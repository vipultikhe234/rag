package com.productrag.embedding;

import com.pgvector.PGvector;
import com.productrag.product.ProductSpecifications;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * pgvector store for brand, category, attribute and variant embeddings (one row per entity). Searches only return
 * entities that still exist and are ACTIVE (variants also need an ACTIVE product), so stale rows are never shown.
 */
@Repository
public class CatalogEmbeddingStore {

    /** Join to the source table that keeps only ACTIVE entities; constants, never user input. */
    private static final Map<CatalogEntityType, String> ACTIVE_SOURCE = new EnumMap<>(Map.of(
            CatalogEntityType.BRAND, "JOIN brands s ON s.id = e.entity_id AND s.status = 'ACTIVE'",
            CatalogEntityType.CATEGORY, "JOIN categories s ON s.id = e.entity_id AND s.status = 'ACTIVE'",
            CatalogEntityType.ATTRIBUTE, "JOIN product_attributes s ON s.id = e.entity_id AND s.status = 'ACTIVE'",
            CatalogEntityType.VARIANT, "JOIN product_variants s ON s.id = e.entity_id AND s.status = 'ACTIVE' "
                    + "JOIN products sp ON sp.id = s.product_id AND sp.status = 'ACTIVE'"));

    /** ACTIVE rows of each type, for "not indexed" counts. */
    private static final Map<CatalogEntityType, String> ACTIVE_ROWS = new EnumMap<>(Map.of(
            CatalogEntityType.BRAND, "SELECT s.id FROM brands s WHERE s.status = 'ACTIVE'",
            CatalogEntityType.CATEGORY, "SELECT s.id FROM categories s WHERE s.status = 'ACTIVE'",
            CatalogEntityType.ATTRIBUTE, "SELECT s.id FROM product_attributes s WHERE s.status = 'ACTIVE'",
            CatalogEntityType.VARIANT, "SELECT s.id FROM product_variants s WHERE s.status = 'ACTIVE'"));

    /** Visible ACTIVE rows, the label compared for an exact match, and the text searched for a partial match. */
    private static final Map<CatalogEntityType, NameMatch> NAME_MATCH = new EnumMap<>(Map.of(
            CatalogEntityType.BRAND, new NameMatch("brands s WHERE s.status = 'ACTIVE'", "s.name", "s.name"),
            CatalogEntityType.CATEGORY, new NameMatch("categories s WHERE s.status = 'ACTIVE'", "s.name", "s.name"),
            CatalogEntityType.ATTRIBUTE, new NameMatch("product_attributes s WHERE s.status = 'ACTIVE'",
                    "s.name", "s.name"),
            CatalogEntityType.VARIANT, new NameMatch("product_variants s JOIN products sp ON sp.id = s.product_id "
                    + "WHERE s.status = 'ACTIVE' AND sp.status = 'ACTIVE'", "s.sku",
                    "concat_ws(' ', sp.name, s.variant_name, s.sku)")));

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogEmbeddingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, Stored> find(CatalogEntityType type, Collection<UUID> ids) {
        Map<UUID, Stored> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.query("""
                        SELECT entity_id, content_hash, embedding_model FROM catalog_embeddings
                        WHERE entity_type = :type AND entity_id IN (:ids)
                        """,
                new MapSqlParameterSource().addValue("type", type.name()).addValue("ids", ids),
                rs -> {
                    result.put(rs.getObject("entity_id", UUID.class),
                            new Stored(rs.getString("content_hash"), rs.getString("embedding_model")));
                });
        return result;
    }

    /**
     * Inserts or replaces the entity's embedding unless the stored one was built from data read later than
     * {@code sourceReadAt}; returns false when the write was skipped for that reason.
     */
    public boolean upsert(CatalogEntityType type, UUID id, String content, String contentHash, String model,
                          float[] embedding, Instant sourceReadAt) {
        return jdbc.update("""
                        INSERT INTO catalog_embeddings (entity_type, entity_id, content, embedding, content_hash,
                                                        embedding_model, source_read_at, created_at, updated_at)
                        VALUES (:type, :id, :content, CAST(:embedding AS vector), :hash, :model, :readAt, now(), now())
                        ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                            content = EXCLUDED.content,
                            embedding = EXCLUDED.embedding,
                            content_hash = EXCLUDED.content_hash,
                            embedding_model = EXCLUDED.embedding_model,
                            source_read_at = EXCLUDED.source_read_at,
                            updated_at = now()
                        WHERE catalog_embeddings.source_read_at <= EXCLUDED.source_read_at
                        """,
                new MapSqlParameterSource()
                        .addValue("type", type.name())
                        .addValue("id", id)
                        .addValue("content", content)
                        .addValue("embedding", new PGvector(embedding).toString())
                        .addValue("hash", contentHash)
                        .addValue("model", model)
                        .addValue("readAt", OffsetDateTime.ofInstant(sourceReadAt, ZoneOffset.UTC))) > 0;
    }

