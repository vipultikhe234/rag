# Product RAG Admin Panel

A full-stack admin panel for managing a product catalog and asking natural-language questions about it.
Products live in PostgreSQL; their embeddings live in the **same** PostgreSQL instance (pgvector) and are kept
in sync automatically through a transactional outbox. AI Chat answers questions with Retrieval-Augmented
Generation (RAG): exact questions (price, stock, SKU, counts) are answered from parameterized SQL, descriptive
questions from vector similarity search, and the LLM only ever sees the retrieved, authoritative product data.

| Layer    | Technology |
|----------|------------|
| Backend  | Java 21, Spring Boot 3.5, Spring Security (JWT), Spring Data JPA, Bean Validation, Flyway, Spring AI 1.1 (OpenAI), pgvector-java, Actuator |
| Database | PostgreSQL 16/17 + pgvector extension (`pg_trgm` for text search) |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS 4, React Router 7, Axios, React Hook Form, Zod |
| Tests    | JUnit 5, Mockito, Spring MockMvc, Testcontainers (pgvector) |

Further reading: [architecture](docs/architecture.md) · [API examples](docs/api-examples.md) ·
[RAG flow](docs/rag-flow.md) · [vector sync](docs/vector-sync.md)

---

## 1. Features

**Product Management** — create, edit, list (pagination, sorting), search by name/SKU/category/brand, filter by
category and status, activate/deactivate, delete with confirmation, free-form attributes (JSONB), optimistic
locking, per-product "AI index" status with manual retry, and an admin panel for the vector sync queue.

**AI Chat** — conversations persisted per user, answers with source product cards (price, stock, relevance
score, match type), retrieval strategy and applied filters shown, Enter-to-send, new/delete chat, clear errors
when the AI provider is unavailable or not configured.

## 2. Repository layout

```
.env.example            Configuration template (copy to .env; .env is git-ignored)
docker-compose.yml      PostgreSQL 17 + pgvector for local development
backend/                Spring Boot application (Maven Wrapper included)
  src/main/java/com/productrag/
    auth/               Users, JWT, login, admin bootstrap
    product/            Product entity, CRUD service/controller, structured filters
    embedding/          Embedding text builder, pgvector store (JDBC)
    vectorsync/         Transactional outbox, worker, retries, admin API, metrics
    rag/                Question parsing, retrieval strategies, prompt building, RAG service
    chat/               Conversations and messages
    ai/                 Spring AI wrappers, provider error mapping, timeouts
    common/, config/    Errors, rate limiting, request size limit, security config
  src/main/resources/db/migration/   Flyway migrations
frontend/               React + Vite admin UI
scripts/                Sample products and a seeding script (uses the REST API)
docs/                   Architecture, API, RAG and vector sync documentation
```

## 3. Prerequisites and tool check

| Tool | Version | Check |
|------|---------|-------|
| JDK | 21 | `java -version` |
| Maven | not required (wrapper included) | `backend\mvnw.cmd -v` |
| Node.js / npm | Node ≥ 22.12 | `node --version`, `npm --version` |
| PostgreSQL + pgvector | PG 13+ with pgvector ≥ 0.5 | see section 5 |
| Docker (optional) | any recent | `docker --version` — needed for `docker compose` and the Testcontainers integration tests |

On Windows set `JAVA_HOME` if it is not set, e.g. `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`.

## 4. Maven Wrapper

The backend ships with the Maven Wrapper (Maven 3.9.x, distribution pinned by SHA-512 in
`backend/.mvn/wrapper/maven-wrapper.properties`), so no global Maven install is needed:

```powershell
cd backend
.\mvnw.cmd -v          # Windows
./mvnw -v              # macOS/Linux
```

## 5. Database: PostgreSQL + pgvector

Products and embeddings are stored in one PostgreSQL database; there is no separate vector database.

**Option A — Docker (recommended).** `docker-compose.yml` starts `pgvector/pgvector:pg17` with a named volume:

```powershell
docker compose up -d          # reads DB_NAME, DB_USERNAME, DB_PASSWORD, DB_PORT from .env
docker compose ps             # wait for "healthy"
```

The default host port is **5433** to avoid clashing with a locally installed PostgreSQL on 5432.

**Option B — existing local PostgreSQL.** Install the pgvector extension into that server, then point `DB_URL`
at your database. On Windows, build pgvector from the official source (requires Visual Studio C++ Build Tools,
run in an *x64 Native Tools Command Prompt* **as Administrator**):

```bat
set "PGROOT=C:\Program Files\PostgreSQL\16"
cd /d %TEMP%
git clone --branch v0.8.7 https://github.com/pgvector/pgvector.git
cd pgvector
nmake /F Makefile.win
nmake /F Makefile.win install
```

