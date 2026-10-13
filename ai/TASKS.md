# Tasks

Legend: `[x]` done and verified, `[~]` code written but not verified, `[ ]` not started.
Last updated: 2026-10-13.

## Product management system (second round) — plan
- [x] Research (Shopify, Medusa official docs) -> `ai/RESEARCH.md`
- [x] Inspection + design decisions confirmed by the user (`ai/REQUIREMENTS.md`, `ai/ARCHITECTURE.md`)
- [ ] V4 migration: attribute-category join, options, option values, variant options, images, product slug /
  has_variants, compare-at price, backfill
- [ ] Categories: inactive ancestor blocks assignment; `assignable` in options; reactivation tests
- [ ] Attributes: many-to-many categories, global slug/name, `required` filter, applicable via join
- [ ] Products: slug, images, hasVariants, options, create with variants (one transaction), summary price/stock,
  filters (price range, stock, hasVariants), `GET/POST /products/{id}/variants`
- [ ] Variants: options/combination uniqueness, compare-at price, barcode, image, filters (category, brand,
  option values), product row lock + summary refresh
- [ ] Admin UI: nav "Product Management", product form sections (basic, category/subcategory, specs, pricing,
  variants with combination builder, review), Product Details tabs, list filters, variant pages updated
- [ ] Seeds: fixtures updated for options/compare-at; 30/30/30/30 verified on scratch DB, then `RagDB`
- [ ] Tests: unit + WebMvc + integration (`ragdb_it_test`) + lint/build + browser check
- [ ] Docs: ai/ context files (product-management, product-brands rename), API_CONTRACTS, README

## Catalog modules (first round) — done
- [x] Category, Brand, Attribute, Variant modules (backend, UI, tests), V3 applied to scratch DB and `RagDB`
- [x] Seed 30 categories, 30 brands, 30 attributes, 30 variants (+10 prerequisite products), verified twice
- [x] Admin UI browser-verified; layout fixes; Docker removed; `AI_PROVIDER_UNREACHABLE`
- [x] Automated tests — 203 run, 0 failures, 13 skipped without `IT_DB_URL`; integration 13/13 on `ragdb_it_test`
- [ ] Optional: Swagger/OpenAPI — not added (springdoc not in `pom.xml`)

## Blockers / notes
- No orders module exists, so "history preserved for orders" is covered by deactivate-not-delete.
- `RagDB` also holds 10 categories / 11 brands backfilled by V3 from product text; not fixtures, left untouched.
- Scratch DBs `ragdb_catalog_check` and `ragdb_it_test` exist (drop only with the user's go-ahead).
- Main JS chunk is 503 kB (Vite advisory warning at 500 kB).
