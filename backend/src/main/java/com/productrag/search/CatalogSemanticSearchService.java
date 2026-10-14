package com.productrag.search;

import com.productrag.ai.AiProviderException;
import com.productrag.ai.EmbeddingClient;
import com.productrag.catalog.attribute.ProductAttributeService;
import com.productrag.catalog.attribute.dto.ProductAttributeResponse;
import com.productrag.catalog.brand.BrandService;
import com.productrag.catalog.brand.dto.BrandResponse;
import com.productrag.catalog.category.CategoryService;
import com.productrag.catalog.category.dto.CategoryResponse;
import com.productrag.catalog.variant.ProductVariantService;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.common.exception.BadRequestException;
import com.productrag.config.AppProperties;
import com.productrag.embedding.CatalogEmbeddingStore;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.search.dto.CatalogSemanticSearchResponse;
import com.productrag.search.dto.CatalogSemanticSearchResponse.Hit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Natural-language search inside one catalog module: exact and partial name matches first, then entities whose
 * embedded document is semantically close to the query. Only ACTIVE entities are returned (variants also need an
 * ACTIVE product), and every result is read live from PostgreSQL. When the embedding provider fails the search
 * degrades to name matching with a warning instead of failing.
 * <p>
 * Not transactional on purpose: the query is embedded before any database work.
 */
@Service
public class CatalogSemanticSearchService {

    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_LIMIT = 50;
    static final int NAME_CANDIDATES = 20;

    private static final Logger log = LoggerFactory.getLogger(CatalogSemanticSearchService.class);

    private final EmbeddingClient embeddingClient;
    private final CatalogEmbeddingStore embeddingStore;
    private final BrandService brandService;
    private final CategoryService categoryService;
    private final ProductAttributeService attributeService;
    private final ProductVariantService variantService;
    private final double minSimilarity;

    public CatalogSemanticSearchService(EmbeddingClient embeddingClient, CatalogEmbeddingStore embeddingStore,
                                        BrandService brandService, CategoryService categoryService,
                                        ProductAttributeService attributeService,
                                        ProductVariantService variantService, AppProperties properties) {
        this.embeddingClient = embeddingClient;
        this.embeddingStore = embeddingStore;
        this.brandService = brandService;
        this.categoryService = categoryService;
        this.attributeService = attributeService;
        this.variantService = variantService;
        this.minSimilarity = properties.rag().similarityThreshold();
    }

    public CatalogSemanticSearchResponse<BrandResponse> brands(String q, int limit) {
        return search(CatalogEntityType.BRAND, q, limit, brandService::getAll, BrandResponse::id);
    }

    public CatalogSemanticSearchResponse<CategoryResponse> categories(String q, int limit) {
        return search(CatalogEntityType.CATEGORY, q, limit, categoryService::getAll, CategoryResponse::id);
    }

    public CatalogSemanticSearchResponse<ProductAttributeResponse> attributes(String q, int limit) {
        return search(CatalogEntityType.ATTRIBUTE, q, limit, attributeService::getAll, ProductAttributeResponse::id);
    }

    public CatalogSemanticSearchResponse<ProductVariantResponse> variants(String q, int limit) {
        return search(CatalogEntityType.VARIANT, q, limit, variantService::getAll, ProductVariantResponse::id);
    }

    /** Ranked ids with similarity (null for name-only matches); shared by the API and chat discovery. */
    public Ranking rank(CatalogEntityType type, String text, int limit) {
        String model = embeddingClient.modelName();
        Map<UUID, Double> similarity = new LinkedHashMap<>();
        boolean degraded = false;
        try {
            float[] vector = embeddingClient.embed(text);
            for (CatalogEmbeddingStore.Scored scored : embeddingStore.searchActive(type, vector, model, minSimilarity,
                    limit)) {
                similarity.put(scored.id(), scored.similarity());
            }
        } catch (AiProviderException ex) {
            log.warn("{} search falls back to name matching: embedding provider failed ({})", type, ex.getCode());
            degraded = true;
        }
        Set<UUID> byName = new LinkedHashSet<>(embeddingStore.nameMatches(type, text, NAME_CANDIDATES));
        Set<UUID> ordered = new LinkedHashSet<>(byName);
        ordered.addAll(similarity.keySet());
        return new Ranking(ordered.stream().limit(limit).toList(), similarity, byName, model, degraded);
    }

    private <T> CatalogSemanticSearchResponse<T> search(CatalogEntityType type, String q, int limit,
                                                        Function<List<UUID>, List<T>> loader,
                                                        Function<T, UUID> idOf) {
        String text = validate(q, limit);
        Ranking ranking = rank(type, text, limit);
        List<Hit<T>> hits = new ArrayList<>();
        for (T item : loader.apply(ranking.ids())) {
            UUID id = idOf.apply(item);
            boolean semantic = ranking.similarity().containsKey(id);
            boolean named = ranking.nameMatches().contains(id);
            hits.add(new Hit<>(item, ranking.similarity().get(id),
                    semantic && named ? "SEMANTIC_AND_NAME" : semantic ? "SEMANTIC" : "NAME"));
        }
        return new CatalogSemanticSearchResponse<>(text, type, ranking.degraded() ? "KEYWORD_ONLY" : "HYBRID",
                ranking.embeddingModel(), hits, ranking.degraded()
                        ? "The embedding service is unavailable, so only name matches are shown." : null);
    }

    private static String validate(String q, int limit) {
        String text = q == null ? "" : q.strip();
        if (text.length() < 2 || text.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "q must be between 2 and " + MAX_QUERY_LENGTH + " characters");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("INVALID_PARAMETER", "limit must be between 1 and " + MAX_LIMIT);
        }
        return text;
    }

    /**
     * @param ids         name matches first, then vector matches, at most the requested limit
     * @param similarity  vector similarity per id (vector matches only)
     * @param nameMatches ids whose name contains the query
     * @param degraded    true when the embedding provider failed and only name matches are present
     */
    public record Ranking(List<UUID> ids, Map<UUID, Double> similarity, Set<UUID> nameMatches,
                          String embeddingModel, boolean degraded) {
    }
}