Then enable and verify it in your database:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

The `public` schema of the target database should be empty on first start. Flyway refuses to migrate a
non-empty schema without a history table (and `clean` is disabled), so existing data is never touched.

## 6. Configuration (`.env`)

```powershell
Copy-Item .env.example .env     # then edit .env
```

The backend imports `../.env` automatically (`spring.config.import`), Docker Compose reads it, and Vite reads
only `VITE_*` variables from it. Real environment variables override the file. Key settings:

| Variable | Purpose |
|----------|---------|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | JDBC connection |
| `JWT_SECRET` | HMAC key, ≥ 32 bytes. Generate: `$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)` or `openssl rand -base64 48` |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Initial admin (created once, ≥ 12 chars, never overwritten) |
| `OPENAI_API_KEY` | Enables embeddings and chat |
| `OPENAI_EMBEDDING_MODEL`, `EMBEDDING_DIMENSIONS` | Must match each other and the `vector(N)` column |
| `OPENAI_CHAT_MODEL`, `OPENAI_CHAT_TEMPERATURE` | Chat model; leave temperature empty for models that reject it |
| `RAG_*` | Retrieval limit, similarity threshold, structured result limit |
| `VECTOR_SYNC_*` | Worker poll interval, batch size, attempts, backoff, stale lease timeout |
| `CORS_ALLOWED_ORIGINS` | Browser origins allowed to call the API |

The application refuses to start with a missing/short JWT secret or with the `replace-with…` placeholders.
Never commit `.env`; it is listed in `.gitignore`.

## 7. Database migrations

Flyway runs automatically at backend startup (`backend/src/main/resources/db/migration`). `V1__initial_schema.sql`
creates the `vector` and `pg_trgm` extensions and the tables `app_users`, `products`, `product_embeddings`,
`vector_sync_events`, `chat_conversations`, `chat_messages` with constraints and indexes. The embedding column is
created as `vector(${embedding_dimensions})` from `EMBEDDING_DIMENSIONS`; at startup the backend verifies the
column dimension matches the configuration and fails fast if not. `V2__align_embedding_dimension.sql` resizes the
column (and removes stored vectors) when a database created with another dimension is migrated, e.g. when switching
from OpenAI (1536) to Ollama (768); it does nothing when the dimension already matches.

## 8. Starting the application

```powershell
# 1. Database (Docker option)
docker compose up -d

# 2. Backend  → http://localhost:8080
cd backend
.\mvnw.cmd spring-boot:run

# 3. Frontend → http://localhost:5173 (proxies /api to the backend)
cd ..\frontend
npm install
npm run dev
```

Production build of the frontend: `npm run build` (output in `frontend/dist`, served by any static web server
that proxies `/api` to the backend). Health check: `GET http://localhost:8080/actuator/health`.

## 9. Admin login and roles

On first start the backend creates the admin from `ADMIN_EMAIL`/`ADMIN_PASSWORD` if no user with that email
exists. If the variables are empty, no account is created (a warning is logged). There is no built-in default
password. Roles: `ADMIN` (product writes, vector sync administration, actuator metrics) and `USER` (read products,
use chat). Additional users can be inserted into `app_users` with a BCrypt hash; a user-management UI is not part
of this project.

## 10. OpenAI configuration

Set `OPENAI_API_KEY` and restart the backend. Without a key the application still runs: product management works,
vector sync events wait as `PENDING` (no attempts consumed), and chat returns `503 AI_PROVIDER_NOT_CONFIGURED`.
`OPENAI_BASE_URL` can point at an OpenAI-compatible endpoint. Calls use connect/read timeouts (`AI_CONNECT_TIMEOUT`,
`AI_READ_TIMEOUT`) and at most 2 attempts per request; provider errors are mapped to `503 AI_PROVIDER_UNAVAILABLE`
(temporary) or `502 AI_PROVIDER_ERROR` (rejected, e.g. invalid key or model).

The OpenAI **API** is billed separately from ChatGPT and needs prepaid credits; without them calls fail with
`502 AI_PROVIDER_QUOTA_EXCEEDED`.

### Free local alternative: Ollama

