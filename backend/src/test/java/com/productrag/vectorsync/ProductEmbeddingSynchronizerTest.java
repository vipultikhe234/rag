package com.productrag.vectorsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.productrag.ai.AiProviderException;
import com.productrag.embedding.EmbeddingContentBuilder;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.ProductStatus;
import com.productrag.product.ProductTestData;
import com.productrag.support.FakeEmbeddingClient;
import com.productrag.support.InMemoryEmbeddingStore;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductEmbeddingSynchronizerTest {

    private ProductRepository repository;
    private InMemoryEmbeddingStore store;
    private FakeEmbeddingClient embeddingClient;
    private ProductEmbeddingSynchronizer synchronizer;
    private Product product;

    @BeforeEach
    void setUp() {
        repository = mock(ProductRepository.class);
        store = new InMemoryEmbeddingStore();
        embeddingClient = new FakeEmbeddingClient();
        synchronizer = new ProductEmbeddingSynchronizer(repository, store, new EmbeddingContentBuilder(),
                products -> Map.of(), embeddingClient);
        product = ProductTestData.headphones();
        when(repository.findById(product.getId())).thenAnswer(invocation -> Optional.of(product));
        when(repository.existsById(product.getId())).thenReturn(true);
    }

    @Test
    void newProductCreatesAnEmbedding() {
        SyncResult result = synchronizer.synchronize(product.getId());

        assertThat(result).isEqualTo(SyncResult.EMBEDDED);
        assertThat(embeddingClient.calls()).isEqualTo(1);
        InMemoryEmbeddingStore.Row row = store.rows().get(product.getId());
        assertThat(row.content()).contains("Wireless Headphones");
        assertThat(row.vector()).hasSize(1536);
        assertThat(row.model()).isEqualTo("test-embedding-model");
    }

    @Test
    void relevantChangeRegeneratesTheEmbeddingInPlace() {
        synchronizer.synchronize(product.getId());
        String firstHash = store.rows().get(product.getId()).hash();

        product.setDescription("Over-ear headphones with a 40-hour battery and spatial audio.");
        ProductTestData.bumpVersion(product);
        SyncResult result = synchronizer.synchronize(product.getId());

        assertThat(result).isEqualTo(SyncResult.EMBEDDED);
        assertThat(embeddingClient.calls()).isEqualTo(2);
        assertThat(store.rows()).hasSize(1);
        assertThat(store.rows().get(product.getId()).hash()).isNotEqualTo(firstHash);
        assertThat(store.rows().get(product.getId()).content()).contains("40-hour battery");
    }

    @Test
    void nonSemanticChangesDoNotCallTheEmbeddingApi() {
        synchronizer.synchronize(product.getId());

        product.setPrice(new BigDecimal("1999.00"));
        product.setStockQuantity(3);
        product.setStatus(ProductStatus.INACTIVE);
        ProductTestData.bumpVersion(product);
        SyncResult result = synchronizer.synchronize(product.getId());

        assertThat(result).isEqualTo(SyncResult.UNCHANGED);
        assertThat(embeddingClient.calls()).isEqualTo(1);
        assertThat(store.rows().get(product.getId()).version()).isEqualTo(product.getVersion());
    }

    @Test
    void duplicateProcessingIsIdempotent() {
        assertThat(synchronizer.synchronize(product.getId())).isEqualTo(SyncResult.EMBEDDED);
        assertThat(synchronizer.synchronize(product.getId())).isEqualTo(SyncResult.UNCHANGED);
        assertThat(synchronizer.synchronize(product.getId())).isEqualTo(SyncResult.UNCHANGED);

        assertThat(embeddingClient.calls()).isEqualTo(1);
        assertThat(store.rows()).hasSize(1);
    }

    @Test
    void deletedProductHasItsVectorsRemoved() {
        synchronizer.synchronize(product.getId());
        when(repository.findById(product.getId())).thenReturn(Optional.empty());

        SyncResult result = synchronizer.synchronize(product.getId());

        assertThat(result).isEqualTo(SyncResult.VECTORS_DELETED);
        assertThat(store.rows()).isEmpty();
        assertThat(embeddingClient.calls()).isEqualTo(1);
    }

    @Test
    void deleteOfUnknownProductIsANoOp() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(synchronizer.synchronize(unknown)).isEqualTo(SyncResult.VECTORS_DELETED);
        assertThat(embeddingClient.calls()).isZero();
    }

    @Test
    void staleResultNeverOverwritesNewerVector() {
        ProductTestData.bumpVersion(product);
        ProductTestData.bumpVersion(product);
        synchronizer.synchronize(product.getId());
        long storedVersion = store.rows().get(product.getId()).version();

        // Simulate a worker that read an older product version and finishes late.
        boolean written = store.upsert(product.getId(), "old content", "old-hash", "test-embedding-model",
                new float[1536], storedVersion - 1, product.getUpdatedAt());

        assertThat(written).isFalse();
        assertThat(store.rows().get(product.getId()).hash()).isNotEqualTo("old-hash");
    }

    @Test
    void providerFailurePropagatesAndLeavesNoPartialVector() {
        embeddingClient.failTransiently();

        assertThatThrownBy(() -> synchronizer.synchronize(product.getId()))
                .isInstanceOf(AiProviderException.class);
        assertThat(store.rows()).isEmpty();
    }

    @Test
    void dimensionMismatchIsAPermanentFailure() {
        embeddingClient.returnDimensions(768);

        assertThatThrownBy(() -> synchronizer.synchronize(product.getId()))
                .isInstanceOf(PermanentSyncException.class)
                .hasMessageContaining("768");
        assertThat(store.rows()).isEmpty();
    }
}
