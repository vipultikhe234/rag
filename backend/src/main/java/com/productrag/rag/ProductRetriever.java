package com.productrag.rag;

import com.productrag.ai.EmbeddingClient;
import com.productrag.config.AppProperties;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.embedding.ScoredProduct;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.ProductSpecifications;
import com.productrag.product.ProductStatus;
import com.productrag.product.filter.FilterField;
import com.productrag.product.filter.ProductFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Selects products for the answer context. Exact conditions are always answered from PostgreSQL;
 * pgvector is used for meaning-based matching. Every path is restricted to ACTIVE products, and every
 * returned product is re-loaded from the products table so prices, stock and SKUs are authoritative.
 */
@Component
public class ProductRetriever {

    private static final String ACTIVE_FILTER = "status = ACTIVE";
    private static final int MAX_NAMED_MATCHES = 5;

    private final ProductRepository productRepository;
    private final ProductEmbeddingStore embeddingStore;
    private final EmbeddingClient embeddingClient;
    private final QueryIntentParser intentParser;
    private final AppProperties.Rag settings;
    private final String defaultCurrency;

    public ProductRetriever(ProductRepository productRepository, ProductEmbeddingStore embeddingStore,
                            EmbeddingClient embeddingClient, QueryIntentParser intentParser,
                            AppProperties properties) {
        this.productRepository = productRepository;
        this.embeddingStore = embeddingStore;
        this.embeddingClient = embeddingClient;
        this.intentParser = intentParser;
        this.settings = properties.rag();
        this.defaultCurrency = properties.products().defaultCurrency();
    }

    public RetrievalResult retrieve(String question) {
        QueryIntent intent = intentParser.parse(question, productRepository.findDistinctActiveCategories(),
                defaultCurrency);
        List<String> applied = describe(intent.filters());

        if (intent.isAmbiguous()) {
            return new RetrievalResult(RetrievalStrategy.CLARIFICATION, List.of(), applied, null, intent.ambiguity());
        }
        if (intent.sku() != null) {
            return exactSku(intent.sku());
        }
        boolean structuredOnly = !intent.hasSemanticContent()
                && (intent.hasFilters() || intent.sortHint() != null || intent.countQuestion());
        if (structuredOnly) {
            return structured(intent, applied);
        }
        if (intent.hasFilters()) {
            return hybrid(question, intent, applied);
        }
        return semantic(question, intent);
    }

    private RetrievalResult exactSku(String sku) {
        List<String> applied = List.of(ACTIVE_FILTER, "sku = " + sku);
        return productRepository.findBySkuAndStatus(sku, ProductStatus.ACTIVE)
                .map(product -> new RetrievalResult(RetrievalStrategy.EXACT_SKU,
                        List.of(new RetrievedProduct(product, null, MatchType.EXACT_SKU)), applied, 1L, null))
                .orElseGet(() -> RetrievalResult.message(RetrievalStrategy.EXACT_SKU, applied,
                        "I couldn't find an active product with SKU " + sku
                                + ". Check the SKU, or ask about the product by name."));
    }

    private RetrievalResult structured(QueryIntent intent, List<String> applied) {
        Specification<Product> spec = activeSpec(intent.filters());
        Page<Product> page = productRepository.findAll(spec,
                PageRequest.of(0, settings.structuredResultLimit(), sortFor(intent)));
        if (page.isEmpty()) {
            return RetrievalResult.message(RetrievalStrategy.STRUCTURED, applied,
                    "No active products match the exact conditions (" + String.join("; ", applied) + ").");
        }
        List<RetrievedProduct> products = page.getContent().stream()
                .map(product -> new RetrievedProduct(product, null, MatchType.STRUCTURED_FILTER))
                .toList();
        return new RetrievalResult(RetrievalStrategy.STRUCTURED, products, applied, page.getTotalElements(), null);
    }

    private RetrievalResult hybrid(String question, QueryIntent intent, List<String> applied) {
        long total = productRepository.count(activeSpec(intent.filters()));
        if (total == 0) {
            return RetrievalResult.message(RetrievalStrategy.HYBRID, applied,
                    "No active products match the exact conditions (" + String.join("; ", applied) + ").");
        }
        float[] queryVector = embeddingClient.embed(question);
        List<ScoredProduct> scored = closeToBest(embeddingStore.searchActive(queryVector, embeddingClient.modelName(),
                settings.similarityThreshold(), settings.retrievalLimit(), intent.filters()));
        List<RetrievedProduct> products = sortIfRequested(load(scored, List.of(), List.of()), intent.sortHint());
        if (products.isEmpty()) {
            return new RetrievalResult(RetrievalStrategy.HYBRID, List.of(), applied, total,
                    total + " active product(s) match the exact conditions (" + String.join("; ", applied)
                            + "), but none of them is sufficiently relevant to your question. "
                            + "Try rephrasing with a product name, brand, category or feature.");
        }
        return new RetrievalResult(RetrievalStrategy.HYBRID, products, applied, total, null);
    }