[Ollama](https://ollama.com) exposes an OpenAI-compatible API, so no code change is needed:

```powershell
winget install --id Ollama.Ollama -e
ollama pull nomic-embed-text   # embeddings, 768 dimensions (~270 MB)
ollama pull llama3.2:3b        # chat (~2 GB)
```

Then set in `.env`: `OPENAI_API_KEY=ollama`, `OPENAI_BASE_URL=http://localhost:11434`,
`OPENAI_EMBEDDING_MODEL=nomic-embed-text`, `EMBEDDING_DIMENSIONS=768`, `OPENAI_CHAT_MODEL=llama3.2:3b`,
`AI_READ_TIMEOUT=PT180S`, `VITE_API_TIMEOUT_MS=300000`. Restart the backend (Flyway resizes the vector column) and
click **Index missing products**. Local models on a CPU answer in tens of seconds and are less accurate than
OpenAI models; `RAG_SIMILARITY_THRESHOLD` usually needs to be higher for `nomic-embed-text` (see `.env.example`).

## 11. Sample data

With the backend running:

```powershell
node scripts/seed-sample-products.mjs
```

It reads `ADMIN_EMAIL`/`ADMIN_PASSWORD` from the environment or `.env` (and prompts if missing) and creates 20
products through the REST API (existing SKUs are skipped), so they are validated and indexed exactly like products
created in the UI.

## 12. Product CRUD via the API

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"<password>"}' | jq -r .accessToken)

curl -X POST http://localhost:8080/api/v1/products -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Wireless Headphones","sku":"WH-001","description":"Over-ear, ANC, 30-hour battery",
       "category":"Audio","brand":"SoundWave","price":7999.00,"stockQuantity":25,"attributes":{"color":"black"}}'

curl "http://localhost:8080/api/v1/products?q=headphones&status=ACTIVE&page=0&size=20&sort=price,asc" \
  -H "Authorization: Bearer $TOKEN"
```

All endpoints with request/response examples: [docs/api-examples.md](docs/api-examples.md).

## 13. Chat via the API

```bash
curl -X POST http://localhost:8080/api/v1/chat -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"Which products cost less than ₹1,000?","conversationId":null}'
```

The response contains `conversationId`, `answer`, `sources[]` (with `relevanceScore` and `matchType`) and a
`retrieval` block (strategy, applied filters, total matches, whether the LLM was used). Pass the returned
`conversationId` to continue the conversation.

The web UI uses `POST /api/v1/chat/stream` instead, which sends the same answer word by word as Server-Sent Events
(see [docs/api-examples.md](docs/api-examples.md#post-chatstream-server-sent-events)).

## 14. Automatic vector synchronization

Every product create/update/status change/delete writes an outbox row (`vector_sync_events`) **in the same
transaction** as the product change. A background worker claims events with `FOR UPDATE SKIP LOCKED`, reads the
current product, builds the embedding text, skips the API call if the text hash and model are unchanged, otherwise
calls the embedding API **outside any database transaction** and upserts the vector. Failures are retried with
bounded exponential backoff; permanent failures stop and are shown in the UI with a retry button. The search index
is therefore **eventually consistent** — typically a few seconds behind. Inactive or deleted products are excluded
from answers immediately, regardless of sync state. Details: [docs/vector-sync.md](docs/vector-sync.md).

Monitoring: `GET /api/v1/admin/vector-sync/status`, the "AI search index" panel on the Products page, and the
Micrometer gauge `vector.sync.events{status=PENDING|PROCESSING|COMPLETED|FAILED}` at
`/actuator/metrics/vector.sync.events` (admin only).

## 15. How RAG answers a question

1. Normalize and validate the question (length, control characters).
2. Parse intent with deterministic rules: SKU references, price/stock comparisons and ranges, categories, counts,
   sorting. Ambiguous numeric questions ("under 1000" — price or stock?) get a clarification, not a guess.
3. Choose a strategy: **exact SKU** lookup, **structured** SQL (allow-listed fields/operators, bound parameters),
   **hybrid** (SQL filters + vector search), or **semantic** vector search with a similarity threshold.
4. Reload the matched products from the `products` table (only `ACTIVE` ones) so prices and stock are current.
5. If nothing relevant is found, return a fixed "not found" message **without calling the LLM**.
6. Otherwise build a prompt with the configured system prompt, the products wrapped as untrusted data, a retrieval
   summary (filters, exact count) and recent conversation history; call the chat model; return answer + sources.

The LLM never generates or executes SQL. Details: [docs/rag-flow.md](docs/rag-flow.md).

## 16. Running tests

```powershell
cd backend
.\mvnw.cmd test            # unit + MockMvc tests; Testcontainers tests run only when Docker is available

