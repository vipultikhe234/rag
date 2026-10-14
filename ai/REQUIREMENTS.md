# Agreed requirements

Only update when requirements change. Status of each item is in `ai/TASKS.md`.

## Existing (implemented earlier)
- Product CRUD with JSONB attributes, search, filters, pagination, sorting, activate/deactivate, delete.
- Vector sync via transactional outbox; RAG chat with streaming, history, sources panel.
- JWT auth; `ADMIN` writes, any authenticated user reads.

## Catalog modules (request of 2026-10-13)
Four admin modules, each with create, view, update, deactivate; backend validation; search, filters,
pagination, sorting; admin UI (list, create, edit, details, confirm dialogs, loading/empty/error states, toasts).

### Categories
- Parent/subcategory hierarchy; a category cannot be its own parent; no cycles.
- No hard delete while referenced by products (also blocked by subcategories / attribute definitions).
- Only active categories can be assigned to new products; inactive ones stay visible on existing products.

### Brands
- Unique name and slug; logo and website must be valid http(s) URLs.
- Product count per brand. No hard delete while referenced.
- Inactive brands cannot be newly assigned; existing associations stay.

### Product attributes
- Data types: string, number, boolean, select. Linked to a category (inherited by subcategories).
- Select needs valid, distinct options. Product values validated by type.
- Name unique within a category. Filters: category, type, status.
- Category attributes appear in the product form; filterable ones in product list filters.
- Deactivation keeps historical product values.

### Product variants
- Belongs to an existing product; unique SKU; price >= 0; sale price validated against price (< price);
  stock integer >= 0.
- Filters: product, SKU, status, price range, stock availability. Show low-stock and out-of-stock.
- Deactivation keeps references (no delete).

### Seed data
- Exactly 30 fixture records per module (120 total) plus prerequisite products for variants.
- Deterministic, rerun-safe (no duplicates), parents first, verify target DB first, verify counts in code.
- Never seed/reset a production or shared DB without approval.

## Product management system (request of 2026-10-13, second round)
Five admin modules under one "Product Management" nav group: Products (central), Categories, Brands,
Attributes, Variants. Research: `ai/RESEARCH.md`. Decisions confirmed by the user:
- Variant products: `products.price` / `stock_quantity` become a read-only summary recalculated by the backend
  in the same transaction (lowest active variant price, total active variant stock); simple products edit them.
- Variants use `price` + optional `compareAtPrice` (> price); `salePrice` is migrated (price = old sale price,
  compareAtPrice = old price).
- Attributes are global definitions assigned to one or more categories (many-to-many, inherited by
  subcategories); slug and name globally unique.
- Product options (max 3) with values; a variant is one combination (unique per product). Existing variants
  are backfilled from their attribute JSON.
- Images are http(s) URLs (no upload service); a variant may pick one of its product's images.
- Migration and seed may be applied to `RagDB` after verification on a scratch DB.

Further rules: product slug (unique), category + optional subcategory selection, inactive parent category
blocks new assignments through its descendants, product create with options and initial variants in one
transaction, Product Details page (Overview, Specifications, Images, Variants, Inventory), product filters
(price range, stock availability, has variants), variant filters (category, brand, option values), barcode
and image per variant, max 100 variants per product. No SEO fields (no storefront); no multi-warehouse
inventory; no orders module exists.

### Other
- Tests for each module's rules plus seed verification; run the existing suite.
- Swagger/OpenAPI only if it can be added without disruption.
- Keep products compatible with semantic search/RAG: `products.category` / `products.brand` text columns stay
  and are kept in sync with the linked records.
- ~~Do not create embeddings for the four admin modules.~~ Superseded on 2026-10-14 (see "Embeddings for all
  catalog modules" below).

## Semantic search and embedding sync (`Requirment.txt`, 2026-10-14)
- One embedding per product from a deterministic document: name, SKU, category path, brand, description,
  attribute names/values/units, option values of active variants. No price/stock in the text.
- No brand, category, attribute or variant embeddings unless a semantic discovery use case is approved
  (approved later the same day, see below).
- Re-embed only when the document changes: product create/update, brand rename, category rename/move (whole
  subtree), attribute name/slug/type/unit change, variant create / option change / status change. Never for
  price- or stock-only edits. Deactivated products are excluded at query time; reactivation re-verifies the hash.
- Reliable async processing with the existing outbox, retries with backoff, failed-job inspection, stale-job
  protection (`source_version`).
- Semantic search API with exact SKU, vector and keyword candidates, verified by structured filters (category
  subtree, brand, price, stock, variant options); authoritative prices and stock from the database; no LLM.
- Admin repair: index missing, retry failed, refresh stale (hash/model compare), batched, resumable, no
  delete-before-replace, nothing at startup.
- Chat must answer catalog questions exactly ("how many brands" = all 41, not the brands of retrieved products).

## Embeddings for all catalog modules (request of 2026-10-14, "create embedding for all module")
Decisions confirmed by the user:
- One shared table `catalog_embeddings` (entity_type + entity_id) for brands, categories, attributes, variants.
- Use cases: a semantic search API per module, and chat discovery questions ("which brands make gaming
  laptops?", "which category fits running gear?").
- Rich documents: a brand lists the categories and names of its active products; a category lists its path,
  subcategories, brands and products in its subtree; re-embedded when that summary changes.
- Apply V5 to `RagDB` and index all modules after verification on the scratch DB.
Same rules as products: outbox + retries, no provider call inside a DB transaction, no price/stock in documents,
only ACTIVE entities in results, live data from PostgreSQL, no LLM for the discovery answer.
