-- Catalog modules: categories (with parent/child hierarchy), brands, category-specific product attribute
-- definitions and product variants. Products gain category_id / brand_id references; the existing
-- products.category and products.brand text columns are kept as denormalized display names because the RAG
-- retrieval and embedding text use them.

-- ---------------------------------------------------------------------------
-- Categories
-- ---------------------------------------------------------------------------
CREATE TABLE categories (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(120) NOT NULL,
    description TEXT,
    image_url   VARCHAR(500),
    parent_id   UUID REFERENCES categories (id) ON DELETE RESTRICT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_slug UNIQUE (slug),
    CONSTRAINT chk_categories_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_categories_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT chk_categories_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT chk_categories_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX uq_categories_name_lower ON categories (lower(name));
CREATE INDEX ix_categories_parent ON categories (parent_id);
CREATE INDEX ix_categories_status ON categories (status);
CREATE INDEX ix_categories_name_trgm ON categories USING gin (lower(name) gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Brands
-- ---------------------------------------------------------------------------
CREATE TABLE brands (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(120) NOT NULL,
    description TEXT,
    logo_url    VARCHAR(500),
    website_url VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_brands_slug UNIQUE (slug),
    CONSTRAINT chk_brands_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_brands_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT chk_brands_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX uq_brands_name_lower ON brands (lower(name));
CREATE INDEX ix_brands_status ON brands (status);
CREATE INDEX ix_brands_name_trgm ON brands USING gin (lower(name) gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Product attribute definitions (per category; inherited by subcategories)
-- ---------------------------------------------------------------------------
CREATE TABLE product_attributes (
    id            UUID PRIMARY KEY,
    category_id   UUID         NOT NULL REFERENCES categories (id) ON DELETE RESTRICT,
    name          VARCHAR(100) NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    data_type     VARCHAR(20)  NOT NULL,
    options       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    unit          VARCHAR(20),
    is_required   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_filterable BOOLEAN      NOT NULL DEFAULT FALSE,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_attributes_category_slug UNIQUE (category_id, slug),
    CONSTRAINT chk_product_attributes_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_product_attributes_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT chk_product_attributes_data_type CHECK (data_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'SELECT')),
    CONSTRAINT chk_product_attributes_options_array CHECK (jsonb_typeof(options) = 'array'),
    CONSTRAINT chk_product_attributes_select_options CHECK (
        (data_type = 'SELECT' AND jsonb_array_length(options) > 0)
        OR (data_type <> 'SELECT' AND jsonb_array_length(options) = 0)),
    CONSTRAINT chk_product_attributes_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX uq_product_attributes_category_name_lower ON product_attributes (category_id, lower(name));
CREATE INDEX ix_product_attributes_status ON product_attributes (status);
CREATE INDEX ix_product_attributes_data_type ON product_attributes (data_type);

-- ---------------------------------------------------------------------------
-- Product variants. RESTRICT keeps a product with variants from being hard-deleted (no orphans).
-- ---------------------------------------------------------------------------
CREATE TABLE product_variants (
    id             UUID PRIMARY KEY,
    product_id     UUID           NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    sku            VARCHAR(64)    NOT NULL,
    variant_name   VARCHAR(200)   NOT NULL,
    attributes     JSONB          NOT NULL DEFAULT '{}'::jsonb,
    price          NUMERIC(12, 2) NOT NULL,
    sale_price     NUMERIC(12, 2),
    stock_quantity INTEGER        NOT NULL DEFAULT 0,
    status         VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_variants_sku UNIQUE (sku),
    CONSTRAINT chk_product_variants_name_not_blank CHECK (btrim(variant_name) <> ''),
    CONSTRAINT chk_product_variants_price_non_negative CHECK (price >= 0),
    CONSTRAINT chk_product_variants_sale_price CHECK (sale_price IS NULL OR (sale_price >= 0 AND sale_price < price)),
    CONSTRAINT chk_product_variants_stock_non_negative CHECK (stock_quantity >= 0),
    CONSTRAINT chk_product_variants_attributes_object CHECK (jsonb_typeof(attributes) = 'object'),
    CONSTRAINT chk_product_variants_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_product_variants_product ON product_variants (product_id);
CREATE INDEX ix_product_variants_status ON product_variants (status);
CREATE INDEX ix_product_variants_stock ON product_variants (stock_quantity);
CREATE INDEX ix_product_variants_price ON product_variants (price);
CREATE INDEX ix_product_variants_sku_trgm ON product_variants USING gin (lower(sku) gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Product references. Nullable so rows written by an older application version stay valid;
-- the application requires a category for every create/update.
-- ---------------------------------------------------------------------------
ALTER TABLE products ADD COLUMN category_id UUID REFERENCES categories (id) ON DELETE RESTRICT;
ALTER TABLE products ADD COLUMN brand_id UUID REFERENCES brands (id) ON DELETE RESTRICT;
CREATE INDEX ix_products_category_id ON products (category_id);
CREATE INDEX ix_products_brand_id ON products (brand_id);

-- ---------------------------------------------------------------------------
-- Backfill: one category / brand per distinct value already used by products, then link the products.
-- Slugs that collide or come out empty get a short hash suffix.
-- ---------------------------------------------------------------------------
WITH names AS (
    SELECT DISTINCT ON (lower(btrim(category))) btrim(category) AS name
    FROM products
    ORDER BY lower(btrim(category)), btrim(category)
), slugged AS (
    SELECT name, btrim(regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g'), '-') AS base FROM names
), numbered AS (
    SELECT name, base, row_number() OVER (PARTITION BY base ORDER BY name) AS n FROM slugged
)
INSERT INTO categories (id, name, slug, description, status)
SELECT gen_random_uuid(), name,
       CASE WHEN base = '' OR n > 1 THEN btrim(base || '-' || substr(md5(name), 1, 8), '-') ELSE base END,
       'Created automatically from existing product data.', 'ACTIVE'
FROM numbered;

UPDATE products p SET category_id = c.id FROM categories c WHERE lower(c.name) = lower(btrim(p.category));

WITH names AS (
    SELECT DISTINCT ON (lower(btrim(brand))) btrim(brand) AS name
    FROM products
    WHERE brand IS NOT NULL AND btrim(brand) <> ''
    ORDER BY lower(btrim(brand)), btrim(brand)
), slugged AS (
    SELECT name, btrim(regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g'), '-') AS base FROM names
), numbered AS (
    SELECT name, base, row_number() OVER (PARTITION BY base ORDER BY name) AS n FROM slugged
)
INSERT INTO brands (id, name, slug, description, status)
SELECT gen_random_uuid(), name,
       CASE WHEN base = '' OR n > 1 THEN btrim(base || '-' || substr(md5(name), 1, 8), '-') ELSE base END,
       'Created automatically from existing product data.', 'ACTIVE'
FROM numbered;

UPDATE products p SET brand_id = b.id FROM brands b
WHERE p.brand IS NOT NULL AND lower(b.name) = lower(btrim(p.brand));
