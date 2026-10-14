# API contracts (as implemented in code)

Base `/api/v1`, JSON, `Authorization: Bearer <jwt>`. Examples with bodies: `docs/api-examples.md`.
Catalog endpoints below were verified against a running server on a scratch DB (see `TESTING.md`).

## Shared conventions
- Paging: `page` (>= 0), `size` (1-100), `sort=prop,asc|desc` (allowlist per endpoint; `id` tiebreak).
  Response `PageResponse`: `content`, `page`, `size`, `totalElements`, `totalPages`, ...
- Errors `ApiError`: `timestamp`, `status`, `error`, `code`, `message`, `path`, `fieldErrors[{field,message}]`.
  Common codes: `VALIDATION_FAILED` (400, with fieldErrors), `INVALID_PARAMETER` (400), `*_NOT_FOUND` (404),
  `*_ALREADY_EXISTS` / `SLUG_ALREADY_EXISTS` / `SKU_ALREADY_EXISTS` (409), `CONCURRENT_MODIFICATION` (409),
  `RECORD_IN_USE` (409), 401 unauthenticated, 403 not admin. AI calls (embeddings, chat):
  `AI_PROVIDER_UNREACHABLE` (503, provider not running / unknown host), `AI_PROVIDER_UNAVAILABLE` (503, timeout,
  429, 5xx), `AI_PROVIDER_NOT_CONFIGURED` (503), `AI_PROVIDER_ERROR` / `AI_PROVIDER_QUOTA_EXCEEDED` /
  `AI_PROVIDER_EMPTY_RESPONSE` (502).
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
| GET/POST | `/admin/vector-sync/status|events|retry/{eventId}|retry-failed|resync-missing|refresh-stale` | admin; see below |
| GET | `/products/semantic-search` | any authenticated user; see below |
| GET | `/brands|categories|product-attributes|product-variants/semantic-search` | any authenticated user; see below |

Vector sync admin (all modules):
- `status` adds `catalogIndex[{entityType, embedded, activeWithoutEmbedding}]` (BRAND, CATEGORY, ATTRIBUTE, VARIANT).
- `resync-missing` -> 202 `{enqueued (products), catalogEnqueued}`.
- `refresh-stale` -> 202 `{checked, stale, enqueued` (products)`, catalog: {checked, stale, enqueued,
  byType{BRAND|CATEGORY|ATTRIBUTE|VARIANT: {checked, stale, enqueued}}}}`.
- Events (`events`, `recentFailures`) add `aggregateType`; for catalog events `productId`/`productName`/`productSku`
  hold the entity id, name and (variants) SKU.

Chat `retrieval.strategy` values: `CATALOG` (whole-table answer for brands/categories/attributes/variants
questions, `llmUsed=false`, `totalMatches` = table size), `DISCOVERY` (brands/categories/attributes/variants
ranked by relevance to a topic, e.g. "which brands make gaming laptops"; `llmUsed=false`, no product sources,
`totalMatches` = entities listed; not used with price/stock conditions or an explicit SKU), `EXACT_SKU`,
`STRUCTURED`, `HYBRID`, `SEMANTIC`, `CLARIFICATION`. Structured filters may include `brand = <name>`.

### Catalog semantic search `GET /{brands|categories|product-attributes|product-variants}/semantic-search`
- Params: `q*` (2-200 chars), `limit` (1-50, default 10). Bad values -> 400 `INVALID_PARAMETER`.
- Response `{query, entityType, mode: HYBRID|KEYWORD_ONLY, embeddingModel, results[], warning}`; result
  `{item, similarity|null, matchType: NAME|SEMANTIC|SEMANTIC_AND_NAME}` where `item` is the module's normal
  response (`BrandResponse`, `CategoryResponse`, `ProductAttributeResponse`, `ProductVariantResponse`, read live).
- Order: name matches (exact, prefix, contains; variants also product name and SKU), then vector similarity
  >= `RAG_SIMILARITY_THRESHOLD`. ACTIVE entities only (variants also need an ACTIVE product).
- Embedding provider failure -> 200 with `mode=KEYWORD_ONLY` and a `warning`.

### Semantic search `GET /products/semantic-search`
- Params: `q*` (2-200 chars), `categoryId` (subtree), `brandId`, `minPrice`/`maxPrice` (>= 0, min <= max),
  `inStock` (bool), repeated `option=Name:Value` (max 5; all on the same ACTIVE variant; case-insensitive),
  `limit` (1-50, default 10). Bad values -> 400.
