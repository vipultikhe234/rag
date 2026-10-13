# Categories

**Status:** backend written, compiles; untested; no UI; no seed. API details: `ai/API_CONTRACTS.md`.

## Files (`backend/src/main/java/com/productrag/catalog/category/`)
- `Category` (entity, `parentId` UUID column, not a JPA relation), `CategoryRepository` (recursive CTE queries
  `findSelfAndDescendantIds`, `findSelfAndAncestorIds`; grouped `countChildren`), `CategorySpecifications`,
  `CategoryService`, `CategoryController` (`/api/v1/categories`), `dto/CategoryRequest|Response|Option`.
- Table `categories` in `V3__catalog_modules.sql`.

## Rules
- Name unique case-insensitively; slug unique, derived from name when blank (`Slugs.from`, e.g.
  "Men's Clothing" -> `mens-clothing`), max 120.
- Parent must exist, not self, not a descendant (cycle check via ancestors of the new parent); an inactive
  parent cannot be newly chosen.
- Delete only when no products, subcategories or attribute definitions reference it; otherwise deactivate.
- Deactivation changes nothing else; inactive categories cannot be newly assigned to products
  (`ProductCatalogLinks`) but stay on existing products.
- Rename updates `products.category` text for linked products and enqueues `PRODUCT_UPDATED` (re-embedding).
- Response counts (products, subcategories, attributes) use batched grouped queries.
- Product list `categoryId` filter includes descendants.

## Seed plan (30, parents first; * = inactive)
- Electronics > Mobile Phones, Laptops, Computer Accessories
- Home Appliances > Kitchen Appliances
- Fashion > Men's Clothing, Women's Clothing, Kids' Clothing*, Footwear, Watches, Bags and Luggage
- Beauty and Personal Care > Skincare, Haircare*
- Grocery > Beverages, Snacks
- Home Decor > Furniture
- Sports Equipment > Fitness Accessories
- Roots: Kitchenware, Books, Stationery, Toys and Games, Baby Products, Automotive Accessories*, Pet Supplies*

## Tests
- None yet (see scenarios in `ai/TESTING.md`).

## Known issues
- V3 backfill creates categories from existing product text (e.g. Audio, Wearables, Travel); these are not part of
  the 30 fixtures and may overlap in meaning.
