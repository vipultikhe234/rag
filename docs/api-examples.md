# API examples

Base URL: `http://localhost:8080/api/v1`. All endpoints except login require `Authorization: Bearer <token>`.
Errors always use this shape (fields may be omitted when empty):

```json
{ "timestamp": "2026-10-13T06:00:00Z", "status": 400, "error": "Bad Request", "code": "VALIDATION_FAILED",
  "message": "Request validation failed", "path": "/api/v1/products",
  "fieldErrors": [{ "field": "price", "message": "Price must not be negative" }] }
```

| Status | Typical codes |
|--------|---------------|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `INVALID_QUESTION` |
| 401 | `UNAUTHORIZED` (missing/invalid/expired token), `INVALID_CREDENTIALS` |
| 403 | `FORBIDDEN` (role not allowed) |
| 404 | `PRODUCT_NOT_FOUND`, `CONVERSATION_NOT_FOUND`, `NOT_FOUND` |
| 409 | `SKU_ALREADY_EXISTS`, `CONCURRENT_MODIFICATION` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 429 | `RATE_LIMITED` (with `Retry-After`) |
| 502/503 | `AI_PROVIDER_ERROR`, `AI_PROVIDER_QUOTA_EXCEEDED`, `AI_PROVIDER_EMPTY_RESPONSE`, `AI_PROVIDER_UNAVAILABLE`, `AI_PROVIDER_UNREACHABLE`, `AI_PROVIDER_NOT_CONFIGURED` |

The examples use bash + curl; in PowerShell use `curl.exe` or `Invoke-RestMethod`.

## Authentication

### `POST /auth/login`

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"<password>"}'
```

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9…", "tokenType": "Bearer", "expiresAt": "2026-10-13T14:00:00Z",
  "user": { "id": "…", "email": "admin@example.com", "displayName": "Administrator", "role": "ADMIN" } }
```

Wrong email or password both return `401 INVALID_CREDENTIALS`. Limited to `RATE_LIMIT_LOGIN_PER_MINUTE` attempts per
client IP and email.

```bash
TOKEN=<accessToken>
AUTH="Authorization: Bearer $TOKEN"
```

### `GET /auth/me`

```bash
curl -s http://localhost:8080/api/v1/auth/me -H "$AUTH"
```

## Products

Reads require any authenticated user; writes require `ADMIN`.

### `POST /products` → `201 Created` + `Location`

```bash
curl -s -X POST http://localhost:8080/api/v1/products -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "name": "Wireless Noise-Cancelling Headphones",
  "sku": "WH-001",
  "description": "Over-ear Bluetooth headphones with ANC and 30-hour battery life.",
  "category": "Audio",
  "brand": "SoundWave",
  "price": 7999.00,
  "currency": "INR",
  "stockQuantity": 25,
  "attributes": { "color": "black", "battery_life": "30 hours", "connectivity": ["Bluetooth 5.3", "3.5mm"] }
}'
```

```json
{ "id": "6f1c…", "name": "Wireless Noise-Cancelling Headphones", "sku": "WH-001", "description": "…",
  "category": "Audio", "brand": "SoundWave", "price": 7999.00, "currency": "INR", "stockQuantity": 25,
  "attributes": { "color": "black", "battery_life": "30 hours", "connectivity": ["Bluetooth 5.3", "3.5mm"] },
  "status": "ACTIVE", "version": 0, "createdAt": "…", "updatedAt": "…",
  "vectorSync": { "state": "PENDING", "lastEventId": "…", "lastEventType": "PRODUCT_CREATED",
                  "lastEventStatus": "PENDING", "lastError": null, "autoRetryScheduled": false,
                  "nextAttemptAt": null, "embeddingModel": null, "embeddedAt": null } }
```

Rules: SKU 1–64 chars `[A-Za-z0-9._-]` (stored upper-case, unique across products **and** variants), price ≥ 0
with ≤ 2 decimals, currency 3 letters (default `INR`), stock 0…1 000 000 000, ≤ 50 attributes with string
(≤ 500 chars) / number / boolean / list values.

