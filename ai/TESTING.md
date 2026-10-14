# Testing

## Commands
```powershell
cd backend; .\mvnw.cmd test                         # all backend tests
cd backend; .\mvnw.cmd test -Dtest=ProductServiceTest # one class
cd frontend; npm run lint; npm run build            # ESLint + tsc + Vite build (no frontend unit tests exist)
node scripts/seed-catalog.mjs --verify-only         # check catalog fixtures in the running backend's DB
```
On Windows set `JAVA_HOME` (JDK 21) if missing.

## Locations
- Unit (Mockito): `backend/src/test/java/com/productrag/**/**Test.java`. Catalog: `catalog/category/
  CategoryServiceTest`, `catalog/brand/BrandServiceTest`, `catalog/attribute/{ProductAttributeServiceTest,
  AttributeValueValidatorTest}`, `catalog/variant/ProductVariantServiceTest`, `product/ProductCatalogLinksTest`.
  Entity builders: `*TestData` classes next to the tests (same package as the entities).
- Web/security slice: `@WebMvcTest` + `@Import(SecurityConfig, JwtService, RestSecurityHandlers, ApiErrorWriter)`
  with `TestProperties.JWT_SECRET` (`product/ProductControllerSecurityTest`, `catalog/CatalogControllerSecurityTest`).
- Validation: `product/ProductRequestValidationTest` (plain `Validator`).
- Fixtures: `catalog/CatalogFixturesTest` reads `scripts/catalog-fixtures.json` and checks it against the request
  DTOs, slug rules, hierarchy order and a real `AttributeValueValidator` (no DB).
- Integration: `ProductManagementIntegrationTest` (same opt-in and DB rules) covers create-with-variants in one
  request, rollback when one variant fails, duplicate combinations (API and DB constraint), summary price/stock,
  in-use option values, server-side product/variant filters, inactive-parent assignability, multi-category
  attributes, compare-at rule, options without a linked attribute, catalog embeddings per module + module
  searches, brand/category summaries following product and brand changes (refresh-stale, resync-missing), chat
  `DISCOVERY`.
- Integration: `ProductRagIntegrationTest` — opt-in, runs only when `IT_DB_URL` points to a localhost database
  whose name ends in `_test` (`@EnabledIfEnvironmentVariable`); credentials from `IT_DB_USERNAME`/`IT_DB_PASSWORD`.
  No Docker. Resets catalog/product tables in that DB and creates the `Electronics` category / `ExampleBrand`
  brand it uses. Login rate limit raised in its properties (every test signs in). Fake AI: `support/FakeAiModels`.
- Shared config builder: `support/TestProperties.defaults()`.

## Test database
- Tests never use `RagDB`. The integration test uses a dedicated local DB (`ragdb_it_test`, created for it; the
  test wipes it on every run). `APP_ENV_FILE` is redirected in `pom.xml` so the real `.env` is not read.
  No paid AI calls (fakes only).
  ```powershell
  $env:IT_DB_URL = 'jdbc:postgresql://localhost:5432/ragdb_it_test'
  $env:IT_DB_USERNAME = '<user>'; $env:IT_DB_PASSWORD = '<password>'   # never commit these
  cd backend; .\mvnw.cmd test -Dtest=ProductRagIntegrationTest
  ```
- Manual verification: backend jar on port 8081 with `DB_URL` pointing at a scratch DB (`ragdb_catalog_check`,
  `ragdb_v4_check` or `ragdb_it_test`), `SERVER_PORT=8081`, `VECTOR_SYNC_ENABLED=false`; on `ragdb_it_test` also
  `EMBEDDING_DIMENSIONS=1536` (the integration test creates the column with that size). Seed it with
  `API_BASE_URL=http://localhost:8081/api/v1` and the test admin. Drop scratch DBs only with the user's go-ahead.

## Seeds
- `node scripts/seed-sample-products.mjs` (backend running; creates missing categories/brands, then products).
- `node scripts/seed-catalog.mjs [--yes] [--verify-only]` — localhost only, idempotent, verifies at the end.