    private RetrievalResult semantic(String question, QueryIntent intent) {
        List<String> applied = List.of(ACTIVE_FILTER);
        float[] queryVector = embeddingClient.embed(question);
        List<ScoredProduct> scored = closeToBest(embeddingStore.searchActive(queryVector, embeddingClient.modelName(),
                settings.similarityThreshold(), settings.retrievalLimit(), List.of()));
        List<UUID> skuMatches = intent.skuCandidates().stream()
                .map(candidate -> productRepository.findBySkuAndStatus(candidate, ProductStatus.ACTIVE))
                .flatMap(Optional::stream)
                .map(Product::getId)
                .toList();
        List<UUID> nameMatches = productRepository.findActiveIdsWithNameMentionedIn(question, MAX_NAMED_MATCHES);
        List<RetrievedProduct> products = sortIfRequested(load(scored, skuMatches, nameMatches), intent.sortHint());
        if (products.isEmpty()) {
            return RetrievalResult.message(RetrievalStrategy.SEMANTIC, applied, settings.noResultsMessage());
        }
        return new RetrievalResult(RetrievalStrategy.SEMANTIC, products, applied, null, null);
    }

    /**
     * Drops weak matches that only cleared the absolute threshold. Small chat models tend to treat every
     * listed product as relevant, so near-misses in the context produce wrong answers.
     */
    private List<ScoredProduct> closeToBest(List<ScoredProduct> scored) {
        double margin = settings.relativeScoreMargin();
        if (margin <= 0 || scored.size() < 2) {
            return scored;
        }
        double best = scored.stream().mapToDouble(ScoredProduct::similarity).max().orElse(0);
        return scored.stream().filter(s -> s.similarity() >= best - margin).toList();
    }

    /** Loads authoritative ACTIVE rows, preserving priority: SKU matches, name matches, then similarity. */
    private List<RetrievedProduct> load(List<ScoredProduct> scored, List<UUID> skuMatches, List<UUID> nameMatches) {
        Map<UUID, Double> scores = scored.stream()
                .collect(Collectors.toMap(ScoredProduct::productId, ScoredProduct::similarity, (a, b) -> a));
        Map<UUID, MatchType> ordered = new LinkedHashMap<>();
        skuMatches.forEach(id -> ordered.putIfAbsent(id, MatchType.EXACT_SKU));
        nameMatches.forEach(id -> ordered.putIfAbsent(id, MatchType.NAME_MATCH));
        scored.forEach(s -> ordered.putIfAbsent(s.productId(), MatchType.SEMANTIC));
        if (ordered.isEmpty()) {
            return List.of();
        }
        Map<UUID, Product> rows = productRepository
                .findAllByIdInAndStatus(new ArrayList<>(ordered.keySet()), ProductStatus.ACTIVE).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        int cap = settings.retrievalLimit() + skuMatches.size() + nameMatches.size();
        List<RetrievedProduct> result = new ArrayList<>();
        ordered.forEach((id, matchType) -> {
            Product product = rows.get(id);
            if (product != null && product.isActive() && result.size() < cap) {
                result.add(new RetrievedProduct(product, scores.get(id), matchType));
            }
        });
        return result;
    }

    private static List<RetrievedProduct> sortIfRequested(List<RetrievedProduct> products, SortHint hint) {
        if (hint == null || products.size() < 2) {
            return products;
        }
        Comparator<RetrievedProduct> comparator = switch (hint) {
            case PRICE_ASC -> Comparator.comparing(p -> p.product().getPrice());
            case PRICE_DESC -> Comparator.comparing((RetrievedProduct p) -> p.product().getPrice()).reversed();
            case STOCK_ASC -> Comparator.comparingInt(p -> p.product().getStockQuantity());
            case STOCK_DESC -> Comparator.comparingInt((RetrievedProduct p) -> p.product().getStockQuantity())
                    .reversed();
        };
        return products.stream().sorted(comparator).toList();
    }

    private static Specification<Product> activeSpec(List<ProductFilter> filters) {
        Specification<Product> spec = ProductSpecifications.statusEquals(ProductStatus.ACTIVE);
        for (ProductFilter filter : filters) {
            spec = spec.and(filter.toSpecification());
        }
        return spec;
    }

    private static Sort sortFor(QueryIntent intent) {
        if (intent.sortHint() != null) {
            return switch (intent.sortHint()) {
                case PRICE_ASC -> Sort.by(Sort.Direction.ASC, "price");
                case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "price");
                case STOCK_ASC -> Sort.by(Sort.Direction.ASC, "stockQuantity");
                case STOCK_DESC -> Sort.by(Sort.Direction.DESC, "stockQuantity");
            };
        }
        boolean hasPrice = intent.filters().stream().anyMatch(f -> f.field() == FilterField.PRICE);
        boolean hasStock = intent.filters().stream().anyMatch(f -> f.field() == FilterField.STOCK_QUANTITY);
        if (hasPrice) {
            return Sort.by(Sort.Direction.ASC, "price").and(Sort.by("name"));
        }
        if (hasStock) {
            return Sort.by(Sort.Direction.DESC, "stockQuantity").and(Sort.by("name"));
        }
        return Sort.by("name");
    }

    private static List<String> describe(List<ProductFilter> filters) {
        List<String> applied = new ArrayList<>();
        applied.add(ACTIVE_FILTER);
        filters.stream().map(ProductFilter::describe).forEach(applied::add);
        return List.copyOf(applied);
    }
}
