package com.productrag.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.ProductAttributeService;
import com.productrag.catalog.brand.BrandService;
import com.productrag.catalog.brand.dto.BrandResponse;
import com.productrag.catalog.category.CategoryService;
import com.productrag.catalog.variant.ProductVariantService;
import com.productrag.common.exception.BadRequestException;
import com.productrag.embedding.CatalogEmbeddingStore;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.search.dto.CatalogSemanticSearchResponse;
import com.productrag.support.FakeEmbeddingClient;
import com.productrag.support.TestProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogSemanticSearchServiceTest {

    private final FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient();
    private CatalogEmbeddingStore store;
    private BrandService brandService;
    private CatalogSemanticSearchService service;

    private final BrandResponse acme = brand("Acme");
    private final BrandResponse zenith = brand("Zenith");

    @BeforeEach
    void setUp() {
        store = mock(CatalogEmbeddingStore.class);
        brandService = mock(BrandService.class);
        service = new CatalogSemanticSearchService(embeddingClient, store, brandService, mock(CategoryService.class),
                mock(ProductAttributeService.class), mock(ProductVariantService.class), TestProperties.defaults());
    }

    @Test
    void nameMatchesComeFirstThenVectorMatchesWithTheirSimilarity() {
        when(store.searchActive(eq(CatalogEntityType.BRAND), any(), eq(TestProperties.EMBEDDING_MODEL), eq(0.25),
                eq(10))).thenReturn(List.of(new CatalogEmbeddingStore.Scored(zenith.id(), 0.71),
                new CatalogEmbeddingStore.Scored(acme.id(), 0.52)));
        when(store.nameMatches(CatalogEntityType.BRAND, "acme", CatalogSemanticSearchService.NAME_CANDIDATES))
                .thenReturn(List.of(acme.id()));
        when(brandService.getAll(List.of(acme.id(), zenith.id()))).thenReturn(List.of(acme, zenith));

        CatalogSemanticSearchResponse<BrandResponse> response = service.brands(" acme ", 10);

        assertThat(response.mode()).isEqualTo("HYBRID");
        assertThat(response.entityType()).isEqualTo(CatalogEntityType.BRAND);
        assertThat(response.query()).isEqualTo("acme");
        assertThat(response.results()).extracting(CatalogSemanticSearchResponse.Hit::matchType)
                .containsExactly("SEMANTIC_AND_NAME", "SEMANTIC");
        assertThat(response.results()).extracting(CatalogSemanticSearchResponse.Hit::similarity)
                .containsExactly(0.52, 0.71);
        assertThat(response.warning()).isNull();
    }

    @Test
    void anUnavailableProviderFallsBackToNameMatchesWithAWarning() {
        embeddingClient.failTransiently();
        when(store.nameMatches(eq(CatalogEntityType.BRAND), anyString(), anyInt())).thenReturn(List.of(acme.id()));
        when(brandService.getAll(List.of(acme.id()))).thenReturn(List.of(acme));

        CatalogSemanticSearchResponse<BrandResponse> response = service.brands("acme", 5);

        assertThat(response.mode()).isEqualTo("KEYWORD_ONLY");
        assertThat(response.warning()).contains("unavailable");
        assertThat(response.results()).singleElement().satisfies(hit -> {
            assertThat(hit.matchType()).isEqualTo("NAME");
            assertThat(hit.similarity()).isNull();
        });
        verify(store, never()).searchActive(any(), any(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void invalidQueriesAndLimitsAreRejectedBeforeAnyWork() {
        assertThatThrownBy(() -> service.brands(" a ", 10)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.brands("x".repeat(201), 10)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.brands("acme", 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.brands("acme", 51)).isInstanceOf(BadRequestException.class);
        assertThat(embeddingClient.calls()).isZero();
    }

    private static BrandResponse brand(String name) {
        return new BrandResponse(UUID.randomUUID(), name, name.toLowerCase(), null, null, null, CatalogStatus.ACTIVE,
                1, 0L, Instant.now(), Instant.now());
    }
}
