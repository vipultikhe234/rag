# API contracts (as implemented in code)

Base `/api/v1`, JSON, `Authorization: Bearer <jwt>`. Examples with bodies: `docs/api-examples.md`.
Catalog endpoints below were verified against a running server on a scratch DB (see `TESTING.md`).

## Shared conventions
- Paging: `page` (>= 0), `size` (1-100), `sort=prop,asc|desc` (allowlist per endpoint; `id` tiebreak).
  Response `PageResponse`: `content`, `page`, `size`, `totalElements`, `totalPages`, ...
- Errors `ApiError`: `timestamp`, `status`, `error`, `code`, `message`, `path`, `fieldErrors[{field,message}]`.
  Common codes: `VALIDATION_FAILED` (400, with fieldErrors), `INVALID_PARAMETER` (400), `*_NOT_FOUND` (404),
  `*_ALREADY_EXISTS` / `SLUG_ALREADY_EXISTS` / `SKU_ALREADY_EXISTS` (409), `CONCURRENT_MODIFICATION` (409),
  `RECORD_IN_USE` (409), 401 unauthenticated, 403 not admin.
- Auth: GET = any authenticated user; POST/PUT/PATCH/DELETE = `ADMIN`.
- Update bodies are full representations; send `version` for optimistic locking. Catalog resources accept both
  `PUT /{id}` and `PATCH /{id}` for updates (same body).
- Status change: `PATCH /{id}/status` body `{"status":"ACTIVE"|"INACTIVE"}`.

## Existing (pre-catalog)
| Method | Path | Notes |
|--------|------|-------|
| POST | `/auth/login` | public; `{email,password}` -> token |
| GET | `/auth/me` | current user |
| GET/POST | `/products` | list / create (admin) |
| GET/PUT/DELETE | `/products/{id}` | delete blocked with `409 PRODUCT_HAS_VARIANTS` if variants exist |
| PATCH | `/products/{id}/status` | `ACTIVE`/`INACTIVE` |
| GET | `/products/categories` | distinct category names (legacy; the UI now uses `/categories/options`) |
| POST | `/chat/stream` | SSE `meta`, `delta`, `done`/`error` |
| GET/PATCH/DELETE | `/chat/conversations[/{id}]`, GET `/chat/conversations/{id}/messages` | owner only |
| GET/POST | `/admin/vector-sync/status|events|retry/{eventId}|retry-failed|resync-missing` | admin |

### Products changes (catalog integration)
- Request adds `categoryId`, `brandId` (UUID). Category: `categoryId` or legacy `category` name (must match an
  existing category, case-insensitive); one required. Brand optional, same pattern.
  Errors: `categoryId`/`category`/`brandId`/`brand` field errors (unknown, inactive when newly assigned).
- `attributes` values validated against applicable attribute definitions (keyed by slug), field
  `attributes.<slug>`; required active attributes enforced.
- Response adds `categoryId`, `brandId`.
- List adds `categoryId` (includes subcategories), `brandId`, repeated `attr=slug:value` (max 10).
- SKU must not collide with a variant SKU.

## Categories `/categories`
| Method | Path | Params / body |
|--------|------|---------------|
| GET | `/categories` | `q`, `status`, `parentId`, `rootsOnly`, paging; sort `name|slug|status|createdAt|updatedAt` (default `name,asc`) |
| GET | `/categories/options` | `includeInactive` -> `[{id,name,slug,parentId,path,status}]` |
| GET | `/categories/{id}` | |
| POST / PUT / PATCH | `/categories`, `/categories/{id}` | `{name*, slug, description, imageUrl, parentId, status, version}` |
| PATCH | `/categories/{id}/status` | |
| DELETE | `/categories/{id}` | 409 `CATEGORY_IN_USE` / `CATEGORY_HAS_SUBCATEGORIES` / `CATEGORY_HAS_ATTRIBUTES` |

Response: `id,name,slug,description,imageUrl,parentId,parentName,status,productCount,subcategoryCount,
attributeCount,version,createdAt,updatedAt`. Codes: `CATEGORY_ALREADY_EXISTS`, `SLUG_ALREADY_EXISTS`,
`parentId` field errors (self, missing, cycle, inactive).

## Brands `/brands`
Same shape as categories without hierarchy. Body `{name*, slug, description, logoUrl, websiteUrl, status,
version}` (URLs http/https). List `q`, `status`. `GET /brands/options`. DELETE -> 409 `BRAND_IN_USE`.
Response includes `productCount`. Codes: `BRAND_ALREADY_EXISTS`, `SLUG_ALREADY_EXISTS`.

## Product attributes `/product-attributes` (no DELETE)
- GET list: `q`, `categoryId`, `dataType`, `status`, `filterable`, paging; sort `name|slug|dataType|status|createdAt|updatedAt`.
- GET `/product-attributes/applicable?categoryId=&includeInactive=` -> own + inherited attributes, root first.
- POST/PUT body `{categoryId*, name*, slug, dataType* (STRING|NUMBER|BOOLEAN|SELECT), options[], unit,
  required, filterable, status, version}`; SELECT needs 1-50 distinct options, others none.
- Codes: `ATTRIBUTE_ALREADY_EXISTS` (name in category, or slug in category hierarchy), `ATTRIBUTE_NOT_FOUND`.
- Response: `id,categoryId,categoryName,name,slug,dataType,options,unit,required,filterable,status,version,...`.

## Product variants `/product-variants` (no DELETE)
- GET list: `q` (variant/product name or SKU), `productId`, `sku`, `status`, `minPrice`, `maxPrice`,
  `stock=AVAILABLE|IN_STOCK|LOW_STOCK|OUT_OF_STOCK`, paging; sort
  `sku|variantName|productName|price|stockQuantity|status|createdAt|updatedAt` (default `createdAt,desc`).
- POST/PUT body `{productId*, sku*, variantName*, attributes, price*, salePrice, stockQuantity*, status, version}`.
- Rules: product exists (and active when newly linked); SKU unique across variants and products
  (409 `SKU_ALREADY_EXISTS`); `salePrice < price`; stock 0..1e9.
- Response adds `productName, productSku, productStatus, currency, effectivePrice, stockLevel`
  (`LOW_STOCK` = 1..`app.products.low-stock-threshold`, default 5).
