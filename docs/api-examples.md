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
| 502/503 | `AI_PROVIDER_ERROR`, `AI_PROVIDER_QUOTA_EXCEEDED`, `AI_PROVIDER_EMPTY_RESPONSE`, `AI_PROVIDER_UNAVAILABLE`, `AI_PROVIDER_NOT_CONFIGURED` |

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

Rules: SKU 1–64 chars `[A-Za-z0-9._-]` (stored upper-case, unique), price ≥ 0 with ≤ 2 decimals, currency 3 letters
(default `INR`), stock 0…1 000 000 000, ≤ 50 attributes with string (≤ 500 chars) / number / boolean / list values.

### `GET /products`

Query parameters: `q` (name/SKU/category/brand, case-insensitive), `category`, `status` (`ACTIVE`/`INACTIVE`),
`page` (0-based), `size` (1–100, default 20), `sort` = `field,asc|desc` where field ∈ `name, sku, category, brand,
price, stockQuantity, status, createdAt, updatedAt`.

```bash
curl -s "http://localhost:8080/api/v1/products?q=head&category=Audio&status=ACTIVE&page=0&size=20&sort=price,asc" -H "$AUTH"
```

```json
{ "content": [ { "id": "…", "name": "…", "…": "…" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true }
```

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
  "recentFailures": [ { "id": "…", "productId": "…", "productName": "…", "productSku": "…",
                        "eventType": "PRODUCT_UPDATED", "status": "FAILED", "attempts": 6, "maxAttempts": 6,
                        "retryable": false, "lastError": "Retry attempts exhausted. Last error: …", "…": "…" } ] }
```

### `GET /admin/vector-sync/events?status=FAILED&productId=<uuid>&page=0&size=20`

Paged event history.

### `POST /admin/vector-sync/retry/{eventId}` → `202 Accepted`

Resets a `FAILED` event to `PENDING` with zero attempts and returns the event.

### `POST /admin/vector-sync/retry-failed` → `202` `{ "requeued": 3 }`

### `POST /admin/vector-sync/resync-missing` → `202` `{ "enqueued": 5 }`

Queues active products that have no embedding for the current model (e.g. after a model change).

## Operations

- `GET /actuator/health` — public liveness/readiness (details for admins only).
- `GET /actuator/metrics/vector.sync.events?tag=status:FAILED` — admin only.
