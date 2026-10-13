# Testing

## Commands
```powershell
cd backend; .\mvnw.cmd test                         # all backend tests
cd backend; .\mvnw.cmd test -Dtest=ProductServiceTest # one class
cd frontend; npm run lint; npm run build            # ESLint + tsc + Vite build (no frontend unit tests exist)
```
On Windows set `JAVA_HOME` (JDK 21) if missing.

## Locations
- Unit (Mockito): `backend/src/test/java/com/productrag/**/**Test.java` (e.g. `product/ProductServiceTest`).
- Web/security slice: `@WebMvcTest` + `@Import(SecurityConfig, JwtService, RestSecurityHandlers, ApiErrorWriter)`
  with `TestProperties.JWT_SECRET` (see `product/ProductControllerSecurityTest`).
- Validation: `product/ProductRequestValidationTest` (plain `Validator`).
- Integration: `ProductRagIntegrationTest` (Testcontainers pgvector, `@Testcontainers(disabledWithoutDocker = true)`;
  skipped on this machine because Docker is not installed). Fake AI: `support/FakeAiModels`, `HashingEmbedder`.
- Shared config builder: `support/TestProperties.defaults()`.

## Test database
- Tests never use `RagDB`. Integration tests use a throwaway Testcontainers DB; `APP_ENV_FILE` is redirected in
  `pom.xml` so the real `.env` is not read. No paid AI calls (fakes only).
- Manual catalog verification plan: scratch DB `ragdb_catalog_check` on local PostgreSQL + backend on port 8081.
  Creating it needs approval-flow access to `DB_PASSWORD`; never drop/reset `RagDB`.

## Seeds
- `node scripts/seed-sample-products.mjs` (backend running; logs in with `ADMIN_EMAIL`/`ADMIN_PASSWORD` from `.env`).
- Catalog seed `scripts/seed-catalog.mjs`: not written yet.

## Required catalog scenarios (to implement)
- Categories: duplicate name/slug 409, self parent, cycle, inactive parent, delete blocked when in use, rename
  propagates to products + outbox.
- Brands: duplicate name/slug, invalid URL 400, delete blocked when in use, inactive brand not assignable.
- Attributes: SELECT without options, options on non-select, duplicate name in category, slug clash in hierarchy,
  value type checks, required values, inactive attribute keeps historical value.
- Variants: unknown product, SKU duplicate (variant or product), salePrice >= price, negative stock, stock filters.
- Products: inactive category/brand not newly assignable but kept when unchanged; delete with variants 409.
- Security: GET authenticated, writes admin-only for each new controller.
- Seed: 30 per module, rerun creates no duplicates.

## Last executed results
| Date | Command | Result |
|------|---------|--------|
| 2026-10-13 (before catalog work) | `mvnw test` | 129 run, 0 failures, 13 skipped (no Docker) |
| 2026-10-13 (before catalog work) | `npm run lint`, `tsc` | clean |
| 2026-10-13 (catalog in progress) | `mvnw -q compile` | main code compiles; tests NOT run |

## Known failures
- Test sources do not compile yet after catalog changes: `ProductServiceTest` (constructor now needs
  `ProductCatalogLinks`, `AttributeValueValidator`, `CategoryRepository`, `ProductVariantRepository`),
  `ProductControllerSecurityTest` (`list(ProductListQuery, Pageable)`), `ProductRequestValidationTest`
  (`ProductRequest` gained `categoryId`, `brandId`; `category` no longer `@NotBlank`),
  `ProductRagIntegrationTest` (must create category/brand rows and clean catalog tables).
