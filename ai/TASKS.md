# Tasks

Legend: `[x]` done and verified, `[~]` code written but not verified, `[ ]` not started.
Last updated: 2026-10-13.

## Catalog modules
- [x] Category Management — backend, UI (list/details/create/edit), unit + security tests, verified via API on
  scratch DB `ragdb_catalog_check`
- [x] Brand Management — same
- [x] Product Attributes Management — backend + `AttributeValueValidator`, UI, tests, API-verified
- [x] Product Variant Management — backend, UI, tests, API-verified (stock/price filters, sale price rule)
- [x] Product integration — `categoryId`/`brandId`, active-only assignment, attribute validation, list filters
  (`categoryId` incl. descendants, `brandId`, `attr=`), delete guard; API-verified incl. JSONB `attr` filter
- [x] Schema — `V3__catalog_modules.sql` applied cleanly on `ragdb_catalog_check` and on `RagDB`
- [x] Seed 30 categories (16 subcategories, 4 inactive)
- [x] Seed 30 brands (the 30 names from the prompt; 3 inactive; no logo URLs)
- [x] Seed 30 attributes (2 inactive, 2 required)
- [x] Seed 30 variants (+ 10 prerequisite products, not counted)
- [~] Admin UI integration — 12 catalog pages, nav group "Catalog", product form (category/brand selects, typed
  spec fields) and product list (category/brand/attribute filters). `npm run lint` + `npm run build` pass;
  **not exercised in a browser** (needs an admin sign-in)
- [x] API validation — Bean Validation + service rules; covered by unit, WebMvc and live API checks
- [x] Automated tests — 202 run, 0 failures, 13 skipped (Testcontainers; no Docker)
- [x] Verify all 120 required fixture records — scratch DB (2 runs: 130 created, then 130 reused) and `RagDB`
- [x] Docs: README + `docs/api-examples.md` catalog sections
- [ ] Optional: Swagger/OpenAPI — not added (springdoc not in `pom.xml`; would be a new dependency)

## Next steps
1. Sign in at http://localhost:5173 and click through the catalog pages and product form (browser check).
2. Start Ollama, then "Retry all failed" in the AI search index panel for the seeded products.
3. Run `mvnw test` on a machine with Docker so `ProductRagIntegrationTest` (updated for catalog tables) runs.
4. Optional: drop scratch DB `ragdb_catalog_check` when no longer needed (user decision).

## Blockers / notes
- Docker not installed: Testcontainers integration tests are skipped.
- No orders module exists, so "variant history preserved for orders" is only covered by deactivate-not-delete.
- `RagDB` also holds 10 categories / 11 brands backfilled by V3 from existing product text (Audio, Wearables,
  SoundWave, …); they are not fixtures and were left untouched.
- Brand "Apple" already existed in `RagDB` (backfill, no website); the seed reused it unchanged.
- Main JS chunk is 502 kB (Vite advisory warning at 500 kB); catalog pages are lazy-loaded (53 kB chunk).
