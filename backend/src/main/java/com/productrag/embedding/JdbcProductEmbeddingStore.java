package com.productrag.embedding;

import com.pgvector.PGvector;
import com.productrag.product.filter.ProductFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * pgvector-backed embedding store. Vectors are bound as text and cast with {@code CAST(:v AS vector)},
 * which works through any JDBC connection pool without registering custom types.
 * Similarity uses the cosine distance operator {@code <=>} (similarity = 1 - distance), which is
 * also the operator an HNSW index built with {@code vector_cosine_ops} accelerates.
 */
@Repository
public class JdbcProductEmbeddingStore implements ProductEmbeddingStore {

    private static final String METADATA_COLUMNS =
            "product_id, content_hash, embedding_model, source_version, source_updated_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcProductEmbeddingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EmbeddingMetadata> findMetadata(UUID productId) {
        List<EmbeddingMetadata> rows = jdbc.query(
                "SELECT " + METADATA_COLUMNS + " FROM product_embeddings WHERE product_id = :productId AND chunk_index = 0",
                new MapSqlParameterSource("productId", productId), JdbcProductEmbeddingStore::mapMetadata);
        return rows.stream().findFirst();
    }

    @Override
    public Map<UUID, EmbeddingMetadata> findMetadata(Collection<UUID> productIds) {
        Map<UUID, EmbeddingMetadata> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        jdbc.query("SELECT " + METADATA_COLUMNS + " FROM product_embeddings WHERE product_id IN (:ids) AND chunk_index = 0",
                        new MapSqlParameterSource("ids", productIds), JdbcProductEmbeddingStore::mapMetadata)
                .forEach(metadata -> result.put(metadata.productId(), metadata));
        return result;
    }

    @Override
    public boolean upsert(UUID productId, String content, String contentHash, String embeddingModel,
                          float[] embedding, long sourceVersion, Instant sourceUpdatedAt) {
        String sql = """
                INSERT INTO product_embeddings (product_id, chunk_index, content, embedding, content_hash,
                                                embedding_model, source_version, source_updated_at, created_at, updated_at)
                VALUES (:productId, 0, :content, CAST(:embedding AS vector), :contentHash,
                        :model, :sourceVersion, :sourceUpdatedAt, now(), now())
                ON CONFLICT (product_id) DO UPDATE SET
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    content_hash = EXCLUDED.content_hash,
                    embedding_model = EXCLUDED.embedding_model,
                    source_version = EXCLUDED.source_version,
                    source_updated_at = EXCLUDED.source_updated_at,
                    updated_at = now()
                WHERE product_embeddings.source_version <= EXCLUDED.source_version
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("content", content)
                .addValue("embedding", new PGvector(embedding).toString())
                .addValue("contentHash", contentHash)
                .addValue("model", embeddingModel)
                .addValue("sourceVersion", sourceVersion)
                .addValue("sourceUpdatedAt", toOffset(sourceUpdatedAt));
        return jdbc.update(sql, params) > 0;
    }

    @Override
    public void markCurrent(UUID productId, long sourceVersion, Instant sourceUpdatedAt) {
        jdbc.update("""
                        UPDATE product_embeddings
                        SET source_version = :sourceVersion, source_updated_at = :sourceUpdatedAt, updated_at = now()
                        WHERE product_id = :productId AND source_version < :sourceVersion
                        """,
                new MapSqlParameterSource()
                        .addValue("productId", productId)
                        .addValue("sourceVersion", sourceVersion)
                        .addValue("sourceUpdatedAt", toOffset(sourceUpdatedAt)));
    }

    @Override
    public int deleteByProductId(UUID productId) {
        return jdbc.update("DELETE FROM product_embeddings WHERE product_id = :productId",
                new MapSqlParameterSource("productId", productId));
    }

    @Override
    public List<ScoredProduct> searchActive(float[] queryVector, String embeddingModel, double minSimilarity,
                                            int limit, List<ProductFilter> filters) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", new PGvector(queryVector).toString())
                .addValue("model", embeddingModel)
                .addValue("maxDistance", 1.0 - minSimilarity)
                .addValue("limit", limit);
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < filters.size(); i++) {
            String name = "f" + i;
            where.append(" AND ").append(filters.get(i).sqlCondition(name));
            params.addValue(name, filters.get(i).value());
        }
        String sql = """
                SELECT e.product_id, 1 - (e.embedding <=> CAST(:query AS vector)) AS similarity
                FROM product_embeddings e
                JOIN products p ON p.id = e.product_id
                WHERE p.status = 'ACTIVE'
                  AND e.embedding_model = :model
                  AND (e.embedding <=> CAST(:query AS vector)) <= :maxDistance
                """ + where + """

                ORDER BY e.embedding <=> CAST(:query AS vector)
                LIMIT :limit
                """;
        return jdbc.query(sql, params,
                (rs, rowNum) -> new ScoredProduct(rs.getObject("product_id", UUID.class), rs.getDouble("similarity")));
    }

    @Override
    public long countEmbeddings() {
        Long count = jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM product_embeddings", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countActiveProductsWithoutCurrentEmbedding(String embeddingModel) {
        Long count = jdbc.queryForObject("""
                        SELECT count(*) FROM products p
                        WHERE p.status = 'ACTIVE'
                          AND NOT EXISTS (SELECT 1 FROM product_embeddings e
                                          WHERE e.product_id = p.id AND e.embedding_model = :model)
                        """,
                new MapSqlParameterSource("model", embeddingModel), Long.class);
        return count == null ? 0 : count;
    }

    private static EmbeddingMetadata mapMetadata(ResultSet rs, int rowNum) throws SQLException {
        return new EmbeddingMetadata(
                rs.getObject("product_id", UUID.class),
                rs.getString("content_hash"),
                rs.getString("embedding_model"),
                rs.getLong("source_version"),
                rs.getObject("source_updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