    public int delete(CatalogEntityType type, UUID id) {
        return jdbc.update("DELETE FROM catalog_embeddings WHERE entity_type = :type AND entity_id = :id",
                new MapSqlParameterSource().addValue("type", type.name()).addValue("id", id));
    }

    /** Nearest ACTIVE entities of one type with similarity of at least {@code minSimilarity}, best first. */
    public List<Scored> searchActive(CatalogEntityType type, float[] queryVector, String model, double minSimilarity,
                                     int limit) {
        String sql = "SELECT e.entity_id, 1 - (e.embedding <=> CAST(:query AS vector)) AS similarity "
                + "FROM catalog_embeddings e " + ACTIVE_SOURCE.get(type) + """

                WHERE e.entity_type = :type
                  AND e.embedding_model = :model
                  AND (e.embedding <=> CAST(:query AS vector)) <= :maxDistance
                ORDER BY e.embedding <=> CAST(:query AS vector)
                LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("query", new PGvector(queryVector).toString())
                        .addValue("type", type.name())
                        .addValue("model", model)
                        .addValue("maxDistance", 1.0 - minSimilarity)
                        .addValue("limit", limit),
                (rs, rowNum) -> new Scored(rs.getObject("entity_id", UUID.class), rs.getDouble("similarity")));
    }

    /**
     * Visible entities whose name (variants: product name, variant name or SKU) contains {@code text}: exact matches
     * first, then prefix matches, then the rest by name. Works without the embedding provider.
     */
    public List<UUID> nameMatches(CatalogEntityType type, String text, int limit) {
        NameMatch match = NAME_MATCH.get(type);
        String needle = ProductSpecifications.escapeLike(text.strip().toLowerCase(Locale.ROOT));
        String sql = "SELECT s.id FROM " + match.fromWhere()
                + " AND lower(" + match.searched() + ") LIKE :contains ESCAPE '\\'"
                + " ORDER BY CASE WHEN lower(" + match.label() + ") = :exact THEN 0"
                + " WHEN lower(" + match.label() + ") LIKE :prefix ESCAPE '\\' THEN 1 ELSE 2 END, "
                + match.label() + ", s.id LIMIT :limit";
        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("contains", "%" + needle + "%")
                        .addValue("prefix", needle + "%")
                        .addValue("exact", text.strip().toLowerCase(Locale.ROOT))
                        .addValue("limit", limit),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public Map<CatalogEntityType, Long> countByType() {
        Map<CatalogEntityType, Long> counts = new EnumMap<>(CatalogEntityType.class);
        for (CatalogEntityType type : CatalogEntityType.values()) {
            counts.put(type, 0L);
        }
        jdbc.getJdbcTemplate().query("SELECT entity_type, count(*) AS n FROM catalog_embeddings GROUP BY entity_type",
                rs -> {
                    counts.put(CatalogEntityType.valueOf(rs.getString("entity_type")), rs.getLong("n"));
                });
        return counts;
    }

    /** Keyset page of ACTIVE entity ids, ascending, after {@code afterId}. */
    public List<UUID> activeIdsAfter(CatalogEntityType type, UUID afterId, int limit) {
        return jdbc.query(ACTIVE_ROWS.get(type) + " AND s.id > :after ORDER BY s.id LIMIT :limit",
                new MapSqlParameterSource().addValue("after", afterId).addValue("limit", limit),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public long countActiveWithoutEmbedding(CatalogEntityType type, String model) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM (" + ACTIVE_ROWS.get(type) + ") s "
                        + "WHERE NOT EXISTS (SELECT 1 FROM catalog_embeddings e WHERE e.entity_type = :type "
                        + "AND e.entity_id = s.id AND e.embedding_model = :model)",
                new MapSqlParameterSource().addValue("type", type.name()).addValue("model", model), Long.class);
        return count == null ? 0 : count;
    }

    public record Stored(String contentHash, String embeddingModel) {
    }

    public record Scored(UUID id, double similarity) {
    }

    private record NameMatch(String fromWhere, String label, String searched) {
    }
}
