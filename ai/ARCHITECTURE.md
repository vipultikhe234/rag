# Architecture (verified facts)

Details: `docs/architecture.md`, `docs/rag-flow.md`, `docs/vector-sync.md`.

## Backend
- Single Spring Boot app, base package `com.productrag`, layers `controller -> service -> repository`.
- `common`: `ApiError`, `GlobalExceptionHandler` (maps exceptions + unique/FK violations to codes),
  `PageResponse`, `PageRequests` (paging + sort allowlist), request size limit, rate limiter.
- `config`: `AppProperties` (typed `app.*` config, validated), `SecurityConfig`, async/scheduling, AI HTTP client.
- `product`: `Product` entity (JSONB `attributes`, `@Version`), `ProductService`, `ProductController`,
  `ProductSpecifications`, `ProductCatalogLinks` (resolves category/brand for a request).
- `catalog`: `category`, `brand`, `attribute`, `variant` sub-packages (entity, repository, specifications,
  service, controller, `dto/`), shared `CatalogStatus`, `Slugs`, `Counts`, `CatalogText`, `StatusRequest`,
  `validation/HttpUrl`.
- `vectorsync`: `VectorSyncOutbox.enqueue(product, type)` must run inside the product write transaction
  (propagation MANDATORY); worker embeds asynchronously.
- `embedding`: `EmbeddingContentBuilder` builds embedded text from product name, category, brand, attributes.
- `rag`/`chat`/`ai`: retrieval (structured SQL + pgvector), prompt building, streaming chat via SSE.

## Frontend
- `src/api` (Axios client with JWT, `endpoints.ts`, `types.ts`, error normalization, SSE chat stream),
  `src/auth` (session storage, `ProtectedRoute requireAdmin`), `src/components` (layout `AppLayout` with
  `NAV_SECTIONS`, ui kit, toasts), `src/features` (products, chat, catalog), `src/pages` (+ `pages/catalog`).
- `features/catalog`: `CatalogUi` (PageHeader, DetailList, StatusToggle with confirm + toast, ListBody
  loading/error/empty), `hooks` (`useListParams` URL filters, `useDebouncedSearch`, `useLoader`),
  `useSpecDefinitions` + `SpecFields` + `attributeValues` (typed attribute inputs shared by product and variant
  forms), `formErrors.applyServerErrors`, `validation`, `messages`, `styles`.
- Routes in `src/App.tsx`; catalog routes generated from `CATALOG_MODULES`; chat and catalog pages lazy-loaded.

## Database
- One PostgreSQL DB holds products, catalog tables, embeddings, outbox, users, chat. See `ai/DATABASE.md`.

## Module relationships
- Product -> Category (`category_id`, required by the app) and Brand (`brand_id`, optional). The text columns
  `products.category` / `products.brand` mirror the linked names (used by RAG and embeddings); renaming a
  category/brand updates them and enqueues `PRODUCT_UPDATED` outbox events.
- Category -> parent Category (self reference). Attribute definition -> Category (inherited by subcategories).
- Product attribute values live in `products.attributes`, keyed by attribute slug; validated by
  `AttributeValueValidator`. Keys without a definition remain allowed (free-form, backward compatible).
- Variant -> Product (`product_id`, RESTRICT). Products with variants cannot be deleted.

## Product management design (V4, planned; see `ai/RESEARCH.md`)
- Product = shared identity (name, slug, description, category, brand, images, specs `attributes`, status).
  Simple product (`hasVariants=false`): product-level SKU, price, stock. Variant product: options (max 3,
  values max 50 each) and variants (max 100); each variant = one option combination with SKU, price,
  compare-at price, stock, barcode, image, status. Product price/stock = backend summary (not editable).
- Attributes (specs) are global definitions linked to categories; an option may link to an attribute so its
  values are validated/reused (SELECT options, NUMBER values).
- Create flow: `POST /products` with `options` + `variants` -> one transaction: validate category (self and
  ancestors active), brand, specs, options, combinations, SKUs (request + DB), then insert product, images,
  options, values, variants, variant options, summary, outbox event. Any failure rolls back everything.
- Variant writes lock the product row (`PESSIMISTIC_WRITE`) so the summary and combination checks are
  serialized per product; the summary update does not bump the product `version`.
- Product edit: `PUT /products/{id}` updates fields, images (matched by id/url to keep variant references),
  options (rename values, add values/options; a new option gives existing variants its first value; values
  in use cannot be removed); `variants` in the body creates additional variants. Variant product -> simple is
  refused once variants exist.
- Inactive rules: category assignable only when it and all ancestors are active; inactive brand/attribute/
  product/variant keep existing references; nothing referenced is hard-deleted.

## Auth
- `POST /api/v1/auth/login` returns a JWT; `JwtAuthenticationFilter` reloads the user per request.
- `SecurityConfig`: GET on catalog paths (`/products`, `/categories`, `/brands`, `/product-attributes`,
  `/product-variants`) needs authentication; other methods need `ADMIN`; `/api/v1/admin/**` and
  `/actuator/**` (except health/info) need `ADMIN`; everything else under `/api/v1` needs authentication.
- Write endpoints also carry `@PreAuthorize("hasRole('ADMIN')")`.
- Admin account is created at startup from `ADMIN_EMAIL` / `ADMIN_PASSWORD` in `.env` (weak or placeholder
  passwords rejected).
