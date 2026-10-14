package com.productrag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.productrag.ai.AiProviderException;
import com.productrag.ai.LlmClient;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.embedding.ScoredProduct;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.ProductStatus;
import com.productrag.product.ProductTestData;
import com.productrag.rag.dto.SourceReference;
import com.productrag.support.FakeEmbeddingClient;
import com.productrag.support.TestProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class RagServiceTest {

    private ProductRepository repository;
    private ProductEmbeddingStore store;
    private FakeEmbeddingClient embeddingClient;
    private LlmClient llm;
    private RagService ragService;
    private Product headphones;
    private Product speaker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ProductRepository.class);
        store = mock(ProductEmbeddingStore.class);
        embeddingClient = new FakeEmbeddingClient();
        llm = mock(LlmClient.class);
        var properties = TestProperties.defaults();
        ProductRetriever retriever = new ProductRetriever(repository, store, embeddingClient, new QueryIntentParser(),
                new CatalogOverview(mock(NamedParameterJdbcTemplate.class)), mock(CatalogDiscovery.class),
                properties);
        ragService = new RagService(retriever, new PromptBuilder(properties), llm);

        headphones = ProductTestData.headphones();
        speaker = ProductTestData.product("Bluetooth Speaker", "SPK-100", "1499.00", 30, ProductStatus.ACTIVE);
        when(repository.findDistinctActiveCategories()).thenReturn(List.of("Electronics"));
        when(repository.findActiveIdsWithNameMentionedIn(anyString(), anyInt())).thenReturn(List.of());
        when(llm.generate(anyString(), anyString())).thenReturn("The Wireless Headphones (SKU WH-001) last 30 hours.");
    }

    @Test
    void semanticQuestionRetrievesRelevantProductAndCallsLlmWithContext() {
        when(store.searchActive(any(), eq("test-embedding-model"), eq(0.25), eq(5), eq(List.of())))
                .thenReturn(List.of(new ScoredProduct(headphones.getId(), 0.83)));
        when(repository.findAllByIdInAndStatus(List.of(headphones.getId()), ProductStatus.ACTIVE))
                .thenReturn(List.of(headphones));

        RagAnswer answer = ragService.answer("Which headphones have a long battery life?", List.of());

        assertThat(answer.answer()).contains("Wireless Headphones");
        assertThat(answer.retrieval().strategy()).isEqualTo("SEMANTIC");
        assertThat(answer.sources()).extracting(SourceReference::productId).containsExactly(headphones.getId());
        assertThat(answer.sources().getFirst().relevanceScore()).isEqualTo(0.83);
        assertThat(answer.sources().getFirst().matchType()).isEqualTo("SEMANTIC");
        assertThat(embeddingClient.calls()).isEqualTo(1);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).generate(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue())
                .contains("<product_records>")
                .contains("sku: WH-001")
                .contains("price: INR 2499.00")
                .contains("Which headphones have a long battery life?");
    }

    @Test
    void sourcesMatchExactlyTheProductsGivenToTheLlm() {
        when(store.searchActive(any(), anyString(), anyDouble(), anyInt(), anyList()))
                .thenReturn(List.of(new ScoredProduct(speaker.getId(), 0.71), new ScoredProduct(headphones.getId(), 0.52)));
        when(repository.findAllByIdInAndStatus(anyList(), eq(ProductStatus.ACTIVE)))
                .thenReturn(List.of(headphones, speaker));

        RagAnswer answer = ragService.answer("wireless audio products", List.of());

        assertThat(answer.sources()).extracting(SourceReference::sku).containsExactly("SPK-100", "WH-001");
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).generate(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("sku: SPK-100").contains("sku: WH-001")
                .contains("products_listed_below: 2");
    }

    @Test
    void weakMatchesFarBelowTheBestAreLeftOutOfTheContext() {
        var properties = TestProperties.withRelativeScoreMargin(0.1);
        ragService = new RagService(new ProductRetriever(repository, store, embeddingClient, new QueryIntentParser(),
                new CatalogOverview(mock(NamedParameterJdbcTemplate.class)), mock(CatalogDiscovery.class),
                properties), new PromptBuilder(properties), llm);
        when(store.searchActive(any(), anyString(), anyDouble(), anyInt(), anyList()))
                .thenReturn(List.of(new ScoredProduct(headphones.getId(), 0.66), new ScoredProduct(speaker.getId(), 0.53)));
        when(repository.findAllByIdInAndStatus(List.of(headphones.getId()), ProductStatus.ACTIVE))
                .thenReturn(List.of(headphones));

        RagAnswer answer = ragService.answer("something to listen to music", List.of());

        assertThat(answer.sources()).extracting(SourceReference::sku).containsExactly("WH-001");
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).generate(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue()).doesNotContain("SPK-100").contains("products_listed_below: 1");
    }

    @Test
    void inactiveProductsNeverAppearEvenIfReturnedByAQuery() {
        Product inactive = ProductTestData.product("Old Headphones", "OLD-1", "999.00", 1, ProductStatus.INACTIVE);
        when(store.searchActive(any(), anyString(), anyDouble(), anyInt(), anyList()))
                .thenReturn(List.of(new ScoredProduct(inactive.getId(), 0.9)));
        when(repository.findAllByIdInAndStatus(anyList(), eq(ProductStatus.ACTIVE))).thenReturn(List.of(inactive));

        RagAnswer answer = ragService.answer("old headphones", List.of());

        assertThat(answer.sources()).isEmpty();
        verify(llm, never()).generate(anyString(), anyString());
    }

    @Test
    void noRelevantResultsReturnsSafeMessageWithoutCallingLlm() {
        when(store.searchActive(any(), anyString(), anyDouble(), anyInt(), anyList())).thenReturn(List.of());

        RagAnswer answer = ragService.answer("Do you sell submarines?", List.of());

        assertThat(answer.answer()).contains("couldn't find a sufficiently relevant product");
        assertThat(answer.sources()).isEmpty();
        assertThat(answer.retrieval().llmUsed()).isFalse();
        verifyNoInteractions(llm);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactPriceQuestionUsesStructuredDatabaseQueryNotVectors() {
        Product cheap = ProductTestData.product("USB Cable", "USB-1", "299.00", 100, ProductStatus.ACTIVE);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cheap), PageRequest.of(0, 20), 1));

        RagAnswer answer = ragService.answer("Which products cost less than ₹1,000?", List.of());

        assertThat(answer.retrieval().strategy()).isEqualTo("STRUCTURED");
        assertThat(answer.retrieval().totalMatches()).isEqualTo(1L);
        assertThat(answer.retrieval().appliedFilters()).contains("price < 1000.00", "currency = INR");
        assertThat(answer.sources()).extracting(SourceReference::sku).containsExactly("USB-1");
        assertThat(answer.sources().getFirst().relevanceScore()).isNull();
        assertThat(embeddingClient.calls()).isZero();
        verifyNoInteractions(store);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).generate(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("total_matching_active_products: 1 (exact count");
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredQuestionWithNoMatchesDoesNotCallLlm() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        RagAnswer answer = ragService.answer("Which products have stock greater than 10000?", List.of());

        assertThat(answer.answer()).startsWith("No active products match");
        verifyNoInteractions(llm);
    }

    @Test
    void exactSkuLookupUsesDatabase() {
        when(repository.findBySkuAndStatus("WH-001", ProductStatus.ACTIVE)).thenReturn(Optional.of(headphones));

        RagAnswer answer = ragService.answer("What is the price of SKU WH-001?", List.of());

        assertThat(answer.retrieval().strategy()).isEqualTo("EXACT_SKU");
        assertThat(answer.sources()).extracting(SourceReference::price).containsExactly(headphones.getPrice());
        assertThat(embeddingClient.calls()).isZero();
    }

    @Test
    void ambiguousExactConditionAsksForClarificationWithoutAnyAiCall() {
        RagAnswer answer = ragService.answer("Which products are under 1000?", List.of());

        assertThat(answer.retrieval().strategy()).isEqualTo("CLARIFICATION");
        assertThat(answer.answer()).contains("price or the stock");
        assertThat(embeddingClient.calls()).isZero();
        verifyNoInteractions(llm);
    }

    @Test
    void embeddingProviderFailureIsAControlledError() {
        embeddingClient.failTransiently();

        assertThatThrownBy(() -> ragService.answer("headphones for travel", List.of()))
                .isInstanceOf(AiProviderException.class)
                .extracting("code").isEqualTo("AI_PROVIDER_UNAVAILABLE");
        verifyNoInteractions(llm);
    }

    @Test
    void chatProviderFailureIsAControlledError() {
        when(store.searchActive(any(), anyString(), anyDouble(), anyInt(), anyList()))
                .thenReturn(List.of(new ScoredProduct(headphones.getId(), 0.8)));
        when(repository.findAllByIdInAndStatus(anyList(), eq(ProductStatus.ACTIVE))).thenReturn(List.of(headphones));
        when(llm.generate(anyString(), anyString())).thenThrow(AiProviderException.unavailable(null));

        assertThatThrownBy(() -> ragService.answer("headphones for travel", List.of()))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    void productsMentionedByNameAreIncludedFirst() {
        when(repository.findActiveIdsWithNameMentionedIn(anyString(), anyInt())).thenReturn(List.of(speaker.getId()));
        when(store.searchActive(any(), anyString(), anyDouble(), anyInt(), anyList()))
                .thenReturn(List.of(new ScoredProduct(headphones.getId(), 0.6)));
        when(repository.findAllByIdInAndStatus(anyList(), eq(ProductStatus.ACTIVE)))
                .thenReturn(List.of(headphones, speaker));

        RagAnswer answer = ragService.answer("Compare Bluetooth Speaker and Wireless Headphones", List.of());

        assertThat(answer.sources()).extracting(SourceReference::matchType).containsExactly("NAME_MATCH", "SEMANTIC");
    }
}