Category and brand: send `categoryId` (UUID, preferred) or the legacy `category` name, which must match an existing
category (case-insensitive); one of them is required. `brandId` / `brand` are optional and work the same way.
Inactive categories and brands cannot be newly assigned (`400`, field error on `categoryId` / `brandId`), but a
product keeps them when they are unchanged. The response includes `categoryId` and `brandId` next to the names.

Attribute values whose key is the slug of an attribute defined for the product's category (or a parent category)
are type-checked: NUMBER needs a number, BOOLEAN `true`/`false`, SELECT one of the options. Required attributes must
be present. Errors use the field `attributes.<slug>`. Other keys stay free-form.

```bash
curl -s -X POST http://localhost:8080/api/v1/products -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "name": "Samsung Galaxy S24", "sku": "SAM-GS24", "description": "Flagship Android phone …",
  "categoryId": "<mobile-phones id>", "brandId": "<samsung id>", "price": 74999, "currency": "INR",
  "stockQuantity": 40,
  "attributes": { "ram": 8, "storage-capacity": 256, "network-support": "5G", "operating-system": "Android" }
}'
```

`DELETE /products/{id}` returns `409 PRODUCT_HAS_VARIANTS` while variants exist; deactivate the product instead.

### `GET /products`

Query parameters: `q` (name/SKU/category/brand, case-insensitive), `categoryId` (also matches subcategories),
`brandId`, repeated `attr=slug:value` (up to 10, case-insensitive exact match on the attribute value), `category`
(legacy name filter), `status` (`ACTIVE`/`INACTIVE`), `page` (0-based), `size` (1–100, default 20), `sort` =
`field,asc|desc` where field ∈ `name, sku, category, brand, price, stockQuantity, status, createdAt, updatedAt`.

```bash
curl -s "http://localhost:8080/api/v1/products?q=head&status=ACTIVE&page=0&size=20&sort=price,asc" -H "$AUTH"
curl -s "http://localhost:8080/api/v1/products?categoryId=<electronics id>&attr=ram:16&attr=storage-capacity:512" -H "$AUTH"
```

```json
{ "content": [ { "id": "…", "name": "…", "…": "…" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true }
```

### `GET /products/semantic-search`

Natural-language product search (any signed-in user). Parameters: `q` (2–200 characters, required), `categoryId`
(includes subcategories), `brandId`, `minPrice`, `maxPrice`, `inStock=true`, repeated `option=Name:Value` (up to 5,
all on the same ACTIVE variant, case-insensitive), `limit` (1–50, default 10).

Ranking: exact product/variant SKU matches first, then vector matches by cosine similarity (ACTIVE products, current
embedding model, similarity ≥ `RAG_SIMILARITY_THRESHOLD`, 100 candidates), then keyword matches (name/SKU/brand/
category contains `q`). Every candidate is then checked against the filters in SQL; prices and stock come from the
product and variant rows. If the embedding provider is down, `mode` is `KEYWORD_ONLY` and `warning` explains why.

```bash
curl -s -G "http://localhost:8080/api/v1/products/semantic-search" -H "$AUTH" \
  --data-urlencode "q=laptop" --data-urlencode "maxPrice=60000" --data-urlencode "inStock=true" \
  --data-urlencode "limit=5"
curl -s -G "http://localhost:8080/api/v1/products/semantic-search" -H "$AUTH" \
  --data-urlencode "q=tshirt" --data-urlencode "option=Color:Black"
```

```json
{ "query": "laptop", "mode": "HYBRID", "embeddingModel": "nomic-embed-text",
  "appliedFilters": ["status = ACTIVE", "price <= 60000", "in stock"],
  "results": [
    { "product": { "sku": "HP-PAV14", "name": "HP Pavilion 14 Laptop", "price": 52999.00, "…": "…" },
      "similarity": 0.696, "matchType": "SEMANTIC_AND_KEYWORD",
      "matchingVariants": [ { "id": "…", "sku": "HP-PAV14-I5-8-512", "variantName": "…", "price": 52999.00,
                              "compareAtPrice": null, "stockQuantity": 6 } ] } ],
  "warning": null }
```

