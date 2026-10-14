package com.productrag.vectorsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.embedding.CatalogDocumentLoader;
import com.productrag.embedding.CatalogEmbeddingStore;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.embedding.EmbeddingContentBuilder;
import com.productrag.support.FakeEmbeddingClient;
import com.productrag.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogEmbeddingSynchronizerTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final CatalogEntityType BRAND = CatalogEntityType.BRAND;

    @Mock
    private CatalogDocumentLoader documentLoader;
    @Mock
    private CatalogEmbeddingStore embeddingStore;

    private final EmbeddingContentBuilder builder = new EmbeddingContentBuilder();
    private final FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient();
    private final UUID id = UUID.randomUUID();
    private CatalogEmbeddingSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        synchronizer = new CatalogEmbeddingSynchronizer(documentLoader, embeddingStore, builder, embeddingClient,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void newContentIsEmbeddedAndStoredWithTheReadTime() {
        String document = "Brand: Acme\nCategories: Laptops";
        when(documentLoader.load(BRAND, List.of(id))).thenReturn(Map.of(id, document));
        when(embeddingStore.find(BRAND, List.of(id))).thenReturn(Map.of());
        when(embeddingStore.upsert(eq(BRAND), eq(id), eq(document), eq(builder.hash(document)),
                eq(TestProperties.EMBEDDING_MODEL), any(float[].class), eq(NOW))).thenReturn(true);

        assertThat(synchronizer.synchronize(BRAND, id)).isEqualTo(SyncResult.EMBEDDED);
        assertThat(embeddingClient.calls()).isEqualTo(1);
    }

    @Test
    void unchangedContentAndModelSkipTheProvider() {
        String document = "Brand: Acme";
        when(documentLoader.load(BRAND, List.of(id))).thenReturn(Map.of(id, document));
        when(embeddingStore.find(BRAND, List.of(id))).thenReturn(Map.of(id,
                new CatalogEmbeddingStore.Stored(builder.hash(document), TestProperties.EMBEDDING_MODEL)));

        assertThat(synchronizer.synchronize(BRAND, id)).isEqualTo(SyncResult.UNCHANGED);
        assertThat(embeddingClient.calls()).isZero();
        verify(embeddingStore, never()).upsert(any(), any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void anotherModelIsReEmbedded() {
        String document = "Brand: Acme";
        when(documentLoader.load(BRAND, List.of(id))).thenReturn(Map.of(id, document));
        when(embeddingStore.find(BRAND, List.of(id))).thenReturn(Map.of(id,
                new CatalogEmbeddingStore.Stored(builder.hash(document), "old-model")));
        when(embeddingStore.upsert(eq(BRAND), eq(id), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);

        assertThat(synchronizer.synchronize(BRAND, id)).isEqualTo(SyncResult.EMBEDDED);
    }

    @Test
    void aDeletedEntityLosesItsEmbedding() {
        when(documentLoader.load(BRAND, List.of(id))).thenReturn(Map.of());

        assertThat(synchronizer.synchronize(BRAND, id)).isEqualTo(SyncResult.VECTORS_DELETED);
        verify(embeddingStore).delete(BRAND, id);
        assertThat(embeddingClient.calls()).isZero();
    }

    @Test
    void aNewerStoredEmbeddingWins() {
        when(documentLoader.load(BRAND, List.of(id))).thenReturn(Map.of(id, "Brand: Acme"));
        when(embeddingStore.find(BRAND, List.of(id))).thenReturn(Map.of());
        when(embeddingStore.upsert(eq(BRAND), eq(id), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(false);

        assertThat(synchronizer.synchronize(BRAND, id)).isEqualTo(SyncResult.SUPERSEDED);
    }

    @Test
    void aDimensionMismatchFailsPermanently() {
        when(documentLoader.load(BRAND, List.of(id))).thenReturn(Map.of(id, "Brand: Acme"));
        when(embeddingStore.find(BRAND, List.of(id))).thenReturn(Map.of());
        embeddingClient.returnDimensions(8);

        assertThatThrownBy(() -> synchronizer.synchronize(BRAND, id)).isInstanceOf(PermanentSyncException.class)
                .hasMessageContaining("8 dimensions");
    }
}
