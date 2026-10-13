# Vector synchronization

## How a product becomes searchable

```
product row ──(same DB transaction)──▶ outbox event (PENDING)
                                            │ claimed by worker (FOR UPDATE SKIP LOCKED, lease)
                                            ▼
                     read current product ─▶ build embedding text ─▶ SHA-256 hash
                                            │ hash + model unchanged? ─▶ mark current, COMPLETED (UNCHANGED)
                                            ▼
                     call embedding API (no DB transaction open)
                                            ▼
                     upsert product_embeddings (guarded by source_version) ─▶ COMPLETED (EMBEDDED)
```

Relationship between the pieces:

- **Product record** (`products`) — the source of truth for every fact shown to users.
- **Outbox event** (`vector_sync_events`) — a durable "this product changed" note written atomically with the change.
- **Embedding text** — built by `EmbeddingContentBuilder` from the product (see below), stored in `product_embeddings.content`.
- **Vector** — the embedding of that text, stored next to it with the model name and content hash.
- **Similarity search** — pgvector cosine distance (`<=>`) over vectors of *active* products.
- **Context** — the matching products re-read from `products`, formatted as untrusted data for the LLM.
- **Response** — the LLM's answer plus the source products it was given.

## Events

| Trigger | Event type |
|---------|------------|
| Create | `PRODUCT_CREATED` |
| Update | `PRODUCT_UPDATED` (+ `PRODUCT_DEACTIVATED`/`PRODUCT_REACTIVATED` if the status changed) |
| Status change | `PRODUCT_DEACTIVATED` / `PRODUCT_REACTIVATED` |
| Delete | `PRODUCT_DELETED` (payload keeps id, SKU, name, status, version) |
| Admin "Index missing products" | `PRODUCT_RESYNC_REQUESTED` |

Processing is **state-based**: whatever the event type, the worker reconciles the vector with the product's
*current* row. If the product no longer exists, its vectors are deleted (`VECTORS_DELETED`). This makes processing
idempotent and order-independent — re-processing an event or processing an older event after a newer one is a no-op.

## Which fields affect the embedding

Embedded: **name, SKU, category, brand, description (≤ 6000 chars), attributes** (sorted by key; values ≤ 300 chars).
Not embedded: **price, currency, stock, status, timestamps** — these change often and must always be read live from
the database. Changing only price or stock therefore produces an `UNCHANGED` result with **no embedding API call**.
Deactivated products keep their vector but are filtered out at query time (`p.status = 'ACTIVE'`), so reactivation
is instant and free.

## Status lifecycle

`PENDING → PROCESSING → COMPLETED`, or `PROCESSING → FAILED`.

- **Transient failure** (timeout, network, HTTP 429/5xx, transient DB error) with attempts left: `FAILED` with
  `retryable = true` and `next_attempt_at` = now + backoff. The worker claims it again when due.
- **Permanent failure** (provider rejected the request, account quota/credits exhausted, wrong vector dimension,
  empty content) or attempts exhausted:
  `FAILED` with `retryable = false`. It is never retried automatically; the UI shows "Failed" with a **Retry now**
  button (`POST /api/v1/admin/vector-sync/retry/{eventId}` resets attempts) and "Retry all failed".
- **Backoff**: `initial × 2^(attempt−1)`, capped at `VECTOR_SYNC_MAX_BACKOFF`, with ±20 % jitter.
  Defaults: 10 s, 20 s, 40 s, … up to 15 min, at most 6 attempts.
- **Stale leases**: if a worker dies mid-event, a recovery job (every minute) re-queues `PROCESSING` rows whose lease
  is older than `VECTOR_SYNC_STALE_TIMEOUT` (default 5 min), or fails them if attempts are exhausted.
- **No API key**: while `OPENAI_API_KEY` is not configured nothing is claimed, so events wait as `PENDING` without
  consuming attempts.
- Error messages stored in `last_error` are truncated and redacted (API keys and bearer tokens removed).

## Triggering and restarts

Events are processed (a) immediately after the product transaction commits (async, best effort) and (b) by a
scheduled poll every `VECTOR_SYNC_POLL_INTERVAL`. Because events are rows in PostgreSQL, nothing is lost on restart
or crash. Multiple backend instances can run the worker concurrently thanks to `SKIP LOCKED` claiming and the
one-event-per-product rule.

## Consistency model

The vector index is **eventually consistent** with the catalog (normally seconds; longer while the AI provider is
unavailable). Correctness does not depend on it: answers use live product rows, inactive/deleted products are
excluded immediately, and exact price/stock/SKU questions bypass vectors entirely. The worst case of a stale vector
is slightly less relevant ranking for a just-edited product's description.

## Monitoring

- `GET /api/v1/admin/vector-sync/status` — counts by status, oldest pending, products without embeddings, recent failures.
- `GET /api/v1/admin/vector-sync/events?status=FAILED&productId=…` — paged event history.
- Micrometer gauge `vector.sync.events{status=PENDING|PROCESSING|COMPLETED|FAILED}` via
  `/actuator/metrics/vector.sync.events?tag=status:FAILED` (admin only) — alert when pending or failed counts grow.
- Product list shows each product's sync badge (Synced / Pending / Retrying / Failed / Not synced).

## Indexing (HNSW)

No approximate index is created initially: for small catalogs an exact scan is fast and gives perfect recall. Once
you have tens of thousands of products, add an HNSW index in a new migration (pgvector supports HNSW up to 2000
dimensions for `vector`):

```sql
-- V2__hnsw_index.sql  (use CONCURRENTLY outside a transaction for large tables)
CREATE INDEX ix_product_embeddings_hnsw ON product_embeddings
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
-- Optional, per session/transaction: SET hnsw.ef_search = 100;  (recall vs speed)
```

The search query already orders by `embedding <=> :query` with cosine distance, so the index is used automatically.
Note that filtered queries (status/price filters) may return fewer than `LIMIT` rows with HNSW; pgvector 0.8+ offers
`hnsw.iterative_scan` to mitigate this.

## Changing the embedding model or dimension

The vector column is `vector(EMBEDDING_DIMENSIONS)` and the backend refuses to start if configuration and column
disagree. To switch models:

1. Choose the new model and its dimension (e.g. `text-embedding-3-large` = 3072, or request fewer dimensions).
2. Add a migration that drops dependent ANN indexes and changes the column, clearing old vectors:
   ```sql
   DELETE FROM product_embeddings;
   ALTER TABLE product_embeddings ALTER COLUMN embedding TYPE vector(3072);
   ```
   `V2__align_embedding_dimension.sql` already does this once (conditionally) for databases created with a
   different dimension, e.g. OpenAI 1536 → Ollama `nomic-embed-text` 768; later changes need a new migration.
3. Update `OPENAI_EMBEDDING_MODEL` and `EMBEDDING_DIMENSIONS`, restart.
4. Click **Index missing products** (or `POST /api/v1/admin/vector-sync/resync-missing`) to re-embed everything.

Even without a dimension change, a model change is detected per row (`embedding_model` differs) and triggers a fresh
embedding the next time the product is processed; use "Index missing products" to cover all products.

## Future: multiple chunks per product

The schema already has `chunk_index`. To support long documents (manuals, reviews) per product: replace the unique
`(product_id)` constraint with `(product_id, chunk_index)`, make the builder return several chunks, upsert all chunks
and delete surplus ones in the same statement batch, and aggregate search results per product (e.g. best chunk score).