`matchType`: `EXACT_SKU`, `SEMANTIC`, `KEYWORD` or `SEMANTIC_AND_KEYWORD`; `similarity` is null for non-vector
matches. Errors: `400` for a missing/too short/too long `q`, `limit` out of range, negative or reversed prices, or a
malformed `option`; `401` without a token.

### `GET /products/categories`

Distinct categories, for filter dropdowns.

### `GET /products/{id}`

`404 PRODUCT_NOT_FOUND` if missing.

### `PUT /products/{id}`

Full update, same body as create. Include `"version"` from the last read to get optimistic-locking protection
(`409 CONCURRENT_MODIFICATION` if someone else saved first). `status` is optional and unchanged when omitted.

### `PATCH /products/{id}/status`

```bash
curl -s -X PATCH http://localhost:8080/api/v1/products/<id>/status -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"status":"INACTIVE"}'
```

Inactive products disappear from chat answers immediately.

### `DELETE /products/{id}` → `204 No Content`

Removes the product and its vectors (cascade); a `PRODUCT_DELETED` outbox event records the cleanup.

## Catalog: categories, brands, attributes, variants

Reads require any authenticated user; writes require `ADMIN`. All four resources share these conventions:

- `GET /{resource}` lists with `q`, `status`, `page`, `size` (1–100) and `sort` (allow-listed fields, below).
- `GET /{resource}/{id}`; `POST /{resource}` → `201` + `Location`.
- Update with `PUT` **or** `PATCH /{resource}/{id}`. Both take the full body; include `"version"` from the last
  read, or the server answers `409 CONCURRENT_MODIFICATION`.
- `PATCH /{resource}/{id}/status` with `{"status":"ACTIVE"|"INACTIVE"}`. Deactivation is the normal way to retire a
  record. Only unused categories and brands can be deleted (`DELETE`); attributes and variants have no delete.
- Blank `slug` is derived from the name (`Men's Clothing` → `mens-clothing`).
- `GET /{resource}/semantic-search?q=…&limit=10` (`q` 2–200 chars, `limit` 1–50) ranks ACTIVE records by meaning:

```http
GET /api/v1/brands/semantic-search?q=gaming%20laptops&limit=3
```

```json
{ "query": "gaming laptops", "entityType": "BRAND", "mode": "HYBRID", "embeddingModel": "nomic-embed-text",
  "results": [ { "item": { "id": "…", "name": "Lenovo", "productCount": 0, "…": "…" },
                 "similarity": 0.694, "matchType": "SEMANTIC" } ],
  "warning": null }
```

  `item` is the same object `GET /{resource}/{id}` returns. Name matches come first (`NAME` / `SEMANTIC_AND_NAME`);
  if the embedding service is down the response is `mode: "KEYWORD_ONLY"` with a `warning` and name matches only.

### Categories `/categories`

```bash
curl -s -X POST http://localhost:8080/api/v1/categories -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Mobile Phones","parentId":"<electronics id>","description":"Smartphones and feature phones"}'

curl -s "http://localhost:8080/api/v1/categories?q=cloth&status=ACTIVE&sort=name,asc" -H "$AUTH"
curl -s "http://localhost:8080/api/v1/categories?rootsOnly=true" -H "$AUTH"         # top level only
curl -s "http://localhost:8080/api/v1/categories?parentId=<fashion id>" -H "$AUTH"  # direct children
curl -s "http://localhost:8080/api/v1/categories/options?includeInactive=false" -H "$AUTH"
```

Response fields: `id, name, slug, description, imageUrl, parentId, parentName, status, productCount,
subcategoryCount, attributeCount, version, createdAt, updatedAt`. `options` returns `[{id, name, slug, parentId,
path: "Fashion / Footwear", status, assignable}]` for dropdowns (`assignable` is false when the category or any
parent is inactive). Sort: `name, slug, status, createdAt, updatedAt`.
Errors: `409 CATEGORY_ALREADY_EXISTS`, `409 SLUG_ALREADY_EXISTS`, `400` on `parentId` (itself, a descendant, missing,
inactive or under an inactive parent). `DELETE` → `409 CATEGORY_IN_USE` / `CATEGORY_HAS_SUBCATEGORIES` / `CATEGORY_HAS_ATTRIBUTES`.

