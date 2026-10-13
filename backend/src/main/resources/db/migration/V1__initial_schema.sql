-- Product RAG Admin - initial schema.
-- The embedding dimension is injected by Flyway from app.ai.embedding-dimensions (EMBEDDING_DIMENSIONS).

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------
CREATE TABLE app_users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_users_email UNIQUE (email),
    CONSTRAINT chk_app_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT chk_app_users_role CHECK (role IN ('ADMIN', 'USER'))
);

-- ---------------------------------------------------------------------------
-- Products (authoritative relational data)
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id             UUID PRIMARY KEY,
    name           VARCHAR(200)  NOT NULL,
    sku            VARCHAR(64)   NOT NULL,
    description    TEXT          NOT NULL,
    category       VARCHAR(100)  NOT NULL,
    brand          VARCHAR(100),
    price          NUMERIC(12, 2) NOT NULL,
    currency       VARCHAR(3)    NOT NULL DEFAULT 'INR',
    stock_quantity INTEGER       NOT NULL DEFAULT 0,
    attributes     JSONB         NOT NULL DEFAULT '{}'::jsonb,
    status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT chk_products_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_products_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT chk_products_category_not_blank CHECK (btrim(category) <> ''),
    CONSTRAINT chk_products_price_non_negative CHECK (price >= 0),
    CONSTRAINT chk_products_stock_non_negative CHECK (stock_quantity >= 0),
    CONSTRAINT chk_products_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_products_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_products_attributes_object CHECK (jsonb_typeof(attributes) = 'object')
);

CREATE INDEX ix_products_status ON products (status);
CREATE INDEX ix_products_category_lower ON products (lower(category));
CREATE INDEX ix_products_created_at ON products (created_at DESC);
CREATE INDEX ix_products_active_price ON products (currency, price) WHERE status = 'ACTIVE';
CREATE INDEX ix_products_active_stock ON products (stock_quantity) WHERE status = 'ACTIVE';
CREATE INDEX ix_products_name_trgm ON products USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_products_sku_trgm ON products USING gin (lower(sku) gin_trgm_ops);
CREATE INDEX ix_products_brand_trgm ON products USING gin (lower(brand) gin_trgm_ops);
CREATE INDEX ix_products_category_trgm ON products USING gin (lower(category) gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Product embeddings (pgvector). One row per product in v1 (chunk_index = 0).
-- Rows are removed automatically when the product row is deleted, so orphaned
-- vectors cannot exist. Retrieval always joins products and filters status = 'ACTIVE'.
-- ---------------------------------------------------------------------------
CREATE TABLE product_embeddings (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id        UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    chunk_index       INTEGER      NOT NULL DEFAULT 0,
    content           TEXT         NOT NULL,
    embedding         vector(${embedding_dimensions}) NOT NULL,
    content_hash      VARCHAR(64)  NOT NULL,
    embedding_model   VARCHAR(100) NOT NULL,
    source_version    BIGINT       NOT NULL,
    source_updated_at TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- v1: exactly one embedding per product. To support multiple chunks later, drop this
    -- constraint and add UNIQUE (product_id, chunk_index) - see docs/vector-sync.md.
    CONSTRAINT uq_product_embeddings_product UNIQUE (product_id),
    CONSTRAINT chk_product_embeddings_chunk_index CHECK (chunk_index >= 0)
);

CREATE INDEX ix_product_embeddings_model ON product_embeddings (embedding_model);
-- No ANN index on purpose: exact (sequential) cosine search is accurate and fast for small
-- catalogs. See docs/vector-sync.md for adding an HNSW index as the catalog grows.

-- ---------------------------------------------------------------------------
-- Transactional outbox for vector synchronization.
-- No foreign key to products: delete events must outlive the product row.
-- ---------------------------------------------------------------------------
CREATE TABLE vector_sync_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(50)   NOT NULL DEFAULT 'PRODUCT',
    aggregate_id    UUID          NOT NULL,
    event_type      VARCHAR(40)   NOT NULL,
    payload         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER       NOT NULL DEFAULT 0,
    max_attempts    INTEGER       NOT NULL,
    retryable       BOOLEAN       NOT NULL DEFAULT TRUE,
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error      VARCHAR(2000),
    result          VARCHAR(40),
    locked_by       VARCHAR(100),
    locked_at       TIMESTAMPTZ,
    processed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_vse_event_type CHECK (event_type IN (
        'PRODUCT_CREATED', 'PRODUCT_UPDATED', 'PRODUCT_DEACTIVATED',
        'PRODUCT_REACTIVATED', 'PRODUCT_DELETED', 'PRODUCT_RESYNC_REQUESTED')),
    CONSTRAINT chk_vse_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_vse_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_vse_max_attempts CHECK (max_attempts > 0)
);

CREATE INDEX ix_vse_claimable ON vector_sync_events (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX ix_vse_processing ON vector_sync_events (locked_at) WHERE status = 'PROCESSING';
CREATE INDEX ix_vse_aggregate ON vector_sync_events (aggregate_id, created_at DESC);
CREATE INDEX ix_vse_status_created ON vector_sync_events (status, created_at DESC);

-- ---------------------------------------------------------------------------
-- Chat history (private per user)
-- ---------------------------------------------------------------------------
CREATE TABLE chat_conversations (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    title      VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_chat_conversations_user ON chat_conversations (user_id, updated_at DESC);

CREATE TABLE chat_messages (
    id              UUID PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES chat_conversations (id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    sources         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    retrieval       JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_chat_messages_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX ix_chat_messages_conversation ON chat_messages (conversation_id, created_at);
