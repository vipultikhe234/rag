package com.productrag.vectorsync;

import com.productrag.embedding.CatalogEntityType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every method is a single SQL statement (atomic under autocommit), so no database transaction is
 * ever held open while the worker calls the embedding provider.
 */
@Repository
public class JdbcOutboxStore implements OutboxStore {

    private static final int MAX_ERROR_LENGTH = 2000;
    private static final VectorSyncEventType CHANGED = VectorSyncEventType.ENTITY_CHANGED;

    /** id, display name and SKU of each entity type, aliased {@code s}, for the event payload. */
    private static final Map<CatalogEntityType, String> ENTITY_ROWS = new EnumMap<>(Map.of(
            CatalogEntityType.BRAND, "SELECT s.id, s.name, CAST(NULL AS varchar) AS sku FROM brands s",
            CatalogEntityType.CATEGORY, "SELECT s.id, s.name, CAST(NULL AS varchar) AS sku FROM categories s",
            CatalogEntityType.ATTRIBUTE, "SELECT s.id, s.name, CAST(NULL AS varchar) AS sku FROM product_attributes s",
            CatalogEntityType.VARIANT, "SELECT s.id, p.name || ' - ' || s.variant_name AS name, s.sku "
                    + "FROM product_variants s JOIN products p ON p.id = s.product_id"));

    /** Wraps a list of category ids into "those categories and all their ancestors". */
    private static final String ANCESTORS_OF = """
            WITH RECURSIVE up(id, parent_id, depth) AS (
                SELECT c.id, c.parent_id, 0 FROM categories c WHERE c.id IN (""";
    private static final String ANCESTORS_END = """
            )
                UNION ALL
                SELECT c.id, c.parent_id, u.depth + 1 FROM categories c JOIN up u ON c.id = u.parent_id
                WHERE u.depth < 20)
            SELECT id FROM up""";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOutboxStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ClaimedEvent> claimBatch(String workerId, int limit) {
        String sql = """
                UPDATE vector_sync_events e
                SET status = 'PROCESSING', locked_by = :workerId, locked_at = now(),
                    attempts = e.attempts + 1, updated_at = now()
                WHERE e.id IN (
                    SELECT c.id FROM vector_sync_events c
                    WHERE c.next_attempt_at <= now()
                      AND (c.status = 'PENDING'
                           OR (c.status = 'FAILED' AND c.retryable AND c.attempts < c.max_attempts))
                      AND NOT EXISTS (SELECT 1 FROM vector_sync_events p
                                      WHERE p.aggregate_id = c.aggregate_id AND p.status = 'PROCESSING')
                    ORDER BY c.created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING e.id, e.aggregate_type, e.aggregate_id, e.event_type, e.attempts, e.max_attempts,
                          e.created_at
                """;
        List<ClaimedRow> rows = jdbc.query(sql,
                new MapSqlParameterSource().addValue("workerId", workerId).addValue("limit", limit),
                (rs, rowNum) -> new ClaimedRow(new ClaimedEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        VectorSyncEventType.valueOf(rs.getString("event_type")),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts")),
                        rs.getObject("created_at", OffsetDateTime.class)));
        // RETURNING does not preserve the sub-select order; process oldest first.
        return rows.stream()
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .map(ClaimedRow::event)
                .toList();
    }

    @Override
    public boolean markCompleted(UUID eventId, String workerId, SyncResult result) {
        return jdbc.update("""
                        UPDATE vector_sync_events
                        SET status = 'COMPLETED', result = :result, processed_at = now(), last_error = NULL,
                            locked_by = NULL, locked_at = NULL, updated_at = now()
                        WHERE id = :id AND status = 'PROCESSING' AND locked_by = :workerId
                        """,
                new MapSqlParameterSource()
                        .addValue("id", eventId)
                        .addValue("workerId", workerId)
                        .addValue("result", result.name())) > 0;
    }

