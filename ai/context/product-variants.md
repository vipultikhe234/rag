# Product variants

**Status:** done — backend, UI, tests, seeded. UI not browser-tested. API details: `ai/API_CONTRACTS.md`.

## Files
- Backend `catalog/variant/`: `ProductVariant` (`@ManyToOne(LAZY) product`), `ProductVariantRepository`
  (`@EntityGraph("product")` on `findAll(spec, pageable)`, `existsByProductId`), `ProductVariantSpecifications`,
  `StockLevel`, `StockFilter`, `ProductVariantService`, `ProductVariantController` (`/api/v1/product-variants`,
  no DELETE), `dto/ProductVariantRequest|Response`. Table `product_variants`; FK to products ON DELETE RESTRICT.
- Frontend: `pages/catalog/Variant{List,Details,Form}Page.tsx`; routes `/product-variants[/new|/:id|/:id/edit]`
  (`?productId=` filters the list / preselects the form). Product form links "Manage variants".

## Rules
- Product must exist; must be active when a variant is newly linked to it (form offers active products only,
  plus the current one).
- SKU normalized upper-case; unique among variants and must not equal any product SKU (and vice versa,
  enforced in `ProductService`) -> 409 `SKU_ALREADY_EXISTS`.
- price >= 0 (2 decimals), optional salePrice >= 0 and < price, stock integer 0..1e9.
- Variant `attributes` type-checked against the product category's definitions (required not enforced).
- Stock level: OUT_OF_STOCK (0), LOW_STOCK (1..`app.products.low-stock-threshold`, default 5), IN_STOCK.
- Deactivate only; products with variants cannot be deleted (409 `PRODUCT_HAS_VARIANTS`).
- Variants are not embedded and not used by RAG.

## Seed data
- 10 prerequisite products (not counted): SAM-GS24, APL-IP15, DELL-INS15, HP-PAV14, LEVI-TEE-CN, NIKE-REV7,
  BOAT-WAVE-SW, SONY-WH1000XM5, DECA-BTL, PUMA-PHASE-BP.
- 30 variants split 4/4/3/3/4/4/2/2/2/2 in that order; 2 inactive (APL-IP15-512-BLK, NIKE-REV7-UK10-GRY),
  11 with a sale price, 6 low stock, 3 out of stock.

## Tests
- `ProductVariantServiceTest` (8), `CatalogFixturesTest`, `ProductServiceTest` (SKU clash, delete guard); live
  API checks (stock/price filters, sale price rule).

## Known issues
- No orders module exists, so order-history preservation is only covered by "deactivate, never delete".
- The variant form's product select lists the first 100 products by name (the current product is always added).