### Brands `/brands`

```bash
curl -s -X POST http://localhost:8080/api/v1/brands -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Havells","description":"Electrical goods and appliances","websiteUrl":"https://www.havells.com"}'
curl -s "http://localhost:8080/api/v1/brands?q=so&status=ACTIVE" -H "$AUTH"
curl -s -X PATCH http://localhost:8080/api/v1/brands/<id>/status -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"status":"INACTIVE"}'
```

Body: `name*`, `slug`, `description`, `logoUrl`, `websiteUrl` (absolute http/https URLs), `status`, `version`.
The response adds `productCount`. Errors: `409 BRAND_ALREADY_EXISTS`, `409 SLUG_ALREADY_EXISTS`, `DELETE` →
`409 BRAND_IN_USE`.

### Product attributes `/product-attributes`

```bash
curl -s -X POST http://localhost:8080/api/v1/product-attributes -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "categoryIds": ["<footwear id>"], "name": "Shoe Size", "dataType": "SELECT",
  "options": ["UK 6", "UK 7", "UK 8", "UK 9", "UK 10"], "required": true, "filterable": true }'

curl -s "http://localhost:8080/api/v1/product-attributes?categoryId=<id>&dataType=SELECT&required=true&filterable=true" -H "$AUTH"
curl -s "http://localhost:8080/api/v1/product-attributes/applicable?categoryId=<laptops id>" -H "$AUTH"
```

`categoryIds` assigns the attribute to 1–20 categories (the older single `categoryId` is still accepted); it applies
to those categories and their subcategories. `dataType` ∈ `STRING, NUMBER, BOOLEAN, SELECT`; SELECT needs 1–50
distinct options, other types none. `unit` ≤ 20 chars. `applicable` returns the attributes of the category and its
parents (root first; `includeInactive=true` adds inactive ones). The response lists
`categories: [{id, name, active}]`. Sort: `name, slug, dataType, status, createdAt, updatedAt`.
Errors: `409 ATTRIBUTE_ALREADY_EXISTS` (same name, case-insensitive, anywhere), `409 SLUG_ALREADY_EXISTS` (same
slug anywhere), `400` on `categoryIds` when a newly assigned category is inactive or under an inactive parent.
Deactivated attributes keep existing product values, which can no longer be changed.

### Product variants `/product-variants`

```bash
curl -s -X POST http://localhost:8080/api/v1/product-variants -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "productId": "<SAM-GS24 id>", "sku": "SAM-GS24-256-BLK",
  "options": { "Color": "Black", "Storage Capacity": "256" },
  "price": 74999, "compareAtPrice": 79999, "stockQuantity": 12, "barcode": "8806095299999" }'

curl -s "http://localhost:8080/api/v1/product-variants?productId=<id>&stock=LOW_STOCK&minPrice=1000&maxPrice=90000&sort=price,asc" -H "$AUTH"
curl -s "http://localhost:8080/api/v1/product-variants?categoryId=<electronics id>&option=Color:Black&option=Storage%20Capacity:256" -H "$AUTH"
```

The product must be a variant product with these options; `options` picks one existing value per option (add new
values on the product first). The title (`Black / 256 GB`) is generated. `stock` ∈ `AVAILABLE` (> 0), `IN_STOCK`,
`LOW_STOCK` (1–`app.products.low-stock-threshold`, default 5), `OUT_OF_STOCK`. Other filters: `q`, `sku`, `status`,
`brandId`, `categoryId` (includes subcategories), repeated `option=Name:Value` (max 5). The response adds
`productName, productSku, productStatus, category, brand, currency, variantName, options[{name, value, label}],
compareAtPrice, onSale, stockLevel, barcode, imageUrl`. Sort: `sku, variantName, productName, price,
stockQuantity, status, createdAt, updatedAt`. Rules: `compareAtPrice > price`; SKU unique across variants and
products (`409 SKU_ALREADY_EXISTS`); barcode unique (`409 BARCODE_ALREADY_EXISTS`); one variant per combination
(`409 VARIANT_COMBINATION_EXISTS`). Variants are deactivated, never deleted.

