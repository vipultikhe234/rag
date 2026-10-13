# Catalog research (2026-10-13)

Official documentation fetched live with the web fetch tool. Only the pages listed were read; nothing here is
assumed from memory beyond what these pages say.

## Sources
| # | Source | URL |
|---|--------|-----|
| S1 | Shopify dev: About products and collections | https://shopify.dev/docs/apps/build/product-merchandising/products-and-collections |
| S2 | Shopify Help: Variants | https://help.shopify.com/en/manual/products/variants |
| S3 | Shopify Help: Adding variants | https://help.shopify.com/en/manual/products/variants/add-variants |
| S4 | Shopify Help: Product details | https://help.shopify.com/en/manual/products/details/product-status |
| S5 | Shopify Help: Sale pricing (compare-at price) | https://help.shopify.com/en/manual/products/details/product-pricing/sale-pricing |
| S6 | Shopify Help: Standard Product Taxonomy / category metafields | https://help.shopify.com/en/manual/products/details/product-category |
| S7 | Shopify Admin GraphQL: ProductVariant | https://shopify.dev/docs/api/admin-graphql/latest/objects/ProductVariant |
| M1 | Medusa: Product Module | https://docs.medusajs.com/resources/commerce-modules/product |
| M2 | Medusa: Product variant inventory | https://docs.medusajs.com/resources/commerce-modules/product/variant-inventory |
| M3 | Medusa: Product module links (pricing, inventory, orders) | https://docs.medusajs.com/resources/commerce-modules/product/links-to-other-modules |
| M4 | Medusa: ProductVariant / ProductCategory data models | https://docs.medusajs.com/resources/references/product/models/ProductVariant , .../ProductCategory |

## Findings
1. **Product / option / variant / attribute** (S1, S2, M1). Three tiers: the *product* holds shared
   information (title, description, vendor/brand, media); *options* are what a buyer chooses (name + values,
   e.g. Color: Red, Green); a *variant* is one purchasable combination of option values with its own SKU,
   price, inventory and barcode. *Attributes* (Shopify "category metafields", S6) are descriptive
   specifications tied to the product category; they are not purchasable choices, but an option can be
   connected to a category attribute so its values are reused (S3, S6).
2. **Product vs variant fields** (S1, S3, S4, S7, M4). Product: name, description, category, brand/vendor,
   media, status, handle/slug. Variant: SKU, barcode, price, compare-at price, inventory, selected options,
   optional image chosen from the product's media (S3), rank/position (M4).
3. **Categories, brands, specs** (S6, M4). One category per product; categories form a tree (Medusa
   `parent_category`, `mpath`, `is_active`, `is_internal`, `handle`). The category decides which attributes
   (specs) apply; when the category changes, values connected to options are kept and others are re-evaluated (S6).
4. **Category-specific attributes** (S6). Attributes are reusable definitions attached to categories; the
   same attribute (e.g. color, size) is shared by many categories rather than duplicated.
5. **Variant lifecycle** (S3). Options and values generate combinations; admins pick which combinations to
   create. A manually added variant must differ in at least one option value; exact duplicates are rejected.
   Limits: 3 options per product, 2,048 variants (S3); bulk create up to 2,048 at a time (S1). Variant search
   by SKU, barcode, product, option values, inventory quantity, product status (S7).
6. **Price and stock with/without variants** (S4, M2, M3). Without variants, price/inventory are edited on
   the product page; once variants exist those sections move to each variant (S4). Medusa keeps prices
   (price sets) and inventory (inventory items/levels per location) per variant, never on the product (M2, M3).
   Sale price = `price` lowered, original kept in `compareAtPrice`, which must be higher than `price` (S5).
7. **Admin filters** (S7, S3). Product and variant lists filter by status, category, vendor/brand, SKU,
   option values, inventory; search, filter and group variants inside the product page (S2).
8. **Search/sort/paging** (S1). Server-side pagination with a maximum page size; sort keys explicit.
9. **Inactive records** (S2, S7, M4). Shopify product status `active/draft/archived/unlisted`, variants can be
   unpublished; Medusa categories `is_active`. Inactive data stays referenced (orders link read-only to
   product/variant, M3) - deactivate, do not delete.
10. **Integrity and transactions** (M1, M3). Medusa wraps product + options + variants creation in one
    workflow with rollback (M1); orders reference variants read-only (M3), so variants must not be hard-deleted.

## Design decisions for this project
| Decision | Why it fits here |
|----------|------------------|
| Keep PostgreSQL relational tables; add `product_options`, `product_option_values`, `variant_option_values` | Matches the three-tier model (finding 1) and gives FK + unique guarantees; existing stack is JPA + Flyway. |
| Variant combination uniqueness via `(product_id, option_signature)` unique index | DB-enforced "no duplicate combination" (finding 5) without triggers. |
| Max 3 options per product, max 100 variants per product in the admin | Shopify allows 3 options (finding 5); this admin has no bulk tooling, so a smaller variant cap keeps the UI usable. |
| Simple products keep product-level price/stock; variant products take price/stock from variants | Mirrors Shopify product page behaviour (finding 6). |
| Compare-at price (> price) instead of sale price (< price) | Industry convention (finding 6, S5); same information, clearer semantics. |
| Options may link to a category attribute (SELECT values reused) | Avoids duplicate definitions (findings 1, 4). |
| One inventory quantity per variant, no locations | No warehouses or orders module exist; Medusa's multi-location model (M2) would be unused complexity. |
| Deactivate instead of delete for every catalog record | Finding 9/10; keeps future order references valid. |
| Product + options + initial variants saved in one DB transaction | Finding 10 (Medusa workflow with rollback). |
| No file upload service: images are http(s) URLs | No upload/storage exists in the project; adding storage is a separate infrastructure decision. |
| No SEO fields | No storefront exists in this project; the slug covers URLs. |

## Not researched
- Multi-currency price lists, tax, shipping, sales channels: out of scope for this admin.