- Response `{query, mode: HYBRID|KEYWORD_ONLY, embeddingModel, appliedFilters[], results[], warning}`;
  result `{product: ProductResponse, similarity|null (null for EXACT_SKU and KEYWORD),
  matchType: EXACT_SKU|SEMANTIC|KEYWORD|SEMANTIC_AND_KEYWORD,
  matchingVariants[{id, sku, variantName, price, compareAtPrice, stockQuantity}] (max 10)}`.
- Order: exact SKU, vector similarity (desc), keyword-only. Price filter = simple product price or a matching
  variant price; `inStock` = product stock > 0 (simple) or matching variant stock > 0. ACTIVE products only.
- Embedding provider failure -> 200 with `mode=KEYWORD_ONLY` and a `warning` (not a 5xx).

### Products `/products`
- Body `ProductRequest`: `name*, sku*, slug (max 200, lowercase-hyphen; generated from name when blank), description,
  categoryId | category*, brandId | brand, price, currency, stockQuantity, attributes, status, version,
  hasVariants, images[] (max 20, {id?, url* http/https, altText}), options[] (max 3, see below),
  variants[] (max 100, `VariantDraft`)`.
  - Category: `categoryId` or legacy `category` name (case-insensitive); must be assignable (it and all
    ancestors active) when newly assigned. Brand optional, same pattern.
  - Simple product (`hasVariants=false`): `price` and `stockQuantity` required; `options`/`variants` rejected.
  - Variant product: needs >= 1 option and >= 1 variant on create; submitted price/stock are ignored —
    `price` = lowest active variant price, `stockQuantity` = sum of active variant stock (kept in sync).
  - Create with variants is one transaction: product, images, options, variants all succeed or nothing is saved.
  - Update: omitted `images`/`options`/`hasVariants` are kept; `variants` only adds new variants (existing ones
    are edited via `/product-variants`). Turning a product with variants simple -> 409 `PRODUCT_HAS_VARIANTS`.
  - `attributes` validated against applicable definitions (keyed by slug), field `attributes.<slug>`; required
    active attributes enforced. Variant draft field errors use `variants[i].<field>`.
- Response adds `slug, hasVariants, images[{id,url,altText,position}], options[{id,name,attributeId,unit,position,
  values[{id,value,position}]}], variantSummary{count, activeCount, minPrice, maxPrice}` (null for simple products).
- List params: `q`, `category`, `categoryId` (includes subcategories), `brandId`, `status`, repeated
  `attr=slug:value` (max 10), `minPrice`/`maxPrice` (>= 0; matches the simple price or any active variant price),
  `stock=AVAILABLE|IN_STOCK|LOW_STOCK|OUT_OF_STOCK` (active-variant total for variant products), `hasVariants`,
  paging; sort `name|sku|slug|category|brand|price|stockQuantity|status|createdAt|updatedAt`.
- `GET /products/{id}/variants` -> list of `ProductVariantResponse`.
- `POST /products/{id}/variants` (admin) body `{variants: VariantDraft[1..100]}` -> 201 list; all or nothing.
- Codes: `SKU_ALREADY_EXISTS` (product or variant SKU), `SLUG_ALREADY_EXISTS`, `PRODUCT_HAS_VARIANTS`
  (delete or make simple), `CONCURRENT_MODIFICATION`, `OPTION_IN_USE` / `OPTION_VALUE_IN_USE` (removing an option
  or value still used by a variant).

### Options (part of the product body)
`{id?, name* (max 50), attributeId?, values* [1..50] {id?, value* (max 100)}}`. Max 3 options per product.
Existing options/values are matched by `id`, otherwise by text (case-insensitive). A linked SELECT attribute
restricts values to its options and canonicalises their casing; a NUMBER attribute's unit is appended to labels.