### Product with variants in one request

```bash
curl -s -X POST http://localhost:8080/api/v1/products -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "name": "Classic Hoodie", "sku": "BC-HOODIE", "description": "Cotton fleece hoodie",
  "categoryId": "<mens clothing id>", "brandId": "<brand id>", "currency": "INR", "hasVariants": true,
  "images": [{ "url": "https://example.com/hoodie-black.jpg", "altText": "Black hoodie" }],
  "options": [{ "name": "Color", "values": [{ "value": "Black" }, { "value": "Grey" }] },
              { "name": "Size", "values": [{ "value": "M" }, { "value": "L" }] }],
  "variants": [
    { "sku": "BC-HOODIE-BLACK-M", "options": { "Color": "Black", "Size": "M" }, "price": 2499,
      "compareAtPrice": 2999, "stockQuantity": 10, "imageUrl": "https://example.com/hoodie-black.jpg" },
    { "sku": "BC-HOODIE-GREY-L", "options": { "Color": "Grey", "Size": "L" }, "price": 2499, "stockQuantity": 0 } ] }'

curl -s -X POST http://localhost:8080/api/v1/products/<id>/variants -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"variants":[{"sku":"BC-HOODIE-GREY-M","options":{"Color":"Grey","Size":"M"},"price":2499,"stockQuantity":4}]}'
curl -s "http://localhost:8080/api/v1/products?hasVariants=true&minPrice=1000&maxPrice=3000&stock=IN_STOCK" -H "$AUTH"
```

Everything is saved in one transaction or not at all; errors point at fields such as `variants[1].sku`. For variant
products `price` is the lowest active variant price and `stockQuantity` the active total; the response adds `slug`,
`hasVariants`, `images`, `options` and `variantSummary {count, activeCount, minPrice, maxPrice}`. A product with
variants cannot be deleted or turned back into a simple product (`409 PRODUCT_HAS_VARIANTS`).

## Chat

### `POST /chat`

```bash
curl -s -X POST http://localhost:8080/api/v1/chat -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"question":"Which products are good for travel?","conversationId":null}'
```

```json
{ "conversationId": "c0a8…", "messageId": "…",
  "answer": "- Cabin Trolley Bag (SKU BAG-CABIN-55): …\n- Power Bank 20000 mAh (SKU PB-20K): …",
  "sources": [
    { "productId": "…", "name": "Cabin Trolley Bag", "sku": "BAG-CABIN-55", "category": "Travel",
      "brand": "TrailMate", "price": 5999.00, "currency": "INR", "stockQuantity": 18, "status": "ACTIVE",
      "relevanceScore": 0.5412, "matchType": "SEMANTIC" } ],
  "retrieval": { "strategy": "SEMANTIC", "appliedFilters": ["status = ACTIVE"], "totalMatches": null, "llmUsed": true },
  "createdAt": "…" }
```

Exact question example — `{"question":"Which products cost less than ₹1,000?"}` returns
`"strategy": "STRUCTURED"`, `"appliedFilters": ["status = ACTIVE", "price < 1000.00", "currency = INR"]`,
`"totalMatches": 3`, and sources with `"matchType": "STRUCTURED_FILTER"` and `"relevanceScore": null`.

Ambiguous example — `{"question":"Which products are under 1000?"}` returns `"strategy": "CLARIFICATION"`,
`"llmUsed": false` and asks whether you mean price or stock.

Question length ≤ 1000 characters; `RATE_LIMIT_CHAT_PER_MINUTE` per user.

### `POST /chat/stream` (Server-Sent Events)