## Last executed results
| Date | Command | Result |
|------|---------|--------|
| 2026-10-14 | SKU detection / EXACT_SKU similarity fix: `mvnw -o test` (no `IT_DB_URL`) | 294 run, 0 failures, 0 errors, 29 skipped (integration) |
| 2026-10-14 | catalog embeddings round: `mvnw -o test` (no `IT_DB_URL`), final code | 293 run, 0 failures, 0 errors, 29 skipped (integration) |
| 2026-10-14 | catalog embeddings round: both integration classes on `ragdb_it_test` (`EMBEDDING_DIMENSIONS=1536`, V5 applied by Flyway) | 29/29 passed (16 + 13) |
| 2026-10-14 | catalog embeddings round: `npm run lint`, `npm run build` | clean |
| 2026-10-14 | V5 applied to `RagDB` (backend restart on 8080); `resync-missing` + drain | Flyway 4 -> 5; `catalogEnqueued` 130; 38/36/28/28 embedded, 0 failed; refresh-stale catalog 130 checked, 0 stale |
| 2026-10-14 | live on `RagDB`: 6 module searches + 4 chat questions | 200; e.g. brands "gaming laptops" -> Lenovo, ASUS, Dell; chat discovery questions -> `DISCOVERY`, "how many brands" still `CATALOG` |
| 2026-10-14 | semantic search round: `mvnw -o test` (no `IT_DB_URL`), final code | 263 run, 0 failures, 0 errors, 26 skipped (integration) |
| 2026-10-14 | semantic search round: both integration classes on `ragdb_it_test` (`EMBEDDING_DIMENSIONS=1536`) | 26/26 passed (13 + 13) |
| 2026-10-14 | semantic search round: `npm run lint`, `npm run build` | clean |
| 2026-10-14 | live on `RagDB` (8080): chat "how many brands" / "list all categories" | `CATALOG`, 41 brands (38 active, 3 inactive; `/brands` totalElements 41) / 40 categories |
| 2026-10-14 | live on `RagDB`: `POST /admin/vector-sync/refresh-stale` | checked 31, stale 0, enqueued 0 |
| 2026-10-14 | live on `RagDB`: 5 semantic-search queries (running shoes, earbuds, laptop + maxPrice + inStock, tshirt + `option=Color:Black`) | 200, expected products and matching variants |
| 2026-10-14 | `mvnw test` (no `IT_DB_URL`) | 240 run, 0 failures, 0 errors, 23 skipped (both integration classes) |
| 2026-10-14 | both integration classes on `ragdb_it_test` (before the attribute slug-code change) | 23/23 passed |
| 2026-10-14 | `npm run lint`, `npm run build` | clean; main chunk 491 kB (no Vite warning) |
| 2026-10-14 | browser check on scratch backend (8081) + Vite (5174), `ragdb_it_test` | product-management flows passed (see below) |
| 2026-10-14 | seed-catalog rerun on scratch backend (`ragdb_it_test`, V4) | 0 created, 130 reused, 0 failed; 30/30/30/10/30 |
| 2026-10-14 | V4 applied to `RagDB` (backend start on 8080) | Flyway 3 -> 4 succeeded |
| 2026-10-14 | seed-catalog `--verify-only` and `--yes` on `RagDB` | 30/30/30/10/30; 0 created, 130 reused, 0 failed |
| 2026-10-14 | read-only API check of migrated `RagDB` data | 30/30 variants with options, 0 leftover attribute keys, 11 compare-at > price, 0 summary mismatches, 0 products without slug |
| 2026-10-13 | `mvnw test` (no `IT_DB_URL`) | 203 run, 0 failures, 0 errors, 13 skipped (integration test) |
| 2026-10-13 | `mvnw test -Dtest=ProductRagIntegrationTest` on `ragdb_it_test` | 13/13 passed |
| 2026-10-13 | `npm run lint`, `npm run build` | clean (Vite advisory: main chunk 503 kB) |
| 2026-10-13 | browser click-through on scratch backend (8081) + Vite (5174) | all catalog flows passed (see below) |
| 2026-10-13 | seed-catalog on `ragdb_catalog_check`, run 1 / run 2 | 130 created / 130 reused, verification passed |
| 2026-10-13 | live API checks on scratch backend (23 scenarios) | 23/23 passed |
| 2026-10-13 | seed-catalog on `RagDB`, run 1 / run 2 | 129 created + 1 reused / 130 reused, verification passed |

Live API scenarios covered: JSONB `attr` filter, descendant category filter, variant stock and price filters,
search/paging/sort, inherited applicable attributes, in-use deletes (409), inactive category/brand assignment
rejected, typed attribute errors, PATCH update with version, stale version 409, deactivate/reactivate,
`salePrice >= price` rejected.

Product-management browser flows (test admin on `ragdb_it_test`, 2026-10-14): "Product Management" nav group;
product list filters (price, stock, has variants) and row actions; Product Details tabs; Add Product with
variants (combination generation, duplicate values ignored, SELECT casing canonicalised, preview, redirect to
details); compare-at must exceed price and "On sale" badge on the variant form; variant list option/category
filters; attribute `required` filter; attribute assigned to two categories; duplicate attribute name shown under
the Name field.

Browser flows covered (throwaway admin on `ragdb_catalog_check`): category create (derived slug, required
errors, duplicate 409 under Name), edit (dirty-gated save), in-use delete disabled, deactivate; SELECT attribute
with options; product form with inherited typed spec fields, typed errors, create + embedding "Synced" via
Ollama; inactive category shown as "(inactive)"; variant sale-price rule + "Low stock" badge; brand/variant/
attribute list filters; product-list attribute filters (category's own first, max 10); mobile menu overlay;
sidebar fixed while only `<main>` scrolls, scrollbars hidden; chat page fits the viewport.

## Not covered
- Asking a chat question in the browser (embedding path via Ollama was verified instead).
- Frontend unit tests (none exist).
- Order-history preservation (no orders module exists).
