package com.productrag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.ProductAttributeService;
import com.productrag.catalog.brand.BrandService;
import com.productrag.catalog.brand.dto.BrandResponse;
import com.productrag.catalog.category.CategoryService;
import com.productrag.catalog.variant.ProductVariantService;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.search.CatalogSemanticSearchService;
import com.productrag.search.CatalogSemanticSearchService.Ranking;
import com.productrag.support.TestProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogDiscoveryTest {

    private final QueryIntentParser parser = new QueryIntentParser();
    private CatalogSemanticSearchService searchService;
    private BrandService brandService;
    private CatalogDiscovery discovery;

    @BeforeEach
    void setUp() {
        searchService = mock(CatalogSemanticSearchService.class);
        brandService = mock(BrandService.class);
        discovery = new CatalogDiscovery(searchService, brandService, mock(CategoryService.class),
                mock(ProductAttributeService.class), mock(ProductVariantService.class),
                TestProperties.withRelativeScoreMargin(0.1));
    }

    @Test
    void discoveryQuestionsNameOneModuleAndATopic() {
        assertThat(CatalogDiscovery.detect("Which brands make gaming laptops?"))
                .contains(new CatalogDiscovery.Question(CatalogEntityType.BRAND, "make gaming laptops"));
        assertThat(CatalogDiscovery.detect("which category fits running gear"))
                .contains(new CatalogDiscovery.Question(CatalogEntityType.CATEGORY, "running gear"));
        assertThat(CatalogDiscovery.detect("What attributes describe screen size?"))
                .contains(new CatalogDiscovery.Question(CatalogEntityType.ATTRIBUTE, "describe screen size"));
        assertThat(CatalogDiscovery.detect("show variants with 256 GB storage"))
                .contains(new CatalogDiscovery.Question(CatalogEntityType.VARIANT, "with 256 gb storage"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "how many brands do we have",
            "list all categories",
            "which brands and categories sell laptops",
            "gaming laptops",
            "brands of gaming laptops",
            ""})
    void otherQuestionsAreNotDiscovery(String question) {
        assertThat(CatalogDiscovery.detect(question)).isEmpty();
    }

    @Test
    void priceOrStockConditionsStayWithProductRetrieval() {
        String question = "which brands make laptops under 50000";
        assertThat(discovery.answer(question, intent(question))).isEmpty();
        verifyNoInteractions(searchService);
    }

    @Test
    void relevantBrandsAreListedFromLiveRowsWithoutWeakMatches() {
        BrandResponse asus = brand("ASUS", "Gaming laptops and monitors");
        UUID weak = UUID.randomUUID();
        when(searchService.rank(eq(CatalogEntityType.BRAND), eq("make gaming laptops"), anyInt()))
                .thenReturn(new Ranking(List.of(asus.id(), weak), Map.of(asus.id(), 0.62, weak, 0.41), Set.of(),
                        "test-model", false));
        when(brandService.getAll(List.of(asus.id()))).thenReturn(List.of(asus));

        String question = "Which brands make gaming laptops?";
        Optional<RetrievalResult> result = discovery.answer(question, intent(question));

        assertThat(result).hasValueSatisfying(r -> {
            assertThat(r.strategy()).isEqualTo(RetrievalStrategy.DISCOVERY);
            assertThat(r.products()).isEmpty();
            assertThat(r.totalMatches()).isEqualTo(1L);
            assertThat(r.message()).contains("**ASUS** (4 products): Gaming laptops and monitors")
                    .contains("not an exhaustive list");
        });
    }

    @Test
    void nothingRelevantFallsBackToProductRetrieval() {
        when(searchService.rank(eq(CatalogEntityType.BRAND), eq("make gaming laptops"), anyInt()))
                .thenReturn(new Ranking(List.of(), Map.of(), Set.of(), "test-model", false));

        String question = "Which brands make gaming laptops?";
        assertThat(discovery.answer(question, intent(question))).isEmpty();
        verifyNoInteractions(brandService);
    }

    @Test
    void entitiesThatDisappearedBeforeLoadingAreNotListed() {
        UUID gone = UUID.randomUUID();
        when(searchService.rank(eq(CatalogEntityType.BRAND), eq("make gaming laptops"), anyInt()))
                .thenReturn(new Ranking(List.of(gone), Map.of(gone, 0.7), Set.of(), "test-model", false));
        when(brandService.getAll(anyList())).thenReturn(List.of());

        String question = "Which brands make gaming laptops?";
        assertThat(discovery.answer(question, intent(question))).isEmpty();
    }

    private QueryIntent intent(String question) {
        return parser.parse(question, List.of("Laptops"), List.of("ASUS"), "INR");
    }

    private static BrandResponse brand(String name, String description) {
        return new BrandResponse(UUID.randomUUID(), name, name.toLowerCase(), description, null, null,
                CatalogStatus.ACTIVE, 4, 0L, Instant.now(), Instant.now());
    }
}
