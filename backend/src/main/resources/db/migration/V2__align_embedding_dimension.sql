-- Aligns product_embeddings.embedding with the configured EMBEDDING_DIMENSIONS for databases created with a
-- different dimension (e.g. 1536 for OpenAI, later switched to 768 for a local Ollama model).
-- Vectors of another dimension cannot be converted, so existing ones are removed; re-index afterwards with
-- "Index missing products" (POST /api/v1/admin/vector-sync/resync-missing).
-- No-op when the column already has the configured dimension.
DO $$
BEGIN
    IF (SELECT a.atttypmod FROM pg_attribute a
        WHERE a.attrelid = 'product_embeddings'::regclass AND a.attname = 'embedding' AND NOT a.attisdropped)
       <> ${embedding_dimensions} THEN
        DELETE FROM product_embeddings;
        ALTER TABLE product_embeddings ALTER COLUMN embedding TYPE vector(${embedding_dimensions});
    END IF;
END
$$;
