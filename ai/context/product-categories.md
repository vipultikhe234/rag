# Categories

**Status:** done — backend, UI, tests, seeded (scratch DB + `RagDB`), browser-checked on scratch.
API details: `ai/API_CONTRACTS.md`.

## Files
- Backend `catalog/category/`: `Category` (entity, `parentId` UUID column, not a JPA relation),
  `CategoryRepository` (recursive CTE `findSelfAndDescendantIds`, `findSelfAndAncestorIds`; grouped
  `countChildren`), `CategorySpecifications`, `CategoryService`, `CategoryController` (`/api/v1/categories`),
  `dto/CategoryRequest|Response|Option`, `CategoryAssignability` (inactive self/ancestor check, used by products,
  attributes and the parent select). Table `categories` in `V3__catalog_modules.sql`.
- Frontend: `pages/catalog/Category{List,Details,Form}Page.tsx`; routes `/categories`, `/categories/new`,
  `/categories/:id`, `/categories/:id/edit` (new/edit admin-only). `features/catalog/categoryTree.ts` splits
  options into top-level category + subcategory for the product form (only `assignable` choices offered).

## Rules
- Name unique case-insensitively; slug unique, derived from name when blank (`Slugs.from`, e.g.
  "Men's Clothing" -> `mens-clothing`), max 120.
- Parent must exist, not self, not a descendant (no cycles); a parent that is inactive or under an inactive
  ancestor cannot be newly chosen. The form hides the category itself and its descendants from the parent select.
- A category is `assignable` only when it and every ancestor are active. Deactivating a parent blocks new
  assignments to its whole subtree without changing the children's own status; reactivating it makes the active
  children assignable again.
- Delete only when no products, subcategories or attribute definitions reference it; otherwise deactivate
  (details page disables Delete when any count > 0).
- Non-assignable categories cannot be newly assigned to products (`ProductCatalogLinks`) or attributes, but stay
  on existing records. In the product form, changing the top-level category resets the subcategory.
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
- `CategoryServiceTest` (17, incl. inactive-ancestor and reactivation cases), `CatalogControllerSecurityTest`
  (shared), `CatalogFixturesTest`, `ProductManagementIntegrationTest.inactiveParentsBlockNewAssignmentsUntilReactivated`.

## Known issues
- `RagDB` also contains 10 categories backfilled by V3 from product text (e.g. Audio, Wearables, Travel); they
  overlap in meaning with some fixtures and were left as they are.
