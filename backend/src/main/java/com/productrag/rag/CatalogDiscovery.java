package com.productrag.rag;

import static com.productrag.rag.CatalogOverview.markdown;

import com.productrag.catalog.attribute.ProductAttributeService;
import com.productrag.catalog.attribute.dto.ProductAttributeResponse;
import com.productrag.catalog.brand.BrandService;
import com.productrag.catalog.category.CategoryService;
import com.productrag.catalog.variant.ProductVariantService;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.config.AppProperties;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.product.filter.FilterField;
import com.productrag.search.CatalogSemanticSearchService;
import com.productrag.search.CatalogSemanticSearchService.Ranking;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Answers discovery questions about one catalog module ("which brands make gaming laptops?", "which category fits
 * running gear?") from the brand, category, attribute or variant embeddings. The answer is built from live
 * PostgreSQL rows without the LLM and is presented as a relevance ranking, never as a complete list. Questions with
 * price or stock conditions, an explicit SKU or several subjects stay with product retrieval, as does any question
 * for which no entity is relevant enough.
 */
@Component
public class CatalogDiscovery {

    static final int MAX_RESULTS = 5;
    private static final int MAX_DESCRIPTION = 160;

    private static final Map<String, CatalogEntityType> SUBJECT_WORDS = Map.ofEntries(
            Map.entry("brand", CatalogEntityType.BRAND), Map.entry("brands", CatalogEntityType.BRAND),
            Map.entry("manufacturer", CatalogEntityType.BRAND), Map.entry("manufacturers", CatalogEntityType.BRAND),
            Map.entry("category", CatalogEntityType.CATEGORY), Map.entry("categories", CatalogEntityType.CATEGORY),
            Map.entry("subcategory", CatalogEntityType.CATEGORY),
            Map.entry("subcategories", CatalogEntityType.CATEGORY),
            Map.entry("attribute", CatalogEntityType.ATTRIBUTE), Map.entry("attributes", CatalogEntityType.ATTRIBUTE),
            Map.entry("specification", CatalogEntityType.ATTRIBUTE),
            Map.entry("specifications", CatalogEntityType.ATTRIBUTE),
            Map.entry("variant", CatalogEntityType.VARIANT), Map.entry("variants", CatalogEntityType.VARIANT));

    private static final Set<String> CUES = Set.of(("which what whats best top recommend recommended suggest "
            + "find show list any good suitable").split(" "));

    private static final Set<String> STOP_WORDS = Set.of(("a an the of in on at to for and or is are was were be "
            + "do does did have has had there here we you i me us our your my they it its this these those who "
            + "please can could would should will tell give name names some all every each one ones more most "
            + "fit fits fitting suit suits belong belongs go goes under").split(" "));

    private final CatalogSemanticSearchService searchService;
    private final BrandService brandService;
    private final CategoryService categoryService;
    private final ProductAttributeService attributeService;
    private final ProductVariantService variantService;
    private final double relativeScoreMargin;

    public CatalogDiscovery(CatalogSemanticSearchService searchService, BrandService brandService,
                            CategoryService categoryService, ProductAttributeService attributeService,
                            ProductVariantService variantService, AppProperties properties) {
        this.searchService = searchService;
        this.brandService = brandService;
        this.categoryService = categoryService;
        this.attributeService = attributeService;
        this.variantService = variantService;
        this.relativeScoreMargin = properties.rag().relativeScoreMargin();
    }

    public Optional<RetrievalResult> answer(String question, QueryIntent intent) {
        if (intent.sku() != null || intent.isAmbiguous()
                || intent.filters().stream().anyMatch(f -> f.field() == FilterField.PRICE
                || f.field() == FilterField.STOCK_QUANTITY)) {
            return Optional.empty();
        }
        Optional<Question> detected = detect(question);
        if (detected.isEmpty()) {
            return Optional.empty();
        }
        CatalogEntityType type = detected.get().type();
        String topic = detected.get().topic();
        Ranking ranking = searchService.rank(type, topic, MAX_RESULTS * 2);
        List<UUID> ids = closeToBest(ranking);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        List<String> lines = switch (type) {
            case BRAND -> brandService.getAll(ids).stream()
                    .map(b -> "**" + markdown(b.name()) + "** (" + count(b.productCount(), "product", "products")
                            + ")" + description(b.description()))
                    .toList();
            case CATEGORY -> categoryService.getAll(ids).stream()
                    .map(c -> "**" + markdown(c.name()) + "**"
                            + (c.parentName() == null ? "" : " (in " + markdown(c.parentName()) + ")")
                            + " — " + count(c.productCount(), "product", "products") + ", "
                            + count(c.subcategoryCount(), "subcategory", "subcategories")
                            + description(c.description()))
                    .toList();
            case ATTRIBUTE -> attributeService.getAll(ids).stream().map(CatalogDiscovery::attributeLine).toList();
            case VARIANT -> variantService.getAll(ids).stream().map(CatalogDiscovery::variantLine).toList();
        };
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder()
                .append("**").append(plural(type)).append("** most relevant to \"").append(markdown(topic))
                .append("\" (ranked by relevance, not an exhaustive list):\n");
        for (int i = 0; i < lines.size(); i++) {
            text.append("\n").append(i + 1).append(". ").append(lines.get(i));
        }
        if (ranking.degraded()) {
            text.append("\n\nThe embedding service is unavailable, so only name matches are shown.");
        }
        List<String> sources = List.of("status = ACTIVE", type.name().toLowerCase(Locale.ROOT)
                + " embeddings and names (" + ranking.embeddingModel() + ")");
        return Optional.of(new RetrievalResult(RetrievalStrategy.DISCOVERY, List.of(), sources,
                (long) lines.size(), text.toString()));
    }

    /** The module asked about and the remaining topic words, or empty when this is not a discovery question. */
    static Optional<Question> detect(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        List<String> tokens = Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> !t.isEmpty())
                .toList();
        Set<CatalogEntityType> subjects = EnumSet.noneOf(CatalogEntityType.class);
        boolean cue = false;
        List<String> topic = new ArrayList<>();
        for (String token : tokens) {
            CatalogEntityType subject = SUBJECT_WORDS.get(token);
            if (subject != null) {
                subjects.add(subject);
            } else if (CUES.contains(token)) {
                cue = true;
            } else if (!STOP_WORDS.contains(token)) {
                topic.add(token);
            }
        }
        if (subjects.size() != 1 || !cue || topic.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Question(subjects.iterator().next(), String.join(" ", topic)));
    }

    /** Name matches, then vector matches that are not much weaker than the best one, at most {@link #MAX_RESULTS}. */
    private List<UUID> closeToBest(Ranking ranking) {
        double best = ranking.similarity().values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return ranking.ids().stream()
                .filter(id -> ranking.nameMatches().contains(id) || relativeScoreMargin <= 0
                        || ranking.similarity().get(id) >= best - relativeScoreMargin)
                .limit(MAX_RESULTS)
                .toList();
    }

    private static String attributeLine(ProductAttributeResponse a) {
        String categories = a.categories().stream().map(c -> markdown(c.name())).collect(Collectors.joining(", "));
        String options = a.options().isEmpty() ? ""
                : "; options: " + a.options().stream().limit(8).map(CatalogOverview::markdown)
                .collect(Collectors.joining(", ")) + (a.options().size() > 8 ? ", …" : "");
        return "**" + markdown(a.name()) + "** (" + a.dataType().name().toLowerCase(Locale.ROOT)
                + (a.unit() == null ? "" : ", " + markdown(a.unit())) + ")"
                + (categories.isEmpty() ? "" : " — used in " + categories) + options;
    }

    private static String variantLine(ProductVariantResponse v) {
        return "**" + markdown(v.productName()) + " — " + markdown(v.variantName()) + "** (SKU " + markdown(v.sku())
                + ") — " + v.price().toPlainString() + " " + markdown(v.currency()) + ", "
                + (v.stockQuantity() > 0 ? v.stockQuantity() + " in stock" : "out of stock");
    }

    private static String description(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String text = markdown(description);
        return ": " + (text.length() > MAX_DESCRIPTION ? text.substring(0, MAX_DESCRIPTION).strip() + "…" : text);
    }

    private static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    private static String plural(CatalogEntityType type) {
        return switch (type) {
            case BRAND -> "Brands";
            case CATEGORY -> "Categories";
            case ATTRIBUTE -> "Attributes";
            case VARIANT -> "Variants";
        };
    }

    record Question(CatalogEntityType type, String topic) {
    }
}
