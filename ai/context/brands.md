# Brands

**Status:** backend written, compiles; untested; no UI; no seed. API details: `ai/API_CONTRACTS.md`.

## Files (`backend/src/main/java/com/productrag/catalog/brand/`)
- `Brand`, `BrandRepository`, `BrandSpecifications`, `BrandService`, `BrandController` (`/api/v1/brands`),
  `dto/BrandRequest|Response|Option`. URL check: `catalog/validation/HttpUrl` (+ `HttpUrlValidator`).
- Table `brands` in `V3__catalog_modules.sql`; `products.brand_id` FK (RESTRICT).

## Rules
- Name unique case-insensitively, slug unique (derived when blank).
- `logoUrl`, `websiteUrl` optional, absolute http/https with host.
- Delete only when no product references it (`BRAND_IN_USE`), otherwise deactivate.
- Inactive brands cannot be newly assigned (`ProductCatalogLinks.brand`); existing products keep them.
- Rename updates `products.brand` text + `PRODUCT_UPDATED` outbox events.
- `productCount` via grouped query `ProductRepository.countByBrandIds`.

## Seed plan
- 30 well-known brands with official website URLs, `logoUrl` left empty (no copied logos), a few inactive.
- Exact names from the original prompt were not recorded; planned list in `ai/TASKS.md` — confirm with user.
- Backfill from existing products also creates brands such as SoundWave, FitPulse, TrailMate (not fixtures).

## Tests
- None yet.

## Known issues
- None verified yet.
