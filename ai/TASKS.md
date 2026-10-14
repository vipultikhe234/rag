# Tasks

Legend: `[x]` done and verified, `[~]` code written but not verified, `[ ]` not started.
Last updated: 2026-10-14.

## Embeddings for all catalog modules (fourth round, 2026-10-14)
- [x] V5 `catalog_embeddings` + outbox aggregate/event types; schema verifier checks both vector columns —
  applied to `ragdb_it_test` and `RagDB` (4 -> 5, `vector(768)`)
- [x] Documents (`CatalogDocumentLoader`): rich brand/category summaries, attribute, variant
- [x] Worker: `CatalogEmbeddingSynchronizer`, processor dispatch on `aggregate_type` — unit + integration tested
- [x] Triggers: product fan-out (brand, category chain, variants), previous brand/category on move, brand /
  category / attribute / variant changes — integration tested
- [x] Admin: per-module status, resync-missing and refresh-stale for all modules, `aggregateType` on events, UI panel
- [x] `GET /{brands|categories|product-attributes|product-variants}/semantic-search` — unit, WebMvc, integration,
  live on `RagDB`
- [x] Chat `DISCOVERY` strategy — unit, integration, live on `RagDB`
- [x] Indexed `RagDB`: 38 brands, 36 categories, 28 attributes, 28 variants (all ACTIVE), 0 failed, 0 stale
- [ ] Semantic search UI for the modules (API only for now)
- [ ] Inactive entities are embedded only when they change (resync-missing covers ACTIVE ones)

## Semantic search and embedding sync (`Requirment.txt`, third round)
- [x] Research (pgvector README, Ollama / Hugging Face nomic-embed-text) -> `ai/RESEARCH.md`
- [x] Product search document: category path, attribute labels/units, active variant option values
- [x] Sync triggers: category rename/move (subtree), attribute name/slug/type/unit, variant create/option/status;
  none for price/stock-only edits — unit + integration tested
- [x] `GET /products/semantic-search` (SKU + vector + keyword candidates, SQL-verified filters, live price/stock,
  keyword fallback) — unit, WebMvc, integration, live on `RagDB`
- [x] `POST /admin/vector-sync/refresh-stale` + UI button — integration tested; live on `RagDB` (31 checked, 0 stale)
- [x] Chat: "how many brands" answered from the table (`CATALOG` strategy): 41 brands live, matches `/brands`
- [x] Brand named in a question becomes an exact filter
- [x] Bug reports in `Requirment.txt` (ProductOptionService message, CatalogFixturesTest UUIDs, App.tsx route
  order) reviewed: not bugs, no change
- [ ] nomic `search_document:` / `search_query:` prefixes (needs A/B check + full re-embed)
- [ ] HNSW index with `hnsw.ef_search` / iterative scan once the catalog is large
- [ ] Free-text constraints ("under 50000", "in stock") in the semantic-search API (chat already parses them)
- [ ] Semantic search UI page (API only for now)

## Product management system (second round)
- [x] Research (Shopify, Medusa official docs) -> `ai/RESEARCH.md`
- [x] Inspection + design decisions confirmed by the user (`ai/REQUIREMENTS.md`, `ai/ARCHITECTURE.md`)
- [x] V4 migration: attribute-category join, options, option values, variant options, images, product slug /
  has_variants, compare-at price, barcode, backfill — verified on scratch DBs
- [x] Categories: inactive ancestor blocks assignment; `assignable` in options; reactivation tests
- [x] Attributes: many-to-many categories, global slug/name, `required` filter, applicable via join
- [x] Products: slug, images, hasVariants, options, create with variants (one transaction), summary price/stock,
  filters (price range, stock, hasVariants), `GET/POST /products/{id}/variants`
- [x] Variants: options/combination uniqueness, compare-at price, barcode, image, filters (category, brand,
  option values), product row lock + summary refresh
- [x] Admin UI: nav "Product Management", product form sections, Product Details tabs, list filters, variant and
  attribute pages updated — browser-checked on scratch
- [x] Seeds: fixtures updated for options/compare-at; 30/30/30/10/30 verified on scratch (`ragdb_it_test`)
- [x] Apply V4 to `RagDB` (approved): migrated V3 -> V4, migrated variants checked, `seed-catalog.mjs` rerun
  (0 created, 130 reused) and counts verified
- [x] Tests: unit + WebMvc + integration (`ragdb_it_test`) + lint/build + browser check
- [x] Docs: ai/ context files (product-management, product-brands rename), API_CONTRACTS, DATABASE, README

## Catalog modules (first round) — done
- [x] Category, Brand, Attribute, Variant modules (backend, UI, tests), V3 applied to scratch DB and `RagDB`
- [x] Seed 30 categories, 30 brands, 30 attributes, 30 variants (+10 prerequisite products), verified twice
- [x] Admin UI browser-verified; layout fixes; Docker removed; `AI_PROVIDER_UNREACHABLE`
- [x] Automated tests (first round) — 203 run, 0 failures, 13 skipped; integration 13/13 on `ragdb_it_test`
- [ ] Optional: Swagger/OpenAPI — not added (springdoc not in `pom.xml`)

## Blockers / notes
- No orders module exists, so "history preserved for orders" is covered by deactivate-not-delete.
- `RagDB` also holds 10 categories / 11 brands backfilled by V3 from product text; not fixtures, left untouched.
- Scratch DBs `ragdb_catalog_check`, `ragdb_v4_check` and `ragdb_it_test` exist (drop only with the user's go-ahead).
- Migrated variants of PUMA-PHASE-BP / DECA-BTL use options not linked to an attribute, so their titles show
  "22 / Grey" without a unit (link the option to a NUMBER attribute to get "22 L").
