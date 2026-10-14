package com.productrag.vectorsync;

import com.productrag.ai.EmbeddingClient;
import com.productrag.embedding.CatalogDocumentLoader;
import com.productrag.embedding.CatalogEmbeddingStore;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.embedding.EmbeddingContentBuilder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reconciles the embedding of one brand, category, attribute or variant with its current data, the same way
 * {@link ProductEmbeddingSynchronizer} does for products: rebuild the document, skip the provider when the hash and
 * model are unchanged, embed outside any transaction, and never let a result built from older data win.
 */
@Component
public class CatalogEmbeddingSynchronizer {

    private final CatalogDocumentLoader documentLoader;
    private final CatalogEmbeddingStore embeddingStore;
    private final EmbeddingContentBuilder contentBuilder;
    private final EmbeddingClient embeddingClient;
    private final Clock clock;

    @Autowired
    public CatalogEmbeddingSynchronizer(CatalogDocumentLoader documentLoader, CatalogEmbeddingStore embeddingStore,
                                        EmbeddingContentBuilder contentBuilder, EmbeddingClient embeddingClient) {
        this(documentLoader, embeddingStore, contentBuilder, embeddingClient, Clock.systemUTC());
    }

    CatalogEmbeddingSynchronizer(CatalogDocumentLoader documentLoader, CatalogEmbeddingStore embeddingStore,
                                 EmbeddingContentBuilder contentBuilder, EmbeddingClient embeddingClient, Clock clock) {
        this.documentLoader = documentLoader;
        this.embeddingStore = embeddingStore;
        this.contentBuilder = contentBuilder;
        this.embeddingClient = embeddingClient;
        this.clock = clock;
    }

    public SyncResult synchronize(CatalogEntityType type, UUID id) {
        Instant readAt = clock.instant();
        String content = documentLoader.load(type, List.of(id)).get(id);
        if (content == null) {
            embeddingStore.delete(type, id);
            return SyncResult.VECTORS_DELETED;
        }
        if (content.isBlank()) {
            throw new PermanentSyncException(type + " " + id + " has no content to embed");
        }
        String hash = contentBuilder.hash(content);
        String model = embeddingClient.modelName();
        CatalogEmbeddingStore.Stored stored = embeddingStore.find(type, List.of(id)).get(id);
        if (stored != null && stored.contentHash().equals(hash) && stored.embeddingModel().equals(model)) {
            return SyncResult.UNCHANGED;
        }
        float[] vector = embeddingClient.embed(content);
        if (vector.length != embeddingClient.dimensions()) {
            throw new PermanentSyncException("Embedding model '" + model + "' returned " + vector.length
                    + " dimensions but EMBEDDING_DIMENSIONS is " + embeddingClient.dimensions());
        }
        return embeddingStore.upsert(type, id, content, hash, model, vector, readAt)
                ? SyncResult.EMBEDDED : SyncResult.SUPERSEDED;
    }
}
