# Architecture

## Overview

```mermaid
flowchart LR
  UI[React admin UI<br/>Vite dev server / static build] -- "/api/v1 (JWT)" --> API[Spring Boot backend]
  API -- JPA / JDBC --> PG[(PostgreSQL + pgvector)]
  API -- embeddings / chat --> OAI[OpenAI API]
  subgraph PG_TABLES [One PostgreSQL database]
    P[products] --- E[product_embeddings<br/>vector(N)]
    P -. same transaction .- O[vector_sync_events<br/>outbox]
    U[app_users] --- C[chat_conversations] --- M[chat_messages]
  end
```

The browser only talks to the backend. The OpenAI key, database credentials and JWT secret exist only in the
backend's environment. There is a single datastore: product rows, their vectors, the outbox and chat history are
all in one PostgreSQL database, so product changes and outbox events commit atomically.

## Backend layers

Each feature package follows `controller → service → repository/store`, with constructor injection and DTO records
at the API boundary (entities are never returned from controllers).

| Package | Responsibility |
|---------|----------------|
| `auth` | `AppUser`, BCrypt login with timing-safe dummy hash, `JwtService` (HS256, issuer, expiry), `JwtAuthenticationFilter`, `AdminBootstrap`, JSON 401/403 handlers |
| `product` | Entity with `@Version`, JSONB attributes, validation (`ProductRequest`, `@ValidAttributes`), `ProductSpecifications` for search/filters, `filter/` allow-listed structured filters used by RAG |
| `vectorsync` | Outbox writer (`VectorSyncOutbox`), JDBC `OutboxStore` (claiming, leases, retries), `VectorSyncProcessor`, `ProductEmbeddingSynchronizer`, scheduler + after-commit trigger, admin API, metrics |
| `embedding` | `EmbeddingContentBuilder` (what gets embedded + SHA-256 hash), `JdbcProductEmbeddingStore` (pgvector SQL), startup dimension check |
| `ai` | Thin wrappers around Spring AI `EmbeddingModel`/`ChatModel`, provider status, error translation, HTTP timeouts |
| `rag` | `QuestionNormalizer`, `QueryIntentParser`, `ProductRetriever` (strategies), `PromptBuilder`, `RagService` |
| `chat` | Conversations/messages persisted per user, ownership checks, rate limiting |
| `common` | `ApiError` format, `GlobalExceptionHandler`, `PageResponse`, `RequestSizeLimitFilter`, `RateLimiter` |
| `config` | `AppProperties` (typed, validated configuration), security, async/scheduling, AI HTTP client |

## Request lifecycle

1. `RequestSizeLimitFilter` rejects bodies over `app.http.max-request-size` with `413`.
2. `JwtAuthenticationFilter` validates the bearer token and re-loads the user (deleted users lose access at once).
3. URL rules in `SecurityConfig` plus `@PreAuthorize` on write endpoints enforce roles.
4. Controllers validate DTOs (`@Valid`); `GlobalExceptionHandler` maps every error to the uniform `ApiError` body
   (`code`, `message`, optional `fieldErrors`) without stack traces.

## Data model

| Table | Notes |
|-------|-------|
| `products` | UUID id, unique `sku` (stored upper-case), checks for non-negative price/stock, 3-letter currency, status `ACTIVE/INACTIVE`, JSONB `attributes`, `version` for optimistic locking; B-tree indexes on status/category/created_at, partial indexes for active price/stock, trigram GIN indexes for search |
| `product_embeddings` | `product_id` FK `ON DELETE CASCADE`, `chunk_index` (0 today, ready for multi-chunk), `content`, `embedding vector(N)`, `content_hash`, `embedding_model`, `source_version`, unique `(product_id)` |
| `vector_sync_events` | Outbox: event type, status, attempts/max, `retryable`, `next_attempt_at`, lease (`locked_by`, `locked_at`), `last_error`, `result`, JSON payload (id, SKU, name kept for deleted products). No FK to `products`, so delete events survive |
| `app_users` | Lower-case unique email, BCrypt hash, role |
| `chat_conversations`, `chat_messages` | Owned by a user; messages store `sources` and `retrieval` as JSONB snapshots |

## Concurrency and consistency

- Product edits use optimistic locking (`version`); a stale update returns `409 CONCURRENT_MODIFICATION`.
- The outbox worker claims rows with `FOR UPDATE SKIP LOCKED` and never processes two events for the same product
  at once; vector upserts are guarded by `source_version` so an older product state never overwrites a newer one.
- Search answers are built from the authoritative `products` rows (status re-checked), so the vector index being a
  few seconds behind never exposes inactive or deleted products or stale prices.

## Frontend

`src/api` (Axios client, typed endpoints, error normalization), `src/auth` (session in `sessionStorage`, provider,
protected routes), `src/components` (layout, toasts, accessible form fields, dialog, pagination, states),
`src/features` (product form schema/attributes editor/sync panel, chat bubbles/source cards/input) and `src/pages`.
All data comes from the backend; nothing is mocked. Route guards are UX only — the backend enforces authorization.
