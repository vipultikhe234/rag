-- Product management: global attribute definitions assigned to categories, product slug and images, product
-- options with values, variants as unique option combinations, compare-at prices, barcodes and variant images.
-- Existing variants are converted: their attribute keys become product options, sale prices become
-- compare-at prices, and variant products get price/stock summaries calculated from their variants.

-- ---------------------------------------------------------------------------
-- Attribute definitions: one global definition, assigned to one or more categories
-- ---------------------------------------------------------------------------
CREATE TABLE product_attribute_categories (
    attribute_id UUID NOT NULL REFERENCES product_attributes (id) ON DELETE CASCADE,
    category_id  UUID NOT NULL REFERENCES categories (id) ON DELETE RESTRICT,
    PRIMARY KEY (attribute_id, category_id)
);
CREATE INDEX ix_product_attribute_categories_category ON product_attribute_categories (category_id);

INSERT INTO product_attribute_categories (attribute_id, category_id)
SELECT id, category_id FROM product_attributes;

DROP INDEX uq_product_attributes_category_name_lower;
ALTER TABLE product_attributes DROP CONSTRAINT uq_product_attributes_category_slug;
ALTER TABLE product_attributes DROP COLUMN category_id;
ALTER TABLE product_attributes ADD CONSTRAINT uq_product_attributes_slug UNIQUE (slug);
CREATE UNIQUE INDEX uq_product_attributes_name_lower ON product_attributes (lower(name));
CREATE INDEX ix_product_attributes_required ON product_attributes (is_required);

-- ---------------------------------------------------------------------------
-- Products: slug and variant mode
-- ---------------------------------------------------------------------------
ALTER TABLE products ADD COLUMN slug VARCHAR(220);
ALTER TABLE products ADD COLUMN has_variants BOOLEAN NOT NULL DEFAULT FALSE;

