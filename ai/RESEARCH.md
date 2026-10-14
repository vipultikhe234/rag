# Catalog research (2026-10-13)

Official documentation fetched live with the web fetch tool. Only the pages listed were read; nothing here is
assumed from memory beyond what these pages say.

## Sources
| # | Source | URL |
|---|--------|-----|
| S1 | Shopify dev: About products and collections | https://shopify.dev/docs/apps/build/product-merchandising/products-and-collections |
| S2 | Shopify Help: Variants | https://help.shopify.com/en/manual/products/variants |
| S3 | Shopify Help: Adding variants | https://help.shopify.com/en/manual/products/variants/add-variants |
| S4 | Shopify Help: Product details | https://help.shopify.com/en/manual/products/details/product-status |
| S5 | Shopify Help: Sale pricing (compare-at price) | https://help.shopify.com/en/manual/products/details/product-pricing/sale-pricing |
| S6 | Shopify Help: Standard Product Taxonomy / category metafields | https://help.shopify.com/en/manual/products/details/product-category |
| S7 | Shopify Admin GraphQL: ProductVariant | https://shopify.dev/docs/api/admin-graphql/latest/objects/ProductVariant |
| M1 | Medusa: Product Module | https://docs.medusajs.com/resources/commerce-modules/product |
| M2 | Medusa: Product variant inventory | https://docs.medusajs.com/resources/commerce-modules/product/variant-inventory |
| M3 | Medusa: Product module links (pricing, inventory, orders) | https://docs.medusajs.com/resources/commerce-modules/product/links-to-other-modules |
| M4 | Medusa: ProductVariant / ProductCategory data models | https://docs.medusajs.com/resources/references/product/models/ProductVariant , .../ProductCategory |

## Findings
1. **Product / option / variant / attribute** (S1, S2, M1). Three tiers: the *product* holds shared
   information (title, description, vendor/brand, media); *options* are what a buyer chooses (name + values,
   e.g. Color: Red, Green); a *variant* is one purchasable combination of option values with its own SKU,
   price, inventory and barcode. *Attributes* (Shopify "category metafields", S6) are descriptive
   specifications tied to the product category; they are not purchasable choices, but an option can be
   connected to a category attribute so its values are reused (S3, S6).
2. **Product vs variant fields** (S1, S3, S4, S7, M4). Product: name, description, category, brand/vendor,
   media, status, handle/slug. Variant: SKU, barcode, price, compare-at price, inventory, selected options,
   optional image chosen from the product's media (S3), rank/position (M4).
3. **Categories, brands, specs** (S6, M4). One category per product; categories form a tree (Medusa
   `parent_category`, `mpath`, `is_active`, `is_internal`, `handle`). The category decides which attributes
   (specs) apply; when the category changes, values connected to options are kept and others are re-evaluated (S6).
4. **Category-specific attributes** (S6). Attributes are reusable definitions attached to categories; the
   same attribute (e.g. color, size) is shared by many categories rather than duplicated.
5. **Variant lifecycle** (S3). Options and values generate combinations; admins pick which combinations to
   create. A manually added variant must differ in at least one option value; exact duplicates are rejected.
   Limits: 3 options per product, 2,048 variants (S3); bulk create up to 2,048 at a time (S1). Variant search
   by SKU, barcode, product, option values, inventory quantity, product status (S7).
6. **Price and stock with/without variants** (S4, M2, M3). Without variants, price/inventory are edited on
   the product page; once variants exist those sections move to each variant (S4). Medusa keeps prices
   (price sets) and inventory (inventory items/levels per location) per variant, never on the product (M2, M3).
   Sale price = `price` lowered, original kept in `compareAtPrice`, which must be higher than `price` (S5).
7. **Admin filters** (S7, S3). Product and variant lists filter by status, category, vendor/brand, SKU,
   option values, inventory; search, filter and group variants inside the product page (S2).
8. **Search/sort/paging** (S1). Server-side pagination with a maximum page size; sort keys explicit.
9. **Inactive records** (S2, S7, M4). Shopify product status `active/draft/archived/unlisted`, variants can be
   unpublished; Medusa categories `is_active`. Inactive data stays referenced (orders link read-only to
   product/variant, M3) - deactivate, do not delete.
10. **Integrity and transactions** (M1, M3). Medusa wraps product + options + variants creation in one
    workflow with rollback (M1); orders reference variants read-only (M3), so variants must not be hard-deleted.