    @Override
    public boolean markRetryableFailure(UUID eventId, String workerId, String error, Instant nextAttemptAt) {
        return jdbc.update("""
                        UPDATE vector_sync_events
                        SET status = 'FAILED', retryable = TRUE, last_error = :error, next_attempt_at = :nextAttemptAt,
                            locked_by = NULL, locked_at = NULL, updated_at = now()
                        WHERE id = :id AND status = 'PROCESSING' AND locked_by = :workerId
                        """,
                new MapSqlParameterSource()
                        .addValue("id", eventId)
                        .addValue("workerId", workerId)
                        .addValue("error", truncate(error))
                        .addValue("nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))) > 0;
    }

    @Override
    public boolean markPermanentFailure(UUID eventId, String workerId, String error) {
        return jdbc.update("""
                        UPDATE vector_sync_events
                        SET status = 'FAILED', retryable = FALSE, last_error = :error,
                            locked_by = NULL, locked_at = NULL, updated_at = now()
                        WHERE id = :id AND status = 'PROCESSING' AND locked_by = :workerId
                        """,
                new MapSqlParameterSource()
                        .addValue("id", eventId)
                        .addValue("workerId", workerId)
                        .addValue("error", truncate(error))) > 0;
    }

    @Override
    public int recoverStale(Duration timeout) {
        return jdbc.update("""
                        UPDATE vector_sync_events
                        SET status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                            retryable = CASE WHEN attempts >= max_attempts THEN FALSE ELSE retryable END,
                            last_error = 'Processing lease expired (worker ' || coalesce(locked_by, 'unknown')
                                         || ' did not finish); ' ||
                                         CASE WHEN attempts >= max_attempts THEN 'attempts exhausted'
                                              ELSE 're-queued' END,
                            locked_by = NULL, locked_at = NULL, next_attempt_at = now(), updated_at = now()
                        WHERE status = 'PROCESSING' AND locked_at < now() - make_interval(secs => :timeoutSeconds)
                        """,
                new MapSqlParameterSource("timeoutSeconds", (double) timeout.toSeconds()));
    }

    @Override
    public boolean retryFailed(UUID eventId) {
        return jdbc.update("""
                        UPDATE vector_sync_events
                        SET status = 'PENDING', attempts = 0, retryable = TRUE, next_attempt_at = now(),
                            last_error = NULL, result = NULL, updated_at = now()
                        WHERE id = :id AND status = 'FAILED'
                        """,
                new MapSqlParameterSource("id", eventId)) > 0;
    }

    @Override
    public int retryAllFailed() {
        return jdbc.getJdbcTemplate().update("""
                UPDATE vector_sync_events
                SET status = 'PENDING', attempts = 0, retryable = TRUE, next_attempt_at = now(),
                    last_error = NULL, result = NULL, updated_at = now()
                WHERE status = 'FAILED'
                """);
    }

    @Override
    public int enqueueResyncForMissingEmbeddings(String embeddingModel, int maxAttempts) {
        return jdbc.update("""
                        INSERT INTO vector_sync_events (id, aggregate_type, aggregate_id, event_type, payload, status,
                                                        attempts, max_attempts, retryable, next_attempt_at,
                                                        created_at, updated_at)
                        SELECT gen_random_uuid(), 'PRODUCT', p.id, 'PRODUCT_RESYNC_REQUESTED',
                               jsonb_build_object('productId', p.id::text, 'sku', p.sku, 'name', p.name,
                                                  'status', p.status, 'productVersion', p.version),
                               'PENDING', 0, :maxAttempts, TRUE, now(), now(), now()
                        FROM products p
                        WHERE NOT EXISTS (SELECT 1 FROM product_embeddings e
                                          WHERE e.product_id = p.id AND e.embedding_model = :model)
                          AND NOT EXISTS (SELECT 1 FROM vector_sync_events v
                                          WHERE v.aggregate_id = p.id AND v.status IN ('PENDING', 'PROCESSING'))
                        """,
                new MapSqlParameterSource()
                        .addValue("model", embeddingModel)
                        .addValue("maxAttempts", maxAttempts));
    }

    @Override
    public int enqueueForProducts(Collection<UUID> productIds, VectorSyncEventType type, int maxAttempts) {
        if (productIds.isEmpty()) {
            return 0;
        }
        return enqueueWhere("p.id IN (:ids)", new MapSqlParameterSource("ids", productIds), type, maxAttempts);
    }

    @Override
    public int enqueueForCategories(Collection<UUID> categoryIds, VectorSyncEventType type, int maxAttempts) {
        if (categoryIds.isEmpty()) {
            return 0;
        }
        return enqueueWhere("p.category_id IN (:ids)", new MapSqlParameterSource("ids", categoryIds), type,
                maxAttempts);
    }

    @Override
    public int enqueueForAttribute(UUID attributeId, String slug, VectorSyncEventType type, int maxAttempts) {
        return enqueueWhere("(jsonb_exists(p.attributes, :slug) OR EXISTS (SELECT 1 FROM product_options o "
                        + "WHERE o.product_id = p.id AND o.attribute_id = :attributeId))",
                new MapSqlParameterSource().addValue("slug", slug).addValue("attributeId", attributeId), type,
                maxAttempts);
    }

    /** {@code condition} is always a constant from this class; values are bound as parameters. */
    private int enqueueWhere(String condition, MapSqlParameterSource params, VectorSyncEventType type,
                             int maxAttempts) {
        return jdbc.update("""
                        INSERT INTO vector_sync_events (id, aggregate_type, aggregate_id, event_type, payload, status,
                                                        attempts, max_attempts, retryable, next_attempt_at,
                                                        created_at, updated_at)
                        SELECT gen_random_uuid(), 'PRODUCT', p.id, :type,
                               jsonb_build_object('productId', p.id::text, 'sku', p.sku, 'name', p.name,
                                                  'status', p.status, 'productVersion', p.version),
                               'PENDING', 0, :maxAttempts, TRUE, now(), now(), now()
                        FROM products p
                        WHERE """ + " " + condition + """

                          AND NOT EXISTS (SELECT 1 FROM vector_sync_events v
                                          WHERE v.aggregate_id = p.id AND v.status = 'PENDING')
                        """,
                params.addValue("type", type.name()).addValue("maxAttempts", maxAttempts));
    }

    @Override
    public int enqueueEntities(CatalogEntityType type, Collection<UUID> ids, VectorSyncEventType eventType,
                               int maxAttempts) {
        if (ids.isEmpty()) {
            return 0;
        }
        return enqueueEntitiesWhere(type, "s.id IN (:ids)", new MapSqlParameterSource("ids", ids), eventType,
                maxAttempts);
    }

    @Override
    public int enqueueProductDependents(Collection<UUID> productIds, int maxAttempts) {
        if (productIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource ids = new MapSqlParameterSource("ids", productIds);
        return enqueueEntitiesWhere(CatalogEntityType.BRAND,
                "s.id IN (SELECT p.brand_id FROM products p WHERE p.id IN (:ids))", ids, CHANGED, maxAttempts)
                + enqueueEntitiesWhere(CatalogEntityType.CATEGORY, "s.id IN (" + ANCESTORS_OF
                        + "SELECT p.category_id FROM products p WHERE p.id IN (:ids)" + ANCESTORS_END + ")",
                ids, CHANGED, maxAttempts)
                + enqueueEntitiesWhere(CatalogEntityType.VARIANT, "s.product_id IN (:ids)", ids, CHANGED, maxAttempts);
    }

    @Override
    public int enqueueCategoryChains(Collection<UUID> categoryIds, int maxAttempts) {
        if (categoryIds.isEmpty()) {
            return 0;
        }
        return enqueueEntitiesWhere(CatalogEntityType.CATEGORY, "s.id IN (" + ANCESTORS_OF + ":ids" + ANCESTORS_END
                + ")", new MapSqlParameterSource("ids", categoryIds), CHANGED, maxAttempts);
    }

    @Override
    public int enqueueAttributesOfCategories(Collection<UUID> categoryIds, int maxAttempts) {
        if (categoryIds.isEmpty()) {
            return 0;
        }
        return enqueueEntitiesWhere(CatalogEntityType.ATTRIBUTE,
                "s.id IN (SELECT ac.attribute_id FROM product_attribute_categories ac WHERE ac.category_id IN (:ids))",
                new MapSqlParameterSource("ids", categoryIds), CHANGED, maxAttempts);
    }

    @Override
    public int enqueueVariantsOfAttribute(UUID attributeId, int maxAttempts) {
        return enqueueEntitiesWhere(CatalogEntityType.VARIANT,
                "s.id IN (SELECT vov.variant_id FROM variant_option_values vov "
                        + "JOIN product_options o ON o.id = vov.option_id WHERE o.attribute_id = :attributeId)",
                new MapSqlParameterSource("attributeId", attributeId), CHANGED, maxAttempts);
    }

    @Override
    public int enqueueMissingCatalogEmbeddings(String embeddingModel, int maxAttempts) {
        int total = 0;
        for (CatalogEntityType type : CatalogEntityType.values()) {
            total += enqueueEntitiesWhere(type, "s.status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM catalog_embeddings e "
                            + "WHERE e.entity_type = :entityType AND e.entity_id = s.id AND e.embedding_model = :model)",
                    new MapSqlParameterSource().addValue("entityType", type.name()).addValue("model", embeddingModel),
                    VectorSyncEventType.ENTITY_RESYNC_REQUESTED, maxAttempts);
        }
        return total;
    }

    /**
     * One event per selected entity that has no PENDING event yet. {@code condition} is always a constant from this
     * class over the alias {@code s} (the entity's table); values are bound as parameters.
     */
    private int enqueueEntitiesWhere(CatalogEntityType type, String condition, MapSqlParameterSource params,
                                     VectorSyncEventType eventType, int maxAttempts) {
        return jdbc.update("""
                        INSERT INTO vector_sync_events (id, aggregate_type, aggregate_id, event_type, payload, status,
                                                        attempts, max_attempts, retryable, next_attempt_at,
                                                        created_at, updated_at)
                        SELECT gen_random_uuid(), :aggregateType, x.id, :eventType,
                               jsonb_strip_nulls(jsonb_build_object('entityType', :aggregateType, 'name', x.name,
                                                                    'sku', x.sku)),
                               'PENDING', 0, :maxAttempts, TRUE, now(), now(), now()
                        FROM (""" + ENTITY_ROWS.get(type) + " WHERE " + condition + """
                        ) x
                        WHERE NOT EXISTS (SELECT 1 FROM vector_sync_events v
                                          WHERE v.aggregate_id = x.id AND v.status = 'PENDING')
                        """,
                params.addValue("aggregateType", type.name())
                        .addValue("eventType", eventType.name())
                        .addValue("maxAttempts", maxAttempts));
    }

    @Override
    public Map<VectorSyncStatus, Long> countByStatus() {
        Map<VectorSyncStatus, Long> counts = new EnumMap<>(VectorSyncStatus.class);
        for (VectorSyncStatus status : VectorSyncStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.getJdbcTemplate().query("SELECT status, count(*) AS n FROM vector_sync_events GROUP BY status",
                rs -> {
                    counts.put(VectorSyncStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        return counts;
    }

    @Override
    public long countRetryableFailed() {
        Long count = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM vector_sync_events WHERE status = 'FAILED' AND retryable AND attempts < max_attempts",
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Instant> oldestPendingCreatedAt() {
        OffsetDateTime oldest = jdbc.getJdbcTemplate().queryForObject(
                "SELECT min(created_at) FROM vector_sync_events WHERE status IN ('PENDING', 'PROCESSING')",
                OffsetDateTime.class);
        return Optional.ofNullable(oldest).map(OffsetDateTime::toInstant);
    }

    @Override
    public Map<UUID, LatestEvent> latestEventPerProduct(Collection<UUID> productIds) {
        Map<UUID, LatestEvent> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                        SELECT DISTINCT ON (aggregate_id) id, aggregate_id, event_type, status, retryable, attempts,
                               max_attempts, last_error, next_attempt_at, updated_at
                        FROM vector_sync_events
                        WHERE aggregate_id IN (:ids)
                        ORDER BY aggregate_id, created_at DESC
                        """,
                        new MapSqlParameterSource("ids", productIds), JdbcOutboxStore::mapLatest)
                .forEach(latest -> result.put(latest.productId(), latest));
        return result;
    }

    private static LatestEvent mapLatest(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime next = rs.getObject("next_attempt_at", OffsetDateTime.class);
        OffsetDateTime updated = rs.getObject("updated_at", OffsetDateTime.class);
        return new LatestEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class),
                VectorSyncEventType.valueOf(rs.getString("event_type")),
                VectorSyncStatus.valueOf(rs.getString("status")),
                rs.getBoolean("retryable"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getString("last_error"),
                next == null ? null : next.toInstant(),
                updated == null ? null : updated.toInstant());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private record ClaimedRow(ClaimedEvent event, OffsetDateTime createdAt) {
    }
}
