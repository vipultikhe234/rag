# Tasks

Legend: `[x]` done and verified, `[~]` code written but not verified, `[ ]` not started.
Last updated: 2026-10-13.

## Catalog modules
- [~] Category Management — backend written and compiles; no UI, no tests, not run against a DB
- [~] Brand Management — backend written and compiles; no UI, no tests
- [~] Product Attributes Management — backend + `AttributeValueValidator` written and compile; no UI, no tests
- [~] Product Variant Management — backend written and compiles; no UI, no tests
- [~] Product integration — `categoryId`/`brandId`, active-only assignment, attribute validation, list filters
  (`categoryId`, `brandId`, `attr=`), delete guard; compiles, untested (`jsonb_extract_path_text` filter unverified)
- [~] Schema — `V3__catalog_modules.sql` written; not applied to any database yet
- [ ] Seed 30 categories
- [ ] Seed 30 brands
- [ ] Seed 30 attributes
- [ ] Seed 30 variants (+ prerequisite products)
- [ ] Admin UI integration (4 modules: list/create/edit/details; nav group; product form/list integration)
- [~] API validation — Bean Validation + service rules in code; untested
- [ ] Automated tests (fix existing tests first — they no longer compile; then new module tests)
- [ ] Verify all 120 required fixture records
- [ ] Docs: README + `docs/api-examples.md` catalog sections
- [ ] Optional: Swagger/OpenAPI (springdoc not in `pom.xml`; add only if non-disruptive)

## Next steps (in order)
1. Fix existing tests to compile; add catalog unit + `@WebMvcTest` security tests; run `mvnw test`.
2. Write `scripts/seed-catalog.mjs` (REST-based, localhost guard, idempotent by slug/SKU, verifies counts) and
   update `seed-sample-products.mjs` to ensure its categories/brands exist.
3. Verify V3 + seed twice on scratch DB `ragdb_catalog_check` (backend on 8081), then on `RagDB` with approval.
4. Frontend pages and product form/list integration; `npm run lint` + `npm run build`.
5. Docs.

## Blockers / notes
- Original prompt's exact 30 brand names were not recorded; planned list (to confirm with user): Samsung,
  Apple, Dell, HP, Lenovo, Asus, Acer, Sony, LG, Xiaomi, OnePlus, Philips, Bosch, Whirlpool, Nike, Adidas,
  Puma, Levi's, Zara, H&M, Titan, Fossil, boAt, JBL, Milton, Prestige, Lakme, Nivea, Nestle, Wildcraft.
- Category and attribute fixture lists: see `ai/context/product-categories.md`, `product-attributes.md`.
- Docker not installed: Testcontainers integration tests are skipped.
- The user's running backend must be restarted after these changes (V3 runs on next start against `RagDB`).
