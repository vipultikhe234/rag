package com.productrag.vectorsync;

import com.productrag.ai.EmbeddingClient;
import com.productrag.config.AppProperties;
import com.productrag.embedding.CatalogDocumentLoader;
import com.productrag.embedding.CatalogEmbeddingStore;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.embedding.EmbeddingContentBuilder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@link StaleEmbeddingScanner} for brands, categories, attributes and variants: rebuilds each ACTIVE entity's
 * document in keyset batches, compares hash and model with the stored embedding and queues only the outdated ones.
 * Nothing is embedded or deleted here; an interrupted run can simply be started again.
 */
@Component
public class CatalogStaleEmbeddingScanner {

    static final int BATCH_SIZE = 200;
    private static final UUID SMALLEST_UUID = new UUID(0, 0);

    private final CatalogEmbeddingStore embeddingStore;
    private final CatalogDocumentLoader documentLoader;
    private final EmbeddingContentBuilder contentBuilder;
    private final EmbeddingClient embeddingClient;
    private final OutboxStore outboxStore;
    private final int maxAttempts;

    public CatalogStaleEmbeddingScanner(CatalogEmbeddingStore embeddingStore, CatalogDocumentLoader documentLoader,
                                        EmbeddingContentBuilder contentBuilder, EmbeddingClient embeddingClient,
                                        OutboxStore outboxStore, AppProperties properties) {
        this.embeddingStore = embeddingStore;
        this.documentLoader = documentLoader;
        this.contentBuilder = contentBuilder;
        this.embeddingClient = embeddingClient;
        this.outboxStore = outboxStore;
        this.maxAttempts = properties.vectorSync().maxAttempts();
    }

    /** The caller publishes {@link VectorSyncRequested} when anything was enqueued. */
    public Result refreshStale() {
        String model = embeddingClient.modelName();
        Map<CatalogEntityType, Counts> byType = new EnumMap<>(CatalogEntityType.class);
        for (CatalogEntityType type : CatalogEntityType.values()) {
            long checked = 0;
            long stale = 0;
            long enqueued = 0;
            UUID after = SMALLEST_UUID;
            while (true) {
                List<UUID> ids = embeddingStore.activeIdsAfter(type, after, BATCH_SIZE);
                if (ids.isEmpty()) {
                    break;
                }
                Map<UUID, String> documents = documentLoader.load(type, ids);
                Map<UUID, CatalogEmbeddingStore.Stored> stored = embeddingStore.find(type, ids);
                List<UUID> staleIds = new ArrayList<>();
                for (UUID id : ids) {
                    String document = documents.get(id);
                    CatalogEmbeddingStore.Stored current = stored.get(id);
                    if (document != null && (current == null || !model.equals(current.embeddingModel())
                            || !contentBuilder.hash(document).equals(current.contentHash()))) {
                        staleIds.add(id);
                    }
                }
                checked += ids.size();
                stale += staleIds.size();
                enqueued += outboxStore.enqueueEntities(type, staleIds, VectorSyncEventType.ENTITY_RESYNC_REQUESTED,
                        maxAttempts);
                after = ids.getLast();
            }
            byType.put(type, new Counts(checked, stale, enqueued));
        }
        return new Result(byType.values().stream().mapToLong(Counts::checked).sum(),
                byType.values().stream().mapToLong(Counts::stale).sum(),
                byType.values().stream().mapToLong(Counts::enqueued).sum(), byType);
    }

    public record Counts(long checked, long stale, long enqueued) {
    }

    public record Result(long checked, long stale, long enqueued, Map<CatalogEntityType, Counts> byType) {
    }
}