## Categories `/categories`
| Method | Path | Params / body |
|--------|------|---------------|
| GET | `/categories` | `q`, `status`, `parentId`, `rootsOnly`, paging; sort `name|slug|status|createdAt|updatedAt` (default `name,asc`) |
| GET | `/categories/options` | `includeInactive` -> `[{id,name,slug,parentId,path,status,assignable}]`; `assignable=false` when it or an ancestor is inactive |
| GET | `/categories/{id}` | |
| POST / PUT / PATCH | `/categories`, `/categories/{id}` | `{name*, slug, description, imageUrl, parentId, status, version}` |
| PATCH | `/categories/{id}/status` | |
| DELETE | `/categories/{id}` | 409 `CATEGORY_IN_USE` / `CATEGORY_HAS_SUBCATEGORIES` / `CATEGORY_HAS_ATTRIBUTES` |

Response: `id,name,slug,description,imageUrl,parentId,parentName,status,productCount,subcategoryCount,
attributeCount,version,createdAt,updatedAt`. Codes: `CATEGORY_ALREADY_EXISTS`, `SLUG_ALREADY_EXISTS`,
`parentId` field errors (self, missing, cycle, inactive or under an inactive ancestor). Deactivating a parent blocks
new assignments to its whole subtree; reactivating it makes active subcategories assignable again.

## Brands `/brands`
Same shape as categories without hierarchy. Body `{name*, slug, description, logoUrl, websiteUrl, status,
version}` (URLs http/https). List `q`, `status`. `GET /brands/options`. DELETE -> 409 `BRAND_IN_USE`.
Response includes `productCount`. Codes: `BRAND_ALREADY_EXISTS`, `SLUG_ALREADY_EXISTS`.

## Product attributes `/product-attributes` (no DELETE)
- GET list: `q`, `categoryId` (attributes assigned to that category), `dataType`, `status`, `required`,
  `filterable`, paging; sort `name|slug|dataType|status|createdAt|updatedAt`.
- GET `/product-attributes/applicable?categoryId=&includeInactive=` -> attributes of the category and its ancestors,
  root first.
- POST/PUT body `{categoryIds[] (1..20) | categoryId (legacy), name*, slug (max 64), dataType*
  (STRING|NUMBER|BOOLEAN|SELECT), options[], unit, required, filterable, status, version}`; SELECT needs 1-50
  distinct options, others none. Newly assigned categories must be assignable.
- Name (case-insensitive) and slug are globally unique. Codes: `ATTRIBUTE_ALREADY_EXISTS` (name),
  `SLUG_ALREADY_EXISTS` (slug), `ATTRIBUTE_NOT_FOUND`.
- Response: `id, categories[{id,name,active}], name, slug, dataType, options, unit, required, filterable, status,
  version, ...`.

## Product variants `/product-variants` (no DELETE)
Variants are deactivated (`PATCH /{id}/status`), never deleted.
- GET list: `q` (variant title, product name or SKU), `productId`, `sku`, `status`, `minPrice`, `maxPrice`,
  `stock=AVAILABLE|IN_STOCK|LOW_STOCK|OUT_OF_STOCK`, `categoryId` (includes subcategories), `brandId`, repeated
  `option=Name:Value` (max 5; name 1-50, value 1-100 chars, else 400 `INVALID_PARAMETER`), paging; sort
  `sku|variantName|productName|price|stockQuantity|status|createdAt|updatedAt` (default `createdAt,desc`).
- POST body `ProductVariantRequest {productId*, sku*, options{name: value} (max 3), attributes, price*,
  compareAtPrice, stockQuantity*, barcode, imageId, status, version}`; PUT/PATCH same body (product cannot change).
  `VariantDraft` (inside product/batch requests) is the same without `productId`/`version`, plus `imageUrl`
  (matched to one of the product's images).
- Rules: product must be a variant product (active when newly linked); options must match the product's options
  exactly (one value each); SKU unique across variants and products; barcode unique when set;
  `compareAtPrice > price`; stock 0..1e9; `imageId` must belong to the product. The variant title is generated
  from the option values (`Black / M`).
- Codes: `SKU_ALREADY_EXISTS`, `BARCODE_ALREADY_EXISTS`, `VARIANT_COMBINATION_EXISTS`, `CONCURRENT_MODIFICATION`.
- Response: `id, productId, productName, productSku, productStatus, categoryId, category, brandId, brand, currency,
  sku, variantName, options[{optionId,name,valueId,value,label}], attributes, price, compareAtPrice, onSale,
  stockQuantity, stockLevel, barcode, imageId, imageUrl, status, version, createdAt, updatedAt`
  (`LOW_STOCK` = 1..`app.products.low-stock-threshold`, default 5).
