# Product management (products + options + images)

**Status:** done — backend, UI, tests, seed; browser-checked on scratch; V4 applied to `RagDB` and its data
verified (2026-10-14). API: `ai/API_CONTRACTS.md`; schema: `ai/DATABASE.md` (V4).

## Files
- Backend `product/`: `Product` (+ `slug`, `hasVariants`), `ProductService` (create/update in one transaction with
  images, options and variant drafts; slug generation; summary refresh; batched list enrichment), `ProductController`
  (`/api/v1/products`, `GET/POST /{id}/variants`), `ProductListQuery`, `ProductSpecifications` (price range and stock
  over active variants via `EXISTS`), `ProductRepository` (`findByIdForUpdate`, `refreshVariantSummary`),
  `ProductCatalogLinks`, `dto/ProductRequest|Response` (`VariantSummary`), `product/image/*`.
- Options and variants: see `ai/context/product-variants.md`. Categories/brands/attributes: their own context files.
- Frontend: `pages/ProductListPage.tsx`, `pages/ProductFormPage.tsx`, `pages/ProductDetailsPage.tsx`;
  `features/products/{productForm.ts, ImagesEditor, OptionsEditor, VariantBuilder, variants.ts, Price}`;
  `features/catalog/{categoryTree.ts, formErrors.ts}`; nav group in `components/layout/AppLayout.tsx`; routes in
  `App.tsx` (`/products`, `/products/new`, `/products/:id`, `/products/:id/edit`).

## Rules
- Simple product: price + stock required. Variant product: >= 1 option and >= 1 variant on create; price = lowest
  active variant price, stock = active variant total (kept in sync on every variant write, under a row lock).
- Create with variants is all-or-nothing (one transaction); field errors come back as `variants[i].<field>`,
  `options[i].values[j]`, `images[i].url`, `attributes.<slug>` and are mapped to the form by `applyServerErrors`.
- Slug unique (derived from the name when blank). SKU unique across products and variants.
- Images are URLs only (http/https, max 20, ordered); no file upload.
- A product with variants cannot become simple or be deleted (`PRODUCT_HAS_VARIANTS`); deactivate instead.
- Specifications use the category's applicable attributes (typed, required enforced on the backend).
- Product descriptions are data, not instructions. Products, brands, categories, attributes and variants are
  embedded (`catalog-embeddings.md`); options and images are not.

## UI
- Nav group "Product Management": Products, Categories, Brands, Attributes, Variants (+ AI Chat).
- Add/Edit Product sections: Basic info, Category and subcategory (assignable only; subcategory resets when the
  category changes), Specifications, Pricing and inventory (simple vs variants), Options and variants (generated
  combinations, duplicates ignored, preview), Images, Review and save -> redirect to Product Details.
- Product Details tabs: Overview, Specifications, Images, Variants, Inventory.
- List: server-side search, category, brand, status, price range, stock, has-variants, attribute filters;
  row actions View, Edit, Manage variants, Activate/Deactivate.

## Tests
- `ProductServiceTest` (24), `ProductRequestValidationTest`, `ProductControllerSecurityTest`,
  `ProductManagementIntegrationTest` (10, opt-in on `ragdb_it_test`); browser check (see `ai/TESTING.md`).

## Known issues
- No frontend unit tests.
- Product update adds new variants only; existing variants are edited on the variant pages.
