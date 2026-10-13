package com.productrag.embedding;

import com.productrag.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fails fast at startup if the pgvector column dimension differs from EMBEDDING_DIMENSIONS, instead of
 * failing later on every insert. The column dimension is fixed by the first Flyway migration run.
 */
@Component
@Order(0)
public class EmbeddingSchemaVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSchemaVerifier.class);

    private final JdbcTemplate jdbcTemplate;
    private final int configuredDimensions;

    public EmbeddingSchemaVerifier(JdbcTemplate jdbcTemplate, AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.configuredDimensions = properties.ai().embeddingDimensions();
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer columnDimensions = jdbcTemplate.queryForObject("""
                SELECT a.atttypmod FROM pg_attribute a
                WHERE a.attrelid = 'product_embeddings'::regclass AND a.attname = 'embedding' AND NOT a.attisdropped
                """, Integer.class);
        if (columnDimensions == null || columnDimensions != configuredDimensions) {
            throw new IllegalStateException("product_embeddings.embedding is vector(" + columnDimensions
                    + ") but EMBEDDING_DIMENSIONS=" + configuredDimensions
                    + ". Use the same dimension, or add a migration that changes the column and re-sync embeddings"
                    + " (see docs/vector-sync.md).");
        }
        log.info("pgvector column dimension verified: {}", columnDimensions);
    }
}
