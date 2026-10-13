# Changelog

Newest first. One entry per meaningful change; no conversation logs or source dumps.

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