Same body as `POST /chat`; the answer arrives as it is generated. The web UI uses this endpoint.

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream -H "$AUTH" -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' -d '{"question":"Which headphones are good for travel?"}'
```

```text
event:meta
data:{"conversationId":"…","sources":[…],"retrieval":{…}}

event:delta
data:{"text":"The "}

event:delta
data:{"text":"Noise-Cancelling Headphones"}

event:done
data:{ …same shape as the POST /chat response… }
```

- `meta` comes first, once retrieval finishes; `delta` events carry answer text; the stream ends with exactly one
  `done` or `error` (`{"code":"AI_PROVIDER_UNAVAILABLE","message":"…"}`).
- Validation, rate-limit and ownership failures happen before streaming starts and are returned as normal JSON
  errors (`400`, `429`, `404`), which is why the `Accept` header lists `application/json` too.
- The conversation and both messages are saved when the answer completes. If the client disconnects, generation is
  cancelled and the partial answer received so far is saved.

### `PATCH /chat/conversations/{id}`

`{"title":"Travel gear"}` (1–200 characters) → the updated `{ "id", "title", "createdAt", "updatedAt" }`.

### `GET /chat/conversations?page=0&size=20`

The caller's conversations, most recently updated first: `{ "content": [{ "id", "title", "createdAt", "updatedAt" }], … }`.

### `GET /chat/conversations/{id}/messages`

Messages in order: `[{ "id", "role": "USER" | "ASSISTANT", "content", "sources", "retrieval", "createdAt" }]`.
Another user's conversation returns `404 CONVERSATION_NOT_FOUND`.

### `DELETE /chat/conversations/{id}` → `204`

## Vector sync administration (`ADMIN` only)

### `GET /admin/vector-sync/status`

```json
{ "aiProviderConfigured": true, "workerEnabled": true, "embeddingModel": "text-embedding-3-small",
  "embeddingDimensions": 1536, "pending": 0, "processing": 0, "completed": 42, "failed": 1,
  "failedRetryable": 0, "failedPermanent": 1, "oldestPendingAt": null, "embeddedProducts": 12,
  "activeProductsWithoutEmbedding": 0,
  "catalogIndex": [ { "entityType": "BRAND", "embedded": 38, "activeWithoutEmbedding": 0 }, "…CATEGORY, ATTRIBUTE, VARIANT" ],
  "recentFailures": [ { "id": "…", "aggregateType": "PRODUCT", "productId": "…", "productName": "…", "productSku": "…",
                        "eventType": "PRODUCT_UPDATED", "status": "FAILED", "attempts": 6, "maxAttempts": 6,
                        "retryable": false, "lastError": "Retry attempts exhausted. Last error: …", "…": "…" } ] }
```

### `GET /admin/vector-sync/events?status=FAILED&productId=<uuid>&page=0&size=20`

Paged event history.

### `POST /admin/vector-sync/retry/{eventId}` → `202 Accepted`

Resets a `FAILED` event to `PENDING` with zero attempts and returns the event.

### `POST /admin/vector-sync/retry-failed` → `202` `{ "requeued": 3 }`

### `POST /admin/vector-sync/resync-missing` → `202` `{ "enqueued": 5, "catalogEnqueued": 130 }`

Queues active products (`enqueued`) and active brands, categories, attributes and variants (`catalogEnqueued`) that
have no embedding for the current model (e.g. after a model change or right after upgrading to V5).

### `POST /admin/vector-sync/refresh-stale` → `202`

```json
{ "checked": 31, "stale": 0, "enqueued": 0,
  "catalog": { "checked": 130, "stale": 0, "enqueued": 0,
               "byType": { "BRAND": { "checked": 38, "stale": 0, "enqueued": 0 }, "…": "…" } } }
```

Rebuilds the search document of every ACTIVE product and catalog entity (keyset batches of 200), compares hash and
model with the stored embedding and queues a re-sync for the differences. Idempotent and resumable; deletes nothing.

## Operations

- `GET /actuator/health` — public liveness/readiness (details for admins only).
- `GET /actuator/metrics/vector.sync.events?tag=status:FAILED` — admin only.
