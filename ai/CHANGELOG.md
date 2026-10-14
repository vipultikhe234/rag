# Changelog

Newest first. One entry per meaningful change; no conversation logs or source dumps.

## 2026-10-14 — Product semantic search: SKU detection and EXACT_SKU similarity
- `SemanticProductSearchService`: a query is looked up as an exact SKU only if it uses the SKU charset and contains
  a digit or `.`/`_`/`-` (plain words like "phone" no longer query SKUs; `GS24` still does; `/` dropped as SKUs
  can't contain it). `EXACT_SKU` hits now return `similarity: null`, as documented for non-vector matches.

## 2026-10-14 — Embeddings for all catalog modules
- DB: `V5__catalog_embeddings.sql` — shared `catalog_embeddings` table (BRAND/CATEGORY/ATTRIBUTE/VARIANT),
  outbox `ENTITY_*` event types and an `aggregate_type` check. Applied to `ragdb_it_test` and `RagDB` (4 -> 5).
- Rich documents (`CatalogDocumentLoader`), worker dispatch by aggregate type (`CatalogEmbeddingSynchronizer`),
  triggers in the brand/category/attribute/variant services, product events fan out to brand, category chain and
  variants; previous brand/category re-queued when a product moves.
- New `GET /{brands|categories|product-attributes|product-variants}/semantic-search`; chat strategy `DISCOVERY`
  ("which brands make gaming laptops?") answered from live rows without the LLM.
- Admin: per-module index counts, `resync-missing` / `refresh-stale` cover all modules (backward-compatible
  top-level product counts), `aggregateType` on events; vector sync panel shows module counts.
- `RagDB` indexed: 38 brands, 36 categories, 28 attributes, 28 variants. The earlier rule "no embeddings for the
  catalog admin modules" was lifted by the user (`ai/REQUIREMENTS.md`, `ai/CODING_RULES.md`).

## 2026-10-14 — Semantic search and embedding sync (`Requirment.txt`)
- Embedding document now includes the category path, attribute display names/units and active variant option
  values (`ProductSearchContextLoader`); price/stock still excluded.
- Re-embedding triggers: category rename/move queues the whole subtree; attribute name/slug/type/unit change
  queues products using it; variant create, option change and status change queue the parent product.
- New `GET /api/v1/products/semantic-search` (package `search`): exact SKU, vector and keyword candidates,
  verified by SQL (category subtree, brand, price, stock, variant options), live prices, keyword-only fallback.
- New `POST /api/v1/admin/vector-sync/refresh-stale` (`StaleEmbeddingScanner`) + "Refresh outdated embeddings"
  button in the vector sync panel.
- Chat: `CATALOG` retrieval strategy (`CatalogOverview`) answers brand/category/attribute/variant totals and lists
  from the tables — "how many brands" now returns 41 instead of the 2 brands of the retrieved products; a single
  named brand becomes an exact filter.
- No migration. Docs: README, `docs/vector-sync.md`, `docs/rag-flow.md`, `docs/api-examples.md`,
  `ai/RESEARCH.md`, new `ai/context/{product-embeddings,embedding-sync,semantic-search}.md`.

## 2026-10-14 — Product management system (options, variants, images, V4)
- DB: `V4__product_options_and_media.sql` — product slug / has_variants, images, options, option values,
  variant option values (composite FKs, unique combination signature), compare-at price (from `sale_price`),
  barcode, variant image, attribute-category join (global attribute slug/name), backfill. Verified on scratch DBs,
  then applied to `RagDB` (V3 -> V4); migrated data checked via the API and seed rerun there: 0 created,
  130 reused, 30/30/30/10/30.
- Backend: product create with options + variants in one transaction; variant product price/stock summary;
  `GET/POST /products/{id}/variants`; product filters (price range incl. variant prices, stock, hasVariants);
  variant filters (category incl. subcategories, brand, `option=Name:Value`); categories expose `assignable` and
  block assignment under an inactive ancestor; attributes many-to-many with `required` filter; attribute slug
  clash now returns `SLUG_ALREADY_EXISTS` (name clash stays `ATTRIBUTE_ALREADY_EXISTS`); option without a linked
  attribute no longer throws an NPE.
- Frontend: "Product Management" nav group; product form in 7 sections (basic, category/subcategory, specs,
  pricing, options + generated variants with preview, images, review); Product Details tabs (Overview,
  Specifications, Images, Variants, Inventory); list filters and actions; attribute multi-category form;
  variant pages with options, compare-at, barcode, image and new filters; field-level server errors.
- Seed: fixtures and `seed-catalog.mjs` create each prerequisite product with its options and variants in one
  request; rerun on scratch `ragdb_it_test`: 0 created, 130 reused, verification 30/30/30/10/30.
- Tests: `mvnw test` 240 run, 0 failures, 23 skipped; new `ProductManagementIntegrationTest` (10 tests);
  lint + build pass; browser check of the new flows passed.

## 2026-10-13 — No Docker, AI connection errors, admin layout, UI check
- Removed `docker-compose.yml` and the Testcontainers dependencies; default `DB_URL` uses port 5432;
  `ProductRagIntegrationTest` is opt-in via `IT_DB_URL` (localhost `*_test` DB only) and raises the login rate
  limit for its own run. README, `.env.example`, `.cursor/rules` and `ai/` updated.
- Backend: connection refused / unknown host anywhere in the cause chain → `503 AI_PROVIDER_UNREACHABLE`
  (transient, so vector sync retries) with a hint to start Ollama; frontend message added.
- Frontend layout: full-height shell where only `<main>` scrolls (sidebar no longer moves with the page), scroll
  resets on navigation, `scrollbar-none` utility hides scrollbars on main, sidebar nav and chat panes.
- Frontend fix: product-list attribute filters list the category's own attributes first and no longer drop
  them past 10; more than 10 active filters are refused with a toast (API limit).
- Tests: `mvnw test` 203 run, 0 failures, 13 skipped; integration test 13/13 on `ragdb_it_test`; lint + build
  pass; browser click-through of the catalog UI passed.

## 2026-10-13 — Catalog modules: tests, seed, admin UI, docs
- Backend: catalog controllers accept `PATCH /{id}` as an alias of `PUT /{id}` (full body + `version`).
- Tests: fixed existing product tests; added category, brand, attribute, validator, variant, catalog-links,
  catalog security and fixture tests; `ProductRagIntegrationTest` now resets catalog tables and creates the
  category/brand it uses. `mvnw test`: 202 run, 0 failures, 13 skipped.
- Seed: `scripts/catalog-fixtures.json` + `scripts/seed-catalog.mjs` (REST, localhost-only, idempotent,
  `--yes`, `--verify-only`, final verification); `seed-sample-products.mjs` creates missing categories/brands.
- DB: V3 applied to scratch `ragdb_catalog_check` (seeded twice, no duplicates) and to `RagDB` (seeded: 129
  created, 1 reused; rerun reused all 130).
- Frontend: catalog list/details/create/edit pages for 4 modules (lazy chunk), "Catalog" nav group, shared
  catalog UI/hooks, product form with category/brand selects and typed attribute fields, product list
  category/brand/attribute filters. Lint + build pass; not browser-tested.
- Docs: README (features, seed, API table, V3, tests), `docs/api-examples.md` catalog section.

## 2026-10-13 — AI context folder
- Added `ai/` (project, requirements, architecture, database, API, rules, testing, tasks, changelog, module
  context) and `.cursor/rules/*.mdc` that point the agent to it.
- No code, DB or API changes. Tests: none needed.

## 2026-10-13 — Catalog modules (in progress)
- Backend: new `catalog` package (category, brand, attribute, variant), `FieldValidationException`,
  `PageRequests`, `HttpUrl` validator; `ProductService`/`ProductController`/`ProductRequest`/`ProductResponse`
  integrated with categories, brands, attribute validation, variant delete guard; `SecurityConfig` catalog paths;
  `GlobalExceptionHandler` maps new unique/FK constraints; `app.products.low-stock-threshold` (default 5).
- DB: `V3__catalog_modules.sql` (4 tables, product FKs, backfill). Not applied yet.
- API: `/categories`, `/brands`, `/product-attributes`, `/product-variants`; product list/request additions.
- Tests: `mvnw -q compile` passes; test sources need updating (see `ai/TESTING.md`).
- Remaining: tests, seed script, frontend, verification, docs.

## 2026-10-13 — RAG accuracy and chat fixes
- `ProductRetriever.closeToBest` + `app.rag.relative-score-margin` (0.1); system prompt rule for product types
  not in the catalog; chat `STREAM_ENDPOINT_MISSING` handling; `PageErrorBoundary` on `/chat`; README rewrite.
- Tests: `mvnw test` 129 run, 0 failures, 13 skipped; frontend lint + tsc clean.

## Earlier (2026-10-13)
- Initial full-stack app: products, vector sync outbox, RAG chat with streaming (Ollama), JWT auth, V1/V2.
