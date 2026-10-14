package com.productrag.vectorsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.ai.EmbeddingClient;
import com.productrag.embedding.EmbeddingContentBuilder;
import com.productrag.embedding.EmbeddingMetadata;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.ProductStatus;
import com.productrag.product.ProductTestData;
import com.productrag.support.TestProperties;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class StaleEmbeddingScannerTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductEmbeddingStore embeddingStore;
    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private OutboxStore outboxStore;
    @Mock
    private ApplicationEventPublisher publisher;

    private final EmbeddingContentBuilder builder = new EmbeddingContentBuilder();
    private StaleEmbeddingScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new StaleEmbeddingScanner(productRepository, embeddingStore, builder, products -> Map.of(),
                embeddingClient, outboxStore, publisher, TestProperties.defaults());
        when(embeddingClient.modelName()).thenReturn("model-a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyMissingOutdatedOrOtherModelEmbeddingsAreQueued() {
        Product current = ProductTestData.product("Current", "C-1", "10.00", 1, ProductStatus.ACTIVE);
        Product changed = ProductTestData.product("Changed", "C-2", "10.00", 1, ProductStatus.ACTIVE);
        Product otherModel = ProductTestData.product("Other Model", "C-3", "10.00", 1, ProductStatus.ACTIVE);
        Product missing = ProductTestData.product("Missing", "C-4", "10.00", 1, ProductStatus.ACTIVE);
        List<Product> batch = List.of(current, changed, otherModel, missing);
        when(productRepository.findByStatusAndIdGreaterThanOrderByIdAsc(eq(ProductStatus.ACTIVE), any(UUID.class),
                any(Pageable.class))).thenReturn(batch, List.of());
        when(embeddingStore.findMetadata(anyCollection())).thenReturn(Map.of(
                current.getId(), metadata(current, builder.hash(builder.build(current)), "model-a"),
                changed.getId(), metadata(changed, "old-hash", "model-a"),
                otherModel.getId(), metadata(otherModel, builder.hash(builder.build(otherModel)), "model-b")));
        when(outboxStore.enqueueForProducts(anyCollection(), eq(VectorSyncEventType.PRODUCT_RESYNC_REQUESTED),
                anyInt())).thenReturn(3);

        StaleEmbeddingScanner.Result result = scanner.refreshStale();

        assertThat(result).isEqualTo(new StaleEmbeddingScanner.Result(4, 3, 3));
        ArgumentCaptor<Collection<UUID>> queued = ArgumentCaptor.forClass(Collection.class);
        verify(outboxStore).enqueueForProducts(queued.capture(), eq(VectorSyncEventType.PRODUCT_RESYNC_REQUESTED),
                anyInt());
        assertThat(queued.getValue()).containsExactlyInAnyOrder(changed.getId(), otherModel.getId(), missing.getId());
        verify(publisher).publishEvent(any(VectorSyncRequested.class));
    }

    @Test
    void anUpToDateCatalogQueuesNothing() {
        Product current = ProductTestData.product("Current", "C-1", "10.00", 1, ProductStatus.ACTIVE);
        when(productRepository.findByStatusAndIdGreaterThanOrderByIdAsc(eq(ProductStatus.ACTIVE), any(UUID.class),
                any(Pageable.class))).thenReturn(List.of(current), List.of());
        when(embeddingStore.findMetadata(anyCollection())).thenReturn(Map.of(
                current.getId(), metadata(current, builder.hash(builder.build(current)), "model-a")));

        assertThat(scanner.refreshStale()).isEqualTo(new StaleEmbeddingScanner.Result(1, 0, 0));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    private static EmbeddingMetadata metadata(Product product, String hash, String model) {
        return new EmbeddingMetadata(product.getId(), hash, model, 0, Instant.now(), Instant.now());
    }
}
