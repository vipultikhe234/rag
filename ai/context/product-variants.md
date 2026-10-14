# Product variants

**Status:** done — backend, UI, tests, seeded; V4 options model browser-checked on scratch and applied to `RagDB`
(2026-10-14). API details: `ai/API_CONTRACTS.md`.

## Files
- Backend `catalog/variant/`: `ProductVariant` (`@ManyToOne(LAZY) product`), `ProductVariantRepository`,
  `ProductVariantSpecifications` (category incl. subcategories, brand, option `EXISTS` subqueries), `VariantListQuery`,
  `StockLevel`, `StockFilter`, `ProductVariantService` (create/update/batch, product row lock, summary refresh),
  `ProductVariantController` (`/api/v1/product-variants`, no DELETE), `dto/ProductVariantRequest|Response`,
  `dto/VariantDraft` (variant inside a product/batch request), `dto/VariantBatchRequest`.
- Backend `catalog/option/`: `ProductOption`, `ProductOptionValue`, `VariantOptionValue` + repositories,
  `ProductOptionService` (sync options on product save, resolve a variant's `{name: value}` to value ids,
  signature, title), `dto/ProductOptionRequest|Response`, `dto/SelectedOption`.
- Backend `product/image/`: `ProductImage`, `ProductImageService`, `ProductImageRequest|Response` (variant image).
- Frontend: `pages/catalog/Variant{List,Details,Form}Page.tsx`; routes `/product-variants[/new|/:id|/:id/edit]`
  (`?productId=` filters the list / preselects the form); Variants tab on `pages/ProductDetailsPage.tsx`;
  combination builder `features/products/{OptionsEditor,VariantBuilder}.tsx`, `variants.ts` (cartesian product,
  dedup, titles), `Price.tsx` (compare-at + "On sale").

## Rules
- Only variant products (`hasVariants=true`) take variants; product must be active when a variant is newly linked.
  The product of a variant cannot change.
- Options: max 3 per product, 50 values each, 100 variants per product. A variant picks exactly one value for
  every product option; the combination (sorted value ids, `option_signature`) is unique per product ->
  409 `VARIANT_COMBINATION_EXISTS`. Title = values in option order joined by " / ", unit appended for options
  linked to a NUMBER attribute.
- SKU normalized upper-case; unique among variants and products -> 409 `SKU_ALREADY_EXISTS`. Barcode optional,
  unique when set -> 409 `BARCODE_ALREADY_EXISTS`.
- price >= 0 (2 decimals), optional `compareAtPrice` > price (replaces V3 `salePrice`), stock integer 0..1e9.
  Optional `imageId` must be one of the product's images.
- Every variant write recomputes the product summary: price = lowest active variant price, stock = active total.
- Deactivate only (`PATCH /{id}/status`); products with variants cannot be deleted (409 `PRODUCT_HAS_VARIANTS`).
  Removing an option or value used by a variant -> 409 `OPTION_IN_USE` / `OPTION_VALUE_IN_USE`.
- Stock level: OUT_OF_STOCK (0), LOW_STOCK (1..`app.products.low-stock-threshold`, default 5), IN_STOCK.
- Variants have their own embedding since V5 (`catalog-embeddings.md`): variant semantic search and chat
  `DISCOVERY`; title/SKU changes and product events re-queue them.

## Seed data
- 10 prerequisite products (not counted), each created with its options and variants in one request: SAM-GS24,
  APL-IP15 (Color + Storage Capacity), DELL-INS15, HP-PAV14 (Processor + RAM + Storage Capacity), LEVI-TEE-CN
  (Clothing Size + Color), NIKE-REV7 (Color + Shoe Size), BOAT-WAVE-SW, SONY-WH1000XM5 (Color), DECA-BTL,
  PUMA-PHASE-BP (Capacity + Color).
- 30 variants; 2 inactive (APL-IP15-512-BLK, NIKE-REV7-UK10-GRY), 11 with a compare-at price, 6 low stock,
  3 out of stock.

## Tests
- `ProductVariantServiceTest` (16), `ProductServiceTest`, `CatalogFixturesTest`,
  `ProductManagementIntegrationTest` (combinations, rollback, summary, filters, compare-at); browser check.

## Known issues
- No orders module exists, so order-history preservation is only covered by "deactivate, never delete".
- The variant form's product select lists the first 100 variant products by name (the current one is always added).