WITH slugged AS (
    SELECT id, created_at,
           COALESCE(NULLIF(btrim(left(btrim(regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g'), '-'), 200), '-'), ''),
                    'product') AS base
    FROM products
), numbered AS (
    SELECT id, base, row_number() OVER (PARTITION BY base ORDER BY created_at, id) AS n FROM slugged
)
UPDATE products p
SET slug = CASE WHEN numbered.n = 1 THEN numbered.base ELSE numbered.base || '-' || substr(md5(p.id::text), 1, 8) END
FROM numbered
WHERE numbered.id = p.id;

ALTER TABLE products ALTER COLUMN slug SET NOT NULL;
ALTER TABLE products ADD CONSTRAINT uq_products_slug UNIQUE (slug);
ALTER TABLE products ADD CONSTRAINT chk_products_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

UPDATE products p SET has_variants = TRUE
WHERE EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id);

-- ---------------------------------------------------------------------------
-- Product images (http/https URLs; the project has no file storage)
-- ---------------------------------------------------------------------------
CREATE TABLE product_images (
    id         UUID PRIMARY KEY,
    product_id UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    url        VARCHAR(500) NOT NULL,
    alt_text   VARCHAR(200),
    position   INTEGER      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_images_product_url UNIQUE (product_id, url),
    CONSTRAINT uq_product_images_id_product UNIQUE (id, product_id),
    CONSTRAINT chk_product_images_url CHECK (url ~* '^https?://'),
    CONSTRAINT chk_product_images_position CHECK (position >= 0)
);

-- ---------------------------------------------------------------------------
-- Product options and their values
-- ---------------------------------------------------------------------------
CREATE TABLE product_options (
    id           UUID PRIMARY KEY,
    product_id   UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    name         VARCHAR(50) NOT NULL,
    attribute_id UUID REFERENCES product_attributes (id) ON DELETE RESTRICT,
    position     SMALLINT    NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_options_id_product UNIQUE (id, product_id),
    CONSTRAINT chk_product_options_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_product_options_position CHECK (position >= 0)
);
CREATE UNIQUE INDEX uq_product_options_product_name_lower ON product_options (product_id, lower(name));
CREATE INDEX ix_product_options_name_lower ON product_options (lower(name));
CREATE INDEX ix_product_options_attribute ON product_options (attribute_id);

CREATE TABLE product_option_values (
    id         UUID PRIMARY KEY,
    option_id  UUID         NOT NULL REFERENCES product_options (id) ON DELETE CASCADE,
    value      VARCHAR(100) NOT NULL,
    position   SMALLINT     NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_option_values_id_option UNIQUE (id, option_id),
    CONSTRAINT chk_product_option_values_not_blank CHECK (btrim(value) <> ''),
    CONSTRAINT chk_product_option_values_position CHECK (position >= 0)
);
CREATE UNIQUE INDEX uq_product_option_values_option_value_lower ON product_option_values (option_id, lower(value));

-- ---------------------------------------------------------------------------
-- Variants: compare-at price, barcode, image, option combination
-- ---------------------------------------------------------------------------
ALTER TABLE product_variants ADD CONSTRAINT uq_product_variants_id_product UNIQUE (id, product_id);
ALTER TABLE product_variants ADD COLUMN compare_at_price NUMERIC(12, 2);
ALTER TABLE product_variants ADD COLUMN barcode VARCHAR(64);
ALTER TABLE product_variants ADD COLUMN image_id UUID;
ALTER TABLE product_variants ADD COLUMN option_signature VARCHAR(200);

-- Sale price -> compare-at price: the customer pays the sale price; the old price becomes the crossed-out one.
ALTER TABLE product_variants DROP CONSTRAINT chk_product_variants_sale_price;
UPDATE product_variants SET compare_at_price = price, price = sale_price WHERE sale_price IS NOT NULL;
ALTER TABLE product_variants DROP COLUMN sale_price;
ALTER TABLE product_variants ADD CONSTRAINT chk_product_variants_compare_at_price
    CHECK (compare_at_price IS NULL OR compare_at_price > price);

CREATE UNIQUE INDEX uq_product_variants_barcode ON product_variants (barcode) WHERE barcode IS NOT NULL;
ALTER TABLE product_variants ADD CONSTRAINT fk_product_variants_image
    FOREIGN KEY (image_id, product_id) REFERENCES product_images (id, product_id) ON DELETE SET NULL (image_id);

CREATE TABLE variant_option_values (
    variant_id      UUID NOT NULL,
    product_id      UUID NOT NULL,
    option_id       UUID NOT NULL,
    option_value_id UUID NOT NULL,
    PRIMARY KEY (variant_id, option_id),
    CONSTRAINT fk_variant_option_values_variant FOREIGN KEY (variant_id, product_id)
        REFERENCES product_variants (id, product_id) ON DELETE CASCADE,
    CONSTRAINT fk_variant_option_values_option FOREIGN KEY (option_id, product_id)
        REFERENCES product_options (id, product_id) ON DELETE RESTRICT,
    CONSTRAINT fk_variant_option_values_value FOREIGN KEY (option_value_id, option_id)
        REFERENCES product_option_values (id, option_id) ON DELETE RESTRICT
);
CREATE INDEX ix_variant_option_values_value ON variant_option_values (option_value_id);
CREATE INDEX ix_variant_option_values_option ON variant_option_values (option_id);

-- ---------------------------------------------------------------------------
-- Backfill options from existing variant attribute keys
-- ---------------------------------------------------------------------------
-- Attribute ids applicable to each variant product's category (own category and its ancestors).
CREATE TEMPORARY TABLE tmp_applicable ON COMMIT DROP AS
WITH RECURSIVE chain AS (
    SELECT p.id AS product_id, p.category_id FROM products p WHERE p.has_variants AND p.category_id IS NOT NULL
    UNION ALL
    SELECT chain.product_id, c.parent_id FROM chain JOIN categories c ON c.id = chain.category_id
    WHERE c.parent_id IS NOT NULL
)
SELECT DISTINCT chain.product_id, pac.attribute_id
FROM chain JOIN product_attribute_categories pac ON pac.category_id = chain.category_id;

-- One option per attribute key used by a product's variants; variants without any key get a "Title" option.
CREATE TEMPORARY TABLE tmp_options ON COMMIT DROP AS
WITH keys AS (
    SELECT v.product_id, k.key
    FROM product_variants v CROSS JOIN LATERAL jsonb_object_keys(v.attributes) AS k(key)
    GROUP BY v.product_id, k.key
    UNION
    SELECT v.product_id, NULL
    FROM product_variants v
    WHERE NOT EXISTS (SELECT 1 FROM product_variants w WHERE w.product_id = v.product_id AND w.attributes <> '{}'::jsonb)
    GROUP BY v.product_id
)
SELECT gen_random_uuid() AS option_id,
       keys.product_id,
       keys.key,
       (row_number() OVER (PARTITION BY keys.product_id ORDER BY keys.key) - 1)::smallint AS position,
       left(COALESCE(a.name, initcap(replace(replace(keys.key, '-', ' '), '_', ' ')), 'Title'), 50) AS name,
       CASE WHEN ta.attribute_id IS NOT NULL THEN a.id END AS attribute_id,
       CASE WHEN ta.attribute_id IS NOT NULL AND a.data_type = 'NUMBER' THEN a.unit END AS unit
FROM keys
LEFT JOIN product_attributes a ON a.slug = keys.key
LEFT JOIN tmp_applicable ta ON ta.product_id = keys.product_id AND ta.attribute_id = a.id;

INSERT INTO product_options (id, product_id, name, attribute_id, position)
SELECT option_id, product_id, name, attribute_id, position FROM tmp_options;

CREATE TEMPORARY TABLE tmp_variant_values ON COMMIT DROP AS
SELECT v.id AS variant_id, v.product_id, o.option_id,
       left(CASE WHEN o.key IS NULL THEN btrim(v.variant_name)
                 ELSE COALESCE(NULLIF(btrim(v.attributes ->> o.key), ''), 'Not specified') END, 100) AS value
FROM product_variants v JOIN tmp_options o ON o.product_id = v.product_id;

CREATE TEMPORARY TABLE tmp_values ON COMMIT DROP AS
WITH distinct_values AS (
    SELECT DISTINCT ON (option_id, lower(value)) option_id, value
    FROM tmp_variant_values
    ORDER BY option_id, lower(value), value
)
SELECT gen_random_uuid() AS value_id, option_id, value,
       (row_number() OVER (PARTITION BY option_id
           ORDER BY CASE WHEN value ~ '^[0-9]+(\.[0-9]+)?$' THEN value::numeric END NULLS LAST, lower(value)) - 1)::smallint
           AS position
FROM distinct_values;

INSERT INTO product_option_values (id, option_id, value, position)
SELECT value_id, option_id, value, position FROM tmp_values;

INSERT INTO variant_option_values (variant_id, product_id, option_id, option_value_id)
SELECT vv.variant_id, vv.product_id, vv.option_id, tv.value_id
FROM tmp_variant_values vv
JOIN tmp_values tv ON tv.option_id = vv.option_id AND lower(tv.value) = lower(vv.value);

-- Signature = the variant's option value ids, sorted as text; unique per product (no duplicate combinations).
-- Title = option values in option order, NUMBER values with their attribute unit ("Black / 128 GB").
UPDATE product_variants v
SET option_signature = s.signature,
    variant_name = left(s.title, 200),
    attributes = '{}'::jsonb
FROM (
    SELECT vov.variant_id,
           string_agg(vov.option_value_id::text, ',' ORDER BY vov.option_value_id::text COLLATE "C") AS signature,
           string_agg(CASE WHEN o.unit IS NOT NULL AND tv.value ~ '^[0-9]+(\.[0-9]+)?$'
                           THEN tv.value || ' ' || o.unit ELSE tv.value END, ' / ' ORDER BY o.position) AS title
    FROM variant_option_values vov
    JOIN tmp_options o ON o.option_id = vov.option_id
    JOIN tmp_values tv ON tv.value_id = vov.option_value_id
    GROUP BY vov.variant_id
) s
WHERE s.variant_id = v.id;

ALTER TABLE product_variants ALTER COLUMN option_signature SET NOT NULL;
ALTER TABLE product_variants ADD CONSTRAINT uq_product_variants_option_signature UNIQUE (product_id, option_signature);

-- ---------------------------------------------------------------------------
-- Variant products: price = lowest active variant price (all variants when none is active),
-- stock = total stock of active variants
-- ---------------------------------------------------------------------------
UPDATE products p
SET price = s.price, stock_quantity = s.stock
FROM (
    SELECT product_id,
           COALESCE(min(price) FILTER (WHERE status = 'ACTIVE'), min(price)) AS price,
           COALESCE(sum(stock_quantity) FILTER (WHERE status = 'ACTIVE'), 0)::integer AS stock
    FROM product_variants
    GROUP BY product_id
) s
WHERE s.product_id = p.id;
