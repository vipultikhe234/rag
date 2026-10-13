# Product variants

**Status:** backend written, compiles; untested; no UI; no seed. API details: `ai/API_CONTRACTS.md`.

## Files (`backend/src/main/java/com/productrag/catalog/variant/`)
- `ProductVariant` (`@ManyToOne(LAZY) product`), `ProductVariantRepository` (`@EntityGraph("product")` on
  `findAll(spec, pageable)`, `existsByProductId`), `ProductVariantSpecifications`, `StockLevel`, `StockFilter`,
  `ProductVariantService`, `ProductVariantController` (`/api/v1/product-variants`, no DELETE),
  `dto/ProductVariantRequest|Response`.
- Table `product_variants` in `V3__catalog_modules.sql`; FK to products ON DELETE RESTRICT.

## Rules
- Product must exist; must be active when a variant is newly linked to it.
- SKU normalized upper-case; unique among variants and must not equal any product SKU (and vice versa,
  enforced in `ProductService`) -> 409 `SKU_ALREADY_EXISTS`.
- price >= 0 (2 decimals), optional salePrice >= 0 and < price, stock integer 0..1e9.
- Variant `attributes` type-checked against the product category's definitions (required not enforced).
- Stock level: OUT_OF_STOCK (0), LOW_STOCK (1..`app.products.low-stock-threshold`, default 5), IN_STOCK.
- Deactivate only; products with variants cannot be deleted (409 `PRODUCT_HAS_VARIANTS`).
- Variants are not embedded and not used by RAG.

## Seed plan (30 variants)
- Prerequisite products (create if missing, reuse by SKU): Samsung Galaxy S24, iPhone 15, Dell Inspiron,
  HP Pavilion, cotton T-shirt, Nike running shoes; existing samples WH-001 (headphones), SW-450 (smartwatch),
  BTL-750 (water bottle), BAG-LAP-156 (backpack).
- Split: 4 + 4 + 3 + 3 + 4 + 4 + 2 + 2 + 2 + 2; include low-stock, out-of-stock, on-sale and inactive examples.

## Tests
- None yet.

## Known issues
- Sorting by `productName` uses `product.name` path; not yet verified against PostgreSQL.
