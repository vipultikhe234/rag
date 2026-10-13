# Agreed requirements

Only update when requirements change. Status of each item is in `ai/TASKS.md`.

## Existing (implemented earlier)
- Product CRUD with JSONB attributes, search, filters, pagination, sorting, activate/deactivate, delete.
- Vector sync via transactional outbox; RAG chat with streaming, history, sources panel.
- JWT auth; `ADMIN` writes, any authenticated user reads.

## Catalog modules (request of 2026-10-13)
Four admin modules, each with create, view, update, deactivate; backend validation; search, filters,
pagination, sorting; admin UI (list, create, edit, details, confirm dialogs, loading/empty/error states, toasts).

### Categories
- Parent/subcategory hierarchy; a category cannot be its own parent; no cycles.
- No hard delete while referenced by products (also blocked by subcategories / attribute definitions).
- Only active categories can be assigned to new products; inactive ones stay visible on existing products.

### Brands
- Unique name and slug; logo and website must be valid http(s) URLs.
- Product count per brand. No hard delete while referenced.
- Inactive brands cannot be newly assigned; existing associations stay.

### Product attributes
- Data types: string, number, boolean, select. Linked to a category (inherited by subcategories).
- Select needs valid, distinct options. Product values validated by type.
- Name unique within a category. Filters: category, type, status.
- Category attributes appear in the product form; filterable ones in product list filters.
- Deactivation keeps historical product values.

### Product variants
- Belongs to an existing product; unique SKU; price >= 0; sale price validated against price (< price);
  stock integer >= 0.
- Filters: product, SKU, status, price range, stock availability. Show low-stock and out-of-stock.
- Deactivation keeps references (no delete).

### Seed data
- Exactly 30 fixture records per module (120 total) plus prerequisite products for variants.
- Deterministic, rerun-safe (no duplicates), parents first, verify target DB first, verify counts in code.
- Never seed/reset a production or shared DB without approval.

### Other
- Tests for each module's rules plus seed verification; run the existing suite.
- Swagger/OpenAPI only if it can be added without disruption.
- Keep products compatible with semantic search/RAG: `products.category` / `products.brand` text columns stay
  and are kept in sync with the linked records.
- Do not create embeddings for the four admin modules.
