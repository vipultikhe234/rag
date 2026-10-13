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
- Integration: `ProductRagIntegrationTest` (Testcontainers pgvector, `@Testcontainers(disabledWithoutDocker = true)`;
  skipped on this machine because Docker is not installed). Resets catalog tables in its own container and
  creates the `Electronics` category / `ExampleBrand` brand it uses. Fake AI: `support/FakeAiModels`.
- Shared config builder: `support/TestProperties.defaults()`.

## Test database
- Tests never use `RagDB`. Integration tests use a throwaway Testcontainers DB; `APP_ENV_FILE` is redirected in
  `pom.xml` so the real `.env` is not read. No paid AI calls (fakes only).
- Manual catalog verification: scratch DB `ragdb_catalog_check` on local PostgreSQL + backend jar on port 8081
  (`DB_URL`/`SERVER_PORT` env overrides). Still exists; drop only with the user's go-ahead.

## Seeds
- `node scripts/seed-sample-products.mjs` (backend running; creates missing categories/brands, then products).
- `node scripts/seed-catalog.mjs [--yes] [--verify-only]` — localhost only, idempotent, verifies at the end.

## Last executed results
| Date | Command | Result |
|------|---------|--------|
| 2026-10-13 | `mvnw test` | 202 run, 0 failures, 0 errors, 13 skipped (no Docker) |
| 2026-10-13 | `npm run lint`, `npm run build` | clean (Vite advisory: main chunk 502 kB) |
| 2026-10-13 | seed-catalog on `ragdb_catalog_check`, run 1 / run 2 | 130 created / 130 reused, verification passed |
| 2026-10-13 | live API checks on scratch backend (23 scenarios) | 23/23 passed |
| 2026-10-13 | seed-catalog on `RagDB`, run 1 / run 2 | 129 created + 1 reused / 130 reused, verification passed |

Live API scenarios covered: JSONB `attr` filter, descendant category filter, variant stock and price filters,
search/paging/sort, inherited applicable attributes, in-use deletes (409), inactive category/brand assignment
rejected, typed attribute errors, PATCH update with version, stale version 409, deactivate/reactivate,
`salePrice >= price` rejected.

## Not covered
- Browser/UI behaviour of the new pages (no frontend unit tests; not clicked through).
- `ProductRagIntegrationTest` with the catalog changes (needs Docker).
- Order-history preservation (no orders module exists).
