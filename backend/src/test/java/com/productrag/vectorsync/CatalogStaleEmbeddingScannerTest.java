package com.productrag.vectorsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.ai.EmbeddingClient;
import com.productrag.embedding.CatalogDocumentLoader;
import com.productrag.embedding.CatalogEmbeddingStore;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.embedding.EmbeddingContentBuilder;
import com.productrag.support.TestProperties;
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

@ExtendWith(MockitoExtension.class)
class CatalogStaleEmbeddingScannerTest {

    @Mock
    private CatalogEmbeddingStore embeddingStore;
    @Mock
    private CatalogDocumentLoader documentLoader;
    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private OutboxStore outboxStore;

    private final EmbeddingContentBuilder builder = new EmbeddingContentBuilder();
    private CatalogStaleEmbeddingScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new CatalogStaleEmbeddingScanner(embeddingStore, documentLoader, builder, embeddingClient,
                outboxStore, TestProperties.defaults());
        when(embeddingClient.modelName()).thenReturn("model-a");
        lenient().when(embeddingStore.activeIdsAfter(any(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyMissingOutdatedOrOtherModelEmbeddingsAreQueuedPerType() {
        UUID current = UUID.randomUUID();
        UUID changed = UUID.randomUUID();
        UUID otherModel = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        List<UUID> batch = List.of(current, changed, otherModel, missing);
        when(embeddingStore.activeIdsAfter(eq(CatalogEntityType.BRAND), any(), anyInt()))
                .thenReturn(batch, List.of());
        when(documentLoader.load(CatalogEntityType.BRAND, batch)).thenReturn(Map.of(
                current, "Brand: Current", changed, "Brand: Changed", otherModel, "Brand: Other",
                missing, "Brand: Missing"));
        when(embeddingStore.find(CatalogEntityType.BRAND, batch)).thenReturn(Map.of(
                current, new CatalogEmbeddingStore.Stored(builder.hash("Brand: Current"), "model-a"),
                changed, new CatalogEmbeddingStore.Stored("old-hash", "model-a"),
                otherModel, new CatalogEmbeddingStore.Stored(builder.hash("Brand: Other"), "model-b")));
        when(outboxStore.enqueueEntities(eq(CatalogEntityType.BRAND), anyCollection(),
                eq(VectorSyncEventType.ENTITY_RESYNC_REQUESTED), anyInt())).thenReturn(3);

        CatalogStaleEmbeddingScanner.Result result = scanner.refreshStale();

        assertThat(result.checked()).isEqualTo(4);
        assertThat(result.stale()).isEqualTo(3);
        assertThat(result.enqueued()).isEqualTo(3);
        assertThat(result.byType().get(CatalogEntityType.BRAND))
                .isEqualTo(new CatalogStaleEmbeddingScanner.Counts(4, 3, 3));
        assertThat(result.byType().get(CatalogEntityType.VARIANT))
                .isEqualTo(new CatalogStaleEmbeddingScanner.Counts(0, 0, 0));
        ArgumentCaptor<Collection<UUID>> queued = ArgumentCaptor.forClass(Collection.class);
        verify(outboxStore).enqueueEntities(eq(CatalogEntityType.BRAND), queued.capture(),
                eq(VectorSyncEventType.ENTITY_RESYNC_REQUESTED), anyInt());
        assertThat(queued.getValue()).containsExactlyInAnyOrder(changed, otherModel, missing);
    }

    @Test
    void keysetPagingContinuesAfterTheLastIdOfEachBatch() {
        UUID first = new UUID(0, 5);
        UUID second = new UUID(0, 9);
        when(embeddingStore.activeIdsAfter(eq(CatalogEntityType.CATEGORY), eq(new UUID(0, 0)), anyInt()))
                .thenReturn(List.of(first));
        when(embeddingStore.activeIdsAfter(eq(CatalogEntityType.CATEGORY), eq(first), anyInt()))
                .thenReturn(List.of(second));
        when(embeddingStore.activeIdsAfter(eq(CatalogEntityType.CATEGORY), eq(second), anyInt()))
                .thenReturn(List.of());
        when(documentLoader.load(eq(CatalogEntityType.CATEGORY), any())).thenReturn(Map.of());
        when(embeddingStore.find(eq(CatalogEntityType.CATEGORY), any())).thenReturn(Map.of());

        CatalogStaleEmbeddingScanner.Result result = scanner.refreshStale();

        assertThat(result.byType().get(CatalogEntityType.CATEGORY).checked()).isEqualTo(2);
        assertThat(result.stale()).as("entities removed between paging and loading are not queued").isZero();
    }
}
