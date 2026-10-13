# Changelog

Newest first. One entry per meaningful change; no conversation logs or source dumps.

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