## Design decisions for this project
| Decision | Why it fits here |
|----------|------------------|
| Keep PostgreSQL relational tables; add `product_options`, `product_option_values`, `variant_option_values` | Matches the three-tier model (finding 1) and gives FK + unique guarantees; existing stack is JPA + Flyway. |
| Variant combination uniqueness via `(product_id, option_signature)` unique index | DB-enforced "no duplicate combination" (finding 5) without triggers. |
| Max 3 options per product, max 100 variants per product in the admin | Shopify allows 3 options (finding 5); this admin has no bulk tooling, so a smaller variant cap keeps the UI usable. |
| Simple products keep product-level price/stock; variant products take price/stock from variants | Mirrors Shopify product page behaviour (finding 6). |
| Compare-at price (> price) instead of sale price (< price) | Industry convention (finding 6, S5); same information, clearer semantics. |
| Options may link to a category attribute (SELECT values reused) | Avoids duplicate definitions (findings 1, 4). |
| One inventory quantity per variant, no locations | No warehouses or orders module exist; Medusa's multi-location model (M2) would be unused complexity. |
| Deactivate instead of delete for every catalog record | Finding 9/10; keeps future order references valid. |
| Product + options + initial variants saved in one DB transaction | Finding 10 (Medusa workflow with rollback). |
| No file upload service: images are http(s) URLs | No upload/storage exists in the project; adding storage is a separate infrastructure decision. |
| No SEO fields | No storefront exists in this project; the slug covers URLs. |

## Not researched
- Multi-currency price lists, tax, shipping, sales channels: out of scope for this admin.

---

# Semantic search and embedding sync research (2026-10-14)

Fetched live for the `Requirment.txt` semantic-search task. Verified stack (code, not assumptions): PostgreSQL 16 +
pgvector (`product_embeddings.embedding vector(N)`, cosine `<=>`), Spring AI 1.1 `EmbeddingModel` behind
`ai/EmbeddingClient`, Ollama `nomic-embed-text` (768) locally or an OpenAI-compatible API.

## Sources
| # | Source | URL |
|---|--------|-----|
| P1 | pgvector README (HNSW, filtering, iterative scans) | https://github.com/pgvector/pgvector (raw `README.md`) |
| N1 | Ollama library: nomic-embed-text | https://ollama.com/library/nomic-embed-text |
| N2 | Hugging Face model card: nomic-embed-text-v1.5 | https://huggingface.co/nomic-ai/nomic-embed-text-v1.5 |

## Findings
1. **Distance and index** (P1). Cosine distance `<=>`; an HNSW index uses `vector_cosine_ops`. Without an index
   pgvector does an exact scan (perfect recall). The project has no ANN index (31 products); `docs/vector-sync.md`
   has the migration to add one.
2. **Filtering with approximate indexes** (P1). Filters are applied *after* the HNSW scan; with the default
   `hnsw.ef_search = 40` a query returns at most ~40 candidates, fewer when filters are selective. pgvector 0.8+
   offers `hnsw.iterative_scan`. Consequence here: semantic search verifies filters on a bounded candidate list
   (100) in a second SQL step; once an HNSW index exists, raise `ef_search` / enable iterative scans in the same
   transaction, or candidates will be capped at ~40.
3. **Model limits** (N1, N2). nomic-embed-text v1.5: 768 dimensions; Ollama serves it with a 2K-token context
   window (the model supports 8192 natively). The search document is capped (description 6000 chars, 300 chars
   per attribute value, 20 values per option) to stay near that window.
4. **Task prefixes** (N2). The model card says documents should be embedded as `search_document: …` and queries
   as `search_query: …`. The project embeds both without prefixes (works, but not the documented usage). Not
   changed in this round: it changes every vector, the similarity distribution (threshold 0.25 / margin 0.1 were
   tuned without prefixes) and is model-specific (OpenAI models must not get it). See `ai/TASKS.md`.

## Design decisions
| Decision | Why |
|----------|-----|
| One embedding per product; no brand, category, attribute or variant vectors | No user-facing semantic brand/category discovery use case; exact brand/category/option/stock questions are answered with SQL (requirement sections 3B-3E, 9). |
| Product document = name, SKU, category path, brand, description, attribute labels + units, active variant option values | Requirement 3A; relationships make product text richer without extra vectors. Prices/stock excluded (change often, read live). |
| Related-entity changes queue bulk `PRODUCT_UPDATED` events; the worker's hash check decides whether to embed | Reuses the existing outbox (requirement 7: no second queue); no embedding call when the text did not change. |
| Catalog-level chat questions ("how many brands") answered from tables | Vector top-k cannot count or list a whole table; this caused the "2 brands instead of 41" answer. |
| Semantic search API = exact SKU + vector + keyword candidates, then SQL verification | Requirement 8: embedding similarity never decides brand, category, price, option or stock constraints. |
| Refresh-stale compares hashes in keyset batches and only queues events | Requirement 10: resumable, idempotent, no delete-before-replace, no work at startup. |
