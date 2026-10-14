package com.productrag.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.ai.AiProviderException;
import com.productrag.ai.EmbeddingClient;
import com.productrag.common.exception.BadRequestException;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.embedding.ScoredProduct;
import com.productrag.product.Product;
import com.productrag.product.ProductService;
import com.productrag.product.ProductStatus;
import com.productrag.product.ProductTestData;
import com.productrag.product.dto.ProductResponse;
import com.productrag.search.SearchCandidateStore.Verified;
import com.productrag.search.dto.SemanticSearchResponse;
import com.productrag.search.dto.SemanticSearchResponse.Hit;
import com.productrag.search.dto.SemanticSearchResponse.MatchingVariant;
import com.productrag.support.TestProperties;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SemanticProductSearchServiceTest {

    private static final float[] VECTOR = {0.1f, 0.2f};

    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private ProductEmbeddingStore embeddingStore;
    @Mock
    private SearchCandidateStore candidates;
    @Mock
    private ProductService productService;

    private final Map<UUID, Product> products = new HashMap<>();
    private SemanticProductSearchService service;
    private Product phone;
    private Product laptop;
    private Product mouse;

    @BeforeEach
    void setUp() {
        service = new SemanticProductSearchService(embeddingClient, embeddingStore, candidates, productService,
                TestProperties.defaults());
        phone = save(ProductTestData.product("Galaxy S24", "GS24", "74999.00", 5, ProductStatus.ACTIVE));
        laptop = save(ProductTestData.product("Gaming Laptop", "GL-15", "99999.00", 2, ProductStatus.ACTIVE));
        mouse = save(ProductTestData.product("Gaming Mouse", "GM-1", "1999.00", 0, ProductStatus.ACTIVE));
        lenient().when(embeddingClient.modelName()).thenReturn("test-model");
        lenient().when(embeddingClient.embed(anyString())).thenReturn(VECTOR);
        lenient().when(productService.getAll(anyList())).thenAnswer(invocation -> invocation.<List<UUID>>getArgument(0)
                .stream().map(id -> ProductResponse.from(products.get(id), null)).toList());
        lenient().when(candidates.verify(anyCollection(), any())).thenAnswer(invocation ->
                new Verified(Set.copyOf(invocation.<Collection<UUID>>getArgument(0)), Map.of()));
    }

    @Test
    void exactSkuComesFirstThenSemanticThenKeywordOnlyMatches() {
        when(candidates.exactSku("GM-1")).thenReturn(List.of(mouse.getId()));
        when(embeddingStore.searchActive(eq(VECTOR), eq("test-model"), anyDouble(), anyInt(), eq(List.of())))
                .thenReturn(List.of(new ScoredProduct(laptop.getId(), 0.81), new ScoredProduct(mouse.getId(), 0.7)));
        when(candidates.keyword("GM-1", SemanticProductSearchService.KEYWORD_CANDIDATES))
                .thenReturn(List.of(phone.getId(), laptop.getId()));

        SemanticSearchResponse response = service.search(query("GM-1", Map.of()));

        assertThat(response.mode()).isEqualTo("HYBRID");
        assertThat(response.results()).extracting(h -> h.product().id())
                .containsExactly(mouse.getId(), laptop.getId(), phone.getId());
        assertThat(response.results()).extracting(Hit::matchType)
                .containsExactly("EXACT_SKU", "SEMANTIC_AND_KEYWORD", "KEYWORD");
        assertThat(response.results().get(0).similarity()).isNull();
        assertThat(response.results().get(1).similarity()).isEqualTo(0.81);
        assertThat(response.results().get(2).similarity()).isNull();
    }

    @Test
    void candidatesFailingTheStructuredFiltersAreDroppedAndMatchingVariantsReturned() {
        when(embeddingStore.searchActive(any(), any(), anyDouble(), anyInt(), anyList()))
                .thenReturn(List.of(new ScoredProduct(laptop.getId(), 0.9), new ScoredProduct(phone.getId(), 0.8)));
        MatchingVariant black = new MatchingVariant(UUID.randomUUID(), "GS24-BLK", "Black", new BigDecimal("70000"),
                null, 3);
        when(candidates.verify(anyCollection(), any()))
                .thenReturn(new Verified(Set.of(phone.getId()), Map.of(phone.getId(), List.of(black))));

        SemanticSearchResponse response = service.search(new SemanticSearchQuery("black phone", null, null, null,
                new BigDecimal("80000"), true, Map.of("Color", "Black"), 10));

        assertThat(response.results()).singleElement().satisfies(hit -> {
            assertThat(hit.product().id()).isEqualTo(phone.getId());
            assertThat(hit.matchingVariants()).containsExactly(black);
        });
        assertThat(response.appliedFilters())
                .contains("status = ACTIVE", "price <= 80000", "in stock", "option Color = Black");
    }

    @Test
    void embeddingOutageFallsBackToKeywordSearchInsteadOfFailing() {
        when(embeddingClient.embed(anyString())).thenThrow(AiProviderException.unreachable(null));
        when(candidates.keyword(anyString(), anyInt())).thenReturn(List.of(laptop.getId()));

        SemanticSearchResponse response = service.search(query("gaming laptop", Map.of()));

        assertThat(response.mode()).isEqualTo("KEYWORD_ONLY");
        assertThat(response.warning()).isNotBlank();
        assertThat(response.results()).extracting(Hit::matchType).containsExactly("KEYWORD");
        verify(embeddingStore, never()).searchActive(any(), any(), anyDouble(), anyInt(), anyList());
    }

    @Test
    void naturalLanguageQueriesAreNotTreatedAsSkus() {
        service.search(query("phone with good camera", Map.of()));
        service.search(query("phone", Map.of()));
        service.search(query("Laptop", Map.of()));

        verify(candidates, never()).exactSku(anyString());
    }

    @Test
    void skusWithoutSeparatorsStillUseExactLookup() {
        when(candidates.exactSku("GS24")).thenReturn(List.of(phone.getId()));

        SemanticSearchResponse response = service.search(query("GS24", Map.of()));

        assertThat(response.results()).extracting(Hit::matchType).containsExactly("EXACT_SKU");
    }

    @Test
    void resultsAreLimited() {
        when(embeddingStore.searchActive(any(), any(), anyDouble(), anyInt(), anyList())).thenReturn(List.of(
                new ScoredProduct(laptop.getId(), 0.9), new ScoredProduct(mouse.getId(), 0.8),
                new ScoredProduct(phone.getId(), 0.7)));

        SemanticSearchResponse response = service.search(new SemanticSearchQuery("gaming", null, null, null, null,
                false, Map.of(), 2));

        assertThat(response.results()).hasSize(2);
    }

    @Test
    void invalidRequestsAreRejectedBeforeAnyEmbeddingCall() {
        assertThatThrownBy(() -> service.search(query(" ", Map.of()))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(query("x".repeat(201), Map.of())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(new SemanticSearchQuery("phones", null, null, new BigDecimal("10"),
                new BigDecimal("5"), false, Map.of(), 10))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(new SemanticSearchQuery("phones", null, null, null, null, false,
                Map.of(), 51))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(query("phones", Map.of("a", "1", "b", "2", "c", "3", "d", "4",
                "e", "5", "f", "6")))).isInstanceOf(BadRequestException.class);
        verify(embeddingClient, never()).embed(anyString());
    }

    private static SemanticSearchQuery query(String text, Map<String, String> options) {
        return new SemanticSearchQuery(text, null, null, null, null, false, options, 10);
    }

    private Product save(Product product) {
        products.put(product.getId(), product);
        return product;
    }
}