cd ..\frontend
npm run lint
npm run build              # includes the TypeScript type check
```

No test calls a paid AI API: unit tests use fakes/mocks and the integration test uses deterministic in-process
embedding and chat models. The integration test (`ProductRagIntegrationTest`) starts its own throwaway
`pgvector/pgvector:pg17` container; it never touches your configured database. Tests also ignore the real `.env`
(`APP_ENV_FILE` is redirected in `pom.xml`).

Coverage includes product CRUD/validation/duplicate SKU, JWT and role-based access (401/403), outbox creation,
embedding skip on unchanged content, retries/backoff/permanent failures, stale lease recovery, idempotent
re-processing, deletion cleanup, query intent parsing, retrieval strategies, prompt-injection delimiting,
no-results without LLM, AI provider failures, and conversation isolation between users.

## 17. Verifying embeddings in the database

```sql
-- One row per embedded product, with the model and dimension used
SELECT p.sku, p.status, e.embedding_model, vector_dims(e.embedding) AS dims, e.content_hash, e.updated_at
FROM products p LEFT JOIN product_embeddings e ON e.product_id = p.id ORDER BY p.sku;

-- The exact text that was embedded for a product
SELECT content FROM product_embeddings e JOIN products p ON p.id = e.product_id WHERE p.sku = 'WH-001';

-- Outbox state
SELECT status, retryable, count(*) FROM vector_sync_events GROUP BY 1, 2;
SELECT event_type, status, attempts, max_attempts, next_attempt_at, last_error
FROM vector_sync_events WHERE status = 'FAILED' ORDER BY updated_at DESC;
```

## 18. Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Backend exits: `JWT secret ...` | Set `JWT_SECRET` to ≥ 32 random bytes (not the placeholder). |
| Backend exits: `ADMIN_PASSWORD must be at least 12 characters` | Use a longer password or leave both admin variables empty. |
| `Found non-empty schema(s) "public" but no schema history table` | Use an empty database/schema for this application. |
| `Connection refused` to PostgreSQL | Check `DB_URL` port (Docker default 5433), `docker compose ps`, credentials. |
| Frontend shows "Cannot reach the backend API" | Start the backend; check `VITE_DEV_PROXY_TARGET`. |
| 401 after some hours | JWT expired (`JWT_EXPIRATION`, default 8 h); sign in again. |
| 429 `RATE_LIMITED` | Login/chat rate limit per minute exceeded; wait or raise `RATE_LIMIT_*`. |
| Products stay "Pending" | No `OPENAI_API_KEY`, worker disabled (`VECTOR_SYNC_ENABLED`), or provider down — see the AI search index panel. |

## 19. AI provider and pgvector errors

| Error | Meaning / fix |
|-------|---------------|
| `ERROR: extension "vector" is not available` | pgvector is not installed in this PostgreSQL server (section 5). |
| `permission denied to create extension "vector"` | Run `CREATE EXTENSION vector;` once as a superuser, then restart the backend. |
| Startup: embedding column has N dimensions but `EMBEDDING_DIMENSIONS` is M | The model/dimension changed; follow the re-embedding procedure in [docs/vector-sync.md](docs/vector-sync.md). |
| Sync error "returned N dimensions but EMBEDDING_DIMENSIONS is M" | Model output doesn't match configuration; marked as permanent failure. |
| `503 AI_PROVIDER_NOT_CONFIGURED` | `OPENAI_API_KEY` missing; set it and restart. |
| `503 AI_PROVIDER_UNAVAILABLE` | Timeout, network error, HTTP 429 or 5xx from the provider; retry later. Sync retries automatically. |
| `502 AI_PROVIDER_ERROR` | Provider rejected the request (invalid key, unknown model). Fix configuration; sync marks it permanent. |
| `502 AI_PROVIDER_QUOTA_EXCEEDED` | OpenAI returned `429 insufficient_quota` (no credits). Add credits in the OpenAI billing settings, then use **Retry all failed** on the Products page. Not retried automatically. |

## 20. Security notes and production readiness

Implemented: BCrypt password hashing, stateless HS256 JWTs with issuer/expiry validation (user re-loaded per
request), role-based authorization (URL rules + method security), JSON 401/403 responses, CORS allow-list, request
body size limit (256 KB), login and chat rate limiting, input validation on every endpoint, parameterized SQL only,
prompt-injection mitigation (delimited untrusted data, sanitized delimiters, system prompt rules), error messages
without stack traces, logs without credentials/prompts/tokens, API keys never sent to the browser, `.env` ignored.

**This project is not production-ready as-is.** Before production you would at least need: TLS termination, a
shared rate limiter (the built-in one is in-memory per instance — `RateLimiter` is the integration point for
Redis/gateway limits), token revocation/refresh and an httpOnly-cookie or BFF session model instead of
`sessionStorage`, user management and password policies, secret management (vault/KMS), database backups,
an HNSW index once the catalog grows (see docs), centralized logging/alerting on the sync metrics, load testing,
and running the Testcontainers integration tests in CI (they were not executed on the development machine because
Docker was unavailable).
#   r a g  
 