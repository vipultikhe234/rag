# Categories

**Status:** done — backend, UI, tests, seeded (scratch DB + `RagDB`). UI not browser-tested.
API details: `ai/API_CONTRACTS.md`.

## Files
- Backend `catalog/category/`: `Category` (entity, `parentId` UUID column, not a JPA relation),
  `CategoryRepository` (recursive CTE `findSelfAndDescendantIds`, `findSelfAndAncestorIds`; grouped
  `countChildren`), `CategorySpecifications`, `CategoryService`, `CategoryController` (`/api/v1/categories`),
  `dto/CategoryRequest|Response|Option`. Table `categories` in `V3__catalog_modules.sql`.
- Frontend: `pages/catalog/Category{List,Details,Form}Page.tsx`; routes `/categories`, `/categories/new`,
  `/categories/:id`, `/categories/:id/edit` (new/edit admin-only).

## Rules
- Name unique case-insensitively; slug unique, derived from name when blank (`Slugs.from`, e.g.
  "Men's Clothing" -> `mens-clothing`), max 120.
- Parent must exist, not self, not a descendant; an inactive parent cannot be newly chosen. The form hides the
  category itself and its descendants from the parent select.
- Delete only when no products, subcategories or attribute definitions reference it; otherwise deactivate
  (details page disables Delete when any count > 0).
- Inactive categories cannot be newly assigned to products (`ProductCatalogLinks`) but stay on existing products.
- Rename updates `products.category` text for linked products and enqueues `PRODUCT_UPDATED` (re-embedding).
- Product list `categoryId` filter includes descendants.

## Seed data (`scripts/catalog-fixtures.json`; * = inactive)
- Electronics > Mobile Phones, Laptops, Computer Accessories
- Home Appliances > Kitchen Appliances
- Fashion > Men's Clothing, Women's Clothing, Kids' Clothing*, Footwear, Watches, Bags and Luggage
- Beauty and Personal Care > Skincare, Haircare*
- Grocery > Beverages, Snacks
- Home Decor > Furniture
- Sports Equipment > Fitness Accessories
- Roots: Kitchenware, Books, Stationery, Toys and Games, Baby Products, Automotive Accessories*, Pet Supplies*
- 30 total: 14 roots, 16 children, 4 inactive.

## Tests
- `CategoryServiceTest` (14), `CatalogControllerSecurityTest` (shared), `CatalogFixturesTest`; live API checks.

## Known issues
- `RagDB` also contains 10 categories backfilled by V3 from product text (e.g. Audio, Wearables, Travel); they
  overlap in meaning with some fixtures and were left as they are.
