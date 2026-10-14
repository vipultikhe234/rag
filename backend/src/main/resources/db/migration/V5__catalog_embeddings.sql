-- Embeddings for brands, categories, product attributes and product variants (products keep product_embeddings).
-- One current row per entity. There is no foreign key (one table serves four source tables): the sync worker
-- removes the row when its entity is deleted, and every search joins the source table, so an orphan is never
-- returned. Additive only: no existing row is changed or removed.

CREATE TABLE catalog_embeddings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(20)  NOT NULL,
    entity_id       UUID         NOT NULL,
    content         TEXT         NOT NULL,
    embedding       vector(${embedding_dimensions}) NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,
    embedding_model VARCHAR(100) NOT NULL,
    -- When the worker read the source rows; a result built from older data never replaces a newer one.
    source_read_at  TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_catalog_embeddings_entity UNIQUE (entity_type, entity_id),
    CONSTRAINT chk_catalog_embeddings_entity_type CHECK (entity_type IN ('BRAND', 'CATEGORY', 'ATTRIBUTE', 'VARIANT'))
);

CREATE INDEX ix_catalog_embeddings_type_model ON catalog_embeddings (entity_type, embedding_model);
-- No ANN index, as for product_embeddings: exact cosine search over a few hundred rows per type.

ALTER TABLE vector_sync_events DROP CONSTRAINT chk_vse_event_type;
ALTER TABLE vector_sync_events ADD CONSTRAINT chk_vse_event_type CHECK (event_type IN (
    'PRODUCT_CREATED', 'PRODUCT_UPDATED', 'PRODUCT_DEACTIVATED',
    'PRODUCT_REACTIVATED', 'PRODUCT_DELETED', 'PRODUCT_RESYNC_REQUESTED',
    'ENTITY_CHANGED', 'ENTITY_DELETED', 'ENTITY_RESYNC_REQUESTED'));
ALTER TABLE vector_sync_events ADD CONSTRAINT chk_vse_aggregate_type CHECK (aggregate_type IN (
    'PRODUCT', 'BRAND', 'CATEGORY', 'ATTRIBUTE', 'VARIANT'));
