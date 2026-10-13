package com.productrag.vectorsync;

import com.productrag.config.AppProperties;
import com.productrag.product.Product;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes vector-sync events into the outbox table. MANDATORY propagation guarantees the event is
 * committed atomically with the product change that caused it - or not at all.
 */
@Component
public class VectorSyncOutbox {

    private final EntityManager entityManager;
    private final ApplicationEventPublisher publisher;
    private final int maxAttempts;
    private final Clock clock;

    @Autowired
    public VectorSyncOutbox(EntityManager entityManager, ApplicationEventPublisher publisher,
                            AppProperties properties) {
        this(entityManager, publisher, properties.vectorSync().maxAttempts(), Clock.systemUTC());
    }

    VectorSyncOutbox(EntityManager entityManager, ApplicationEventPublisher publisher, int maxAttempts, Clock clock) {
        this.entityManager = entityManager;
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
        publisher.publishEvent(new VectorSyncRequested(event.getId()));
        return event;
    }
}
