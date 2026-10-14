package com.productrag.vectorsync;

import com.productrag.config.AppProperties;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.product.Product;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes vector-sync events into the outbox table. MANDATORY propagation guarantees the event is
 * committed atomically with the catalog change that caused it - or not at all.
 */
@Component
public class VectorSyncOutbox {

    private final EntityManager entityManager;
    private final OutboxStore outboxStore;
    private final ApplicationEventPublisher publisher;
    private final int maxAttempts;
    private final Clock clock;

    @Autowired
    public VectorSyncOutbox(EntityManager entityManager, OutboxStore outboxStore, ApplicationEventPublisher publisher,
                            AppProperties properties) {
        this(entityManager, outboxStore, publisher, properties.vectorSync().maxAttempts(), Clock.systemUTC());
    }

    VectorSyncOutbox(EntityManager entityManager, OutboxStore outboxStore, ApplicationEventPublisher publisher,
                     int maxAttempts, Clock clock) {
        this.entityManager = entityManager;
        this.outboxStore = outboxStore;
        this.publisher = publisher;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public VectorSyncEvent enqueue(Product product, VectorSyncEventType type) {
        // Enough information to identify (and clean up) the product's vectors even after the row is deleted.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", product.getId().toString());
        payload.put("sku", product.getSku());
        payload.put("name", product.getName());
        payload.put("status", product.getStatus().name());
        if (product.getVersion() != null) {
            payload.put("productVersion", product.getVersion());
        }
        VectorSyncEvent event = VectorSyncEvent.pending(product.getId(), type, payload, maxAttempts, clock.instant());
        entityManager.persist(event);
        // The product's brand and categories summarise its products, and its variants show its name, brand and
        // category, so their documents may change too (unchanged ones are not re-embedded).
        entityManager.flush();
        outboxStore.enqueueProductDependents(List.of(product.getId()), maxAttempts);
        publisher.publishEvent(new VectorSyncRequested(event.getId()));
        return event;
    }

    /** Re-syncs a brand, category, attribute or variant whose own fields changed (or that was just created). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueEntity(CatalogEntityType type, UUID id) {
        entityManager.flush();
        return requested(outboxStore.enqueueEntities(type, List.of(id), VectorSyncEventType.ENTITY_CHANGED,
                maxAttempts));
    }

    /** Call before deleting the entity; the worker then removes its embedding. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueEntityDeleted(CatalogEntityType type, UUID id) {
        entityManager.flush();
        return requested(outboxStore.enqueueEntities(type, List.of(id), VectorSyncEventType.ENTITY_DELETED,
                maxAttempts));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueEntities(CatalogEntityType type, Collection<UUID> ids) {
        entityManager.flush();
        return requested(outboxStore.enqueueEntities(type, ids, VectorSyncEventType.ENTITY_CHANGED, maxAttempts));
    }

    /** The categories and all their ancestors: their documents list subcategories and the products below them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueCategoryChains(Collection<UUID> categoryIds) {
        entityManager.flush();
        return requested(outboxStore.enqueueCategoryChains(
                categoryIds.stream().filter(Objects::nonNull).toList(), maxAttempts));
    }

    /**
     * After a product moved to another brand or category: the previous brand and category chain no longer list it.
     * Null ids are ignored.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueuePreviousSummaries(UUID brandId, UUID categoryId) {
        entityManager.flush();
        int count = 0;
        if (brandId != null) {
            count += outboxStore.enqueueEntities(CatalogEntityType.BRAND, List.of(brandId),
                    VectorSyncEventType.ENTITY_CHANGED, maxAttempts);
        }
        if (categoryId != null) {
            count += outboxStore.enqueueCategoryChains(List.of(categoryId), maxAttempts);
        }
        return requested(count);
    }

    /** Attributes assigned to the categories show the category names. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueAttributesOfCategories(Collection<UUID> categoryIds) {
        entityManager.flush();
        return requested(outboxStore.enqueueAttributesOfCategories(categoryIds, maxAttempts));
    }

    /**
     * Re-syncs every product in the categories, e.g. after a category above them was renamed or moved (their
     * search documents contain the category path). Products whose document did not change are not re-embedded.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueForCategories(Collection<UUID> categoryIds) {
        entityManager.flush();
        return requested(outboxStore.enqueueForCategories(categoryIds, VectorSyncEventType.PRODUCT_UPDATED,
                maxAttempts));
    }

    /**
     * Re-syncs products that show the attribute's name or unit (a value for it, or an option linked to it), and
     * the variants whose option values show its unit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueForAttribute(UUID attributeId, String slug) {
        entityManager.flush();
        return requested(outboxStore.enqueueForAttribute(attributeId, slug, VectorSyncEventType.PRODUCT_UPDATED,
                maxAttempts) + outboxStore.enqueueVariantsOfAttribute(attributeId, maxAttempts));
    }

    private int requested(int count) {
        if (count > 0) {
            publisher.publishEvent(new VectorSyncRequested(null));
        }
        return count;
    }
}
