# Database

PostgreSQL 16, local DB `RagDB` (credentials only in `.env`). JPA `ddl-auto: none`; schema owned by Flyway.

## Migrations (`backend/src/main/resources/db/migration/`)
| File | Content | Applied to RagDB? |
|------|---------|-------------------|
| `V1__initial_schema.sql` | extensions, products, product_embeddings, vector_sync_events, app_users, chat tables | yes |
| `V2__align_embedding_dimension.sql` | embedding column dimension = `${embedding_dimensions}` | yes |
| `V3__catalog_modules.sql` | catalog tables + product FKs + backfill | yes (also on scratch `ragdb_catalog_check`) |
| `V4__product_options_and_media.sql` | options, option values, variant options, images, product slug/has_variants, compare-at price, barcode, attribute-category join | **not yet**; applied and verified on scratch `ragdb_v4_check` (copy of RagDB data) and `ragdb_it_test` |

Rule: never edit an applied migration; add `V{n+1}__*.sql`.

## V4 (implemented)
- `products`: + `slug` (`uq_products_slug`, format check; backfilled from name, hash suffix on collision),
  + `has_variants` (true where variants exist). For variant products `price` = lowest ACTIVE variant price (all
  variants if none active), `stock_quantity` = sum of ACTIVE variant stock; recomputed by the backend
  (`ProductVariantService`) on every variant write.
- `product_images`: id, product_id (FK CASCADE), url, alt_text, position; unique (product_id, url),
  unique (id, product_id) for the variant composite FK.
- `product_attribute_categories`: (attribute_id, category_id) PK, both FK RESTRICT, idx category; filled from
  `product_attributes.category_id`, which is dropped. `product_attributes`: `uq_product_attributes_slug`,
  `uq_product_attributes_name_lower` (global), idx `is_required`.
- `product_options`: id, product_id (FK CASCADE), name, attribute_id (nullable FK), position;
  unique (product_id, lower(name)), unique (id, product_id); idx lower(name), attribute_id.
- `product_option_values`: id, option_id (FK CASCADE), value, position; unique (option_id, lower(value)),
  unique (id, option_id).
- `variant_option_values`: (variant_id, option_id) PK, product_id; composite FKs keep the option on the same
  product and the value on its option (RESTRICT); idx option_value_id, option_id (variant option filter).
- `product_variants`: + `option_signature` NOT NULL (sorted value ids; `uq_product_variants_option_signature`
  per product), + `compare_at_price` (> price), + `barcode` (`uq_product_variants_barcode`, partial), + `image_id`
  (composite FK to the product's images, `ON DELETE SET NULL (image_id)`); `sale_price` converted then dropped;
  `variant_name` holds the generated title ("Black / 128 GB").
- Backfill: per variant product, one option per variant attribute key (named after the attribute definition when
  the slug exists, linked to it when applicable to the product's category; "Title" when variants have no keys),
  values from the JSON ("Not specified" when missing); keys moved out of variant `attributes`, which become `{}`.
  Duplicate combinations within a product would violate the signature constraint and roll the migration back
  (Flyway runs it in one transaction) — check before applying to a DB with hand-made variants.

## Existing tables (V1/V2)
- `products`: UUID id, `sku` unique (upper-case), name, description, `category` text NOT NULL, `brand` text,
  price NUMERIC(12,2) >= 0, currency (3 letters), stock >= 0, `attributes` JSONB, status ACTIVE/INACTIVE,
  version, timestamps. Trigram indexes on name/sku/brand/category.
- `product_embeddings` (FK products ON DELETE CASCADE), `vector_sync_events` (outbox, no FK),
  `app_users` (unique lower email), `chat_conversations`, `chat_messages`.

## Catalog tables (V3)
| Table | Key fields | Constraints / indexes |
|-------|-----------|------------------------|
| `categories` | name, slug, description, image_url, parent_id (self FK RESTRICT), status, version | `uq_categories_slug`, unique `lower(name)` (`uq_categories_name_lower`), check not own parent, slug format, status; idx parent, status, trgm name |
| `brands` | name, slug, description, logo_url, website_url, status, version | `uq_brands_slug`, `uq_brands_name_lower`; idx status, trgm name |
| `product_attributes` | (V4: category link moved to `product_attribute_categories`) name, slug, data_type STRING/NUMBER/BOOLEAN/SELECT, options JSONB array, unit, is_required, is_filterable, status, version | unique (category_id, slug), unique (category_id, lower(name)); check SELECT has options, others none |
| `product_variants` | product_id (FK products RESTRICT), sku, variant_name, attributes JSONB, price, sale_price, stock_quantity, status, version | `uq_product_variants_sku`; checks price >= 0, sale_price < price, stock >= 0; idx product, status, stock, price, trgm sku |
| `products` (altered) | + `category_id` (FK categories RESTRICT, nullable in DB), + `brand_id` (FK brands RESTRICT) | idx on both |

Backfill in V3: one category / brand per distinct existing `products.category` / `products.brand` value
(slug from name, md5 suffix on collision), then products are linked. Text columns are kept.

Unique-constraint names are mapped to API error codes in `GlobalExceptionHandler`
(`*_ALREADY_EXISTS`, `SKU_ALREADY_EXISTS`; FK violations -> `RECORD_IN_USE`).

## Seeds
| Script | Purpose | Status |
|--------|---------|--------|
| `scripts/seed-sample-products.mjs` + `sample-products.json` | 20 sample products via REST | creates missing categories/brands by name first |
| `scripts/seed-catalog.mjs` + `catalog-fixtures.json` | 30 categories, 30 brands, 30 attributes, 10 prerequisite products, 30 variants | run on scratch DB (twice) and `RagDB` (twice); verified |

Required fixture counts: 30 per module, 120 total (fixture records identified by slug/SKU; pre-existing
backfilled rows are not counted).

## Current `RagDB` contents (after V3 seeding, 2026-10-13; before V4)
- categories 40 (30 fixtures + 10 backfilled), brands 41 (29 created + Apple reused + 11 other backfilled),
  product_attributes 30, product_variants 30, products 31 (21 existing + 10 prerequisite).
- No embedding columns or vector rows for catalog tables; only products are embedded.
- Scratch DBs (local, not RagDB): `ragdb_catalog_check` (V3 fixtures), `ragdb_v4_check` (V4 migration check),
  `ragdb_it_test` (integration tests + browser checks). Drop only with the owner's go-ahead.
