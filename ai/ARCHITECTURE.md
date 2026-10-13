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

## Auth
- `POST /api/v1/auth/login` returns a JWT; `JwtAuthenticationFilter` reloads the user per request.
- `SecurityConfig`: GET on catalog paths (`/products`, `/categories`, `/brands`, `/product-attributes`,
  `/product-variants`) needs authentication; other methods need `ADMIN`; `/api/v1/admin/**` and
  `/actuator/**` (except health/info) need `ADMIN`; everything else under `/api/v1` needs authentication.
- Write endpoints also carry `@PreAuthorize("hasRole('ADMIN')")`.
- Admin account is created at startup from `ADMIN_EMAIL` / `ADMIN_PASSWORD` in `.env` (weak or placeholder
  passwords rejected).
