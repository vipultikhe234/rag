package com.productrag.search;

import com.productrag.ai.AiProviderException;
import com.productrag.ai.EmbeddingClient;
import com.productrag.common.exception.BadRequestException;
import com.productrag.config.AppProperties;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.embedding.ScoredProduct;
import com.productrag.product.ProductService;
import com.productrag.product.dto.ProductResponse;
import com.productrag.search.SearchCandidateStore.Verified;
import com.productrag.search.dto.SemanticSearchResponse;
import com.productrag.search.dto.SemanticSearchResponse.Hit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hybrid product search: exact SKU matches first, then products whose search document is semantically close to the
 * query (pgvector, ACTIVE products and the current embedding model only), then keyword matches. Every candidate is
 * then verified against the structured filters in PostgreSQL, and prices and stock always come from the product and
 * variant rows, never from the embedded text.
 * <p>
 * Not transactional on purpose: the query is embedded before any database work, so no connection or transaction is
 * held during the call to the embedding provider.
 */
@Service
public class SemanticProductSearchService {

    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_LIMIT = 50;
    static final int MAX_OPTION_FILTERS = 5;
    static final int VECTOR_CANDIDATES = 100;
    static final int KEYWORD_CANDIDATES = 20;

    private static final Logger log = LoggerFactory.getLogger(SemanticProductSearchService.class);
    /** SKU charset of product/variant validation, plus a digit or separator so plain words ("phone") are skipped. */
    private static final Pattern SKU_LIKE = Pattern.compile("(?=.*[0-9._-])[A-Za-z0-9][A-Za-z0-9._-]{1,63}");

    private final EmbeddingClient embeddingClient;
    private final ProductEmbeddingStore embeddingStore;
    private final SearchCandidateStore candidates;
    private final ProductService productService;
    private final double minSimilarity;

    public SemanticProductSearchService(EmbeddingClient embeddingClient, ProductEmbeddingStore embeddingStore,
                                        SearchCandidateStore candidates, ProductService productService,
                                        AppProperties properties) {
        this.embeddingClient = embeddingClient;
        this.embeddingStore = embeddingStore;
        this.candidates = candidates;
        this.productService = productService;
        this.minSimilarity = properties.rag().similarityThreshold();
    }

    public SemanticSearchResponse search(SemanticSearchQuery query) {
        String text = validate(query);
        String model = embeddingClient.modelName();
        String mode = "HYBRID";
        String warning = null;
        Map<UUID, Double> similarity = new LinkedHashMap<>();
        try {
            float[] vector = embeddingClient.embed(text);
            for (ScoredProduct scored : embeddingStore.searchActive(vector, model, minSimilarity, VECTOR_CANDIDATES,
                    List.of())) {
                similarity.put(scored.productId(), scored.similarity());
            }
        } catch (AiProviderException ex) {
            log.warn("Semantic search falls back to keyword matching: embedding provider failed ({})", ex.getCode());
            mode = "KEYWORD_ONLY";
            warning = "The embedding service is unavailable, so only exact SKU and keyword matches are shown.";
        }
        Set<UUID> exact = new LinkedHashSet<>(SKU_LIKE.matcher(text).matches() ? candidates.exactSku(text) : List.of());
        Set<UUID> keyword = new LinkedHashSet<>(candidates.keyword(text, KEYWORD_CANDIDATES));

        Set<UUID> ordered = new LinkedHashSet<>(exact);
        ordered.addAll(similarity.keySet());
        ordered.addAll(keyword);
        Verified verified = candidates.verify(ordered, query);
        List<UUID> ids = ordered.stream().filter(verified.productIds()::contains).limit(query.limit()).toList();

        List<Hit> hits = new ArrayList<>();
        for (ProductResponse product : productService.getAll(ids)) {
            UUID id = product.id();
            boolean exactSku = exact.contains(id);
            String matchType = exactSku ? "EXACT_SKU"
                    : similarity.containsKey(id) && keyword.contains(id) ? "SEMANTIC_AND_KEYWORD"
                    : similarity.containsKey(id) ? "SEMANTIC" : "KEYWORD";
            hits.add(new Hit(product, exactSku ? null : similarity.get(id), matchType,
                    verified.variants().getOrDefault(id, List.of())));
        }
        return new SemanticSearchResponse(text, mode, model, appliedFilters(query), hits, warning);
    }

    private static String validate(SemanticSearchQuery query) {
        String text = query.text() == null ? "" : query.text().strip();
        if (text.length() < 2 || text.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "q must be between 2 and " + MAX_QUERY_LENGTH + " characters");
        }
        if (query.limit() < 1 || query.limit() > MAX_LIMIT) {
            throw new BadRequestException("INVALID_PARAMETER", "limit must be between 1 and " + MAX_LIMIT);
        }
        if (query.minPrice() != null && query.maxPrice() != null && query.minPrice().compareTo(query.maxPrice()) > 0) {
            throw new BadRequestException("INVALID_PARAMETER", "minPrice must not be greater than maxPrice");
        }
        if (query.options().size() > MAX_OPTION_FILTERS) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "At most " + MAX_OPTION_FILTERS + " option filters are allowed");
        }
        return text;
    }

    private static List<String> appliedFilters(SemanticSearchQuery query) {
        List<String> applied = new ArrayList<>(List.of("status = ACTIVE"));
        if (query.categoryId() != null) {
            applied.add("categoryId = " + query.categoryId() + " (including subcategories)");
        }
        if (query.brandId() != null) {
            applied.add("brandId = " + query.brandId());
        }
        if (query.minPrice() != null) {
            applied.add("price >= " + query.minPrice().toPlainString());
        }
        if (query.maxPrice() != null) {
            applied.add("price <= " + query.maxPrice().toPlainString());
        }
        if (query.inStock()) {
            applied.add("in stock");
        }
        query.options().forEach((name, value) -> applied.add("option " + name + " = " + value));
        return applied;
    }
}
