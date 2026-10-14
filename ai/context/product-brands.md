# Product brands

**Status:** done — backend, UI, tests, seeded; browser-checked (list filters, create/edit). Unchanged by V4.
API details: `ai/API_CONTRACTS.md`.

## Files
- Backend `catalog/brand/`: `Brand`, `BrandRepository`, `BrandSpecifications`, `BrandService`, `BrandController`
  (`/api/v1/brands`), `dto/BrandRequest|Response|Option`. URL check: `catalog/validation/HttpUrl`.
  Table `brands` in `V3__catalog_modules.sql`; `products.brand_id` FK (RESTRICT).
- Frontend: `pages/catalog/Brand{List,Details,Form}Page.tsx`; routes `/brands[/new|/:id|/:id/edit]`.

## Rules
- Name unique case-insensitively, slug unique (derived when blank).
- `logoUrl`, `websiteUrl` optional, absolute http/https with host.
- Delete only when no product references it (`BRAND_IN_USE`), otherwise deactivate.
- Inactive brands cannot be newly assigned (`ProductCatalogLinks.brand`); existing products keep them.
- Rename updates `products.brand` text + `PRODUCT_UPDATED` outbox events.
- `productCount` via grouped query `ProductRepository.countByBrandIds`.
- Product list and global variant list filter by `brandId`.

## Seed data
- The 30 names from the prompt: Samsung, Apple, Dell, HP, Lenovo, ASUS, Acer*, Sony, LG, Xiaomi, OnePlus,
  Motorola, Realme, boAt, JBL, Logitech, Canon, Nikon*, Nike, Adidas, Puma, Reebok*, Levi's, H&M, Zara, Philips,
  Whirlpool, Bosch, Havells, Decathlon (* = inactive).
- Official https website URLs; `logoUrl` left empty on purpose (no copied or placeholder logos).

## Tests
- `BrandServiceTest` (6), `CatalogControllerSecurityTest`, `CatalogFixturesTest`; live API checks.

## Known issues
- In `RagDB`, "Apple" already existed (V3 backfill, no website/description); the seed reused it unchanged.
- Backfill also created non-fixture brands such as SoundWave, FitPulse, TrailMate.
