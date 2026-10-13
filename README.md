# Product RAG Admin Panel

A full-stack admin panel for managing a product catalog and chatting with it in plain language.

- **Products** are stored in PostgreSQL, and their **embeddings** in the same database through the pgvector
  extension. There is no separate vector database.
- Every product change is copied to the search index automatically through a **transactional outbox**.
- **AI Chat** answers with Retrieval-Augmented Generation (RAG). Exact questions (price, stock, SKU, counts) are
  answered with parameterized SQL, descriptive questions with vector similarity search. The language model only
  sees the retrieved product data and never touches the database.

| Layer    | Technology |
|----------|------------|
| Backend  | Java 21, Spring Boot 3.5, Spring Security (JWT), Spring Data JPA, Bean Validation, Flyway, Spring AI 1.1 (OpenAI API), pgvector-java, Actuator |
| Database | PostgreSQL 16/17 with the `vector` (pgvector) and `pg_trgm` extensions |
| AI       | OpenAI, or any OpenAI-compatible server such as [Ollama](https://ollama.com) (free, runs locally) |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS 4, React Router 7, Axios, React Hook Form, Zod, react-markdown |
| Tests    | JUnit 5, Mockito, Spring MockMvc, Testcontainers (pgvector) |

More documentation: [Architecture](docs/architecture.md) · [API examples](docs/api-examples.md) ·
[RAG flow](docs/rag-flow.md) · [Vector sync](docs/vector-sync.md)

---

## Contents

1. [Features](#1-features)
2. [Quick start](#2-quick-start)
3. [Prerequisites](#3-prerequisites)
4. [Repository layout](#4-repository-layout)
5. [Database setup (PostgreSQL + pgvector)](#5-database-setup-postgresql--pgvector)
6. [Configuration (`.env`)](#6-configuration-env)
7. [AI provider: OpenAI or Ollama](#7-ai-provider-openai-or-ollama)
8. [Running the application](#8-running-the-application)
9. [Sample data](#9-sample-data)
10. [Users and roles](#10-users-and-roles)
11. [Using the API](#11-using-the-api)
12. [How RAG answers a question](#12-how-rag-answers-a-question)
13. [Automatic vector synchronization](#13-automatic-vector-synchronization)
14. [Database migrations](#14-database-migrations)
15. [Tests](#15-tests)
16. [Inspecting embeddings in the database](#16-inspecting-embeddings-in-the-database)
17. [Troubleshooting](#17-troubleshooting)
18. [Security and production readiness](#18-security-and-production-readiness)

---

## 1. Features

### Product Management

- Create, edit, view and delete products (with confirmation).
- List with pagination and sorting; search by name, SKU, category or brand; filter by category and status.
- Activate / deactivate products. Inactive products disappear from chat answers immediately.
- Free-form attributes stored as JSONB (for example `color`, `warranty`, `features`).
- Optimistic locking, so two admins cannot silently overwrite each other's changes.
- Per-product **AI index** status with a manual retry, plus an **AI search index** panel for the sync queue.

### AI Chat (ChatGPT-style)

- Answers **stream word by word** as they are generated, with a **Stop** button that keeps the partial answer.
- Sidebar with conversation history grouped by date (Today, Yesterday, Previous 7 days, …), search,
  inline rename and delete.
- Answers rendered as Markdown (lists, tables, bold), with copy and retry buttons.
- A **Sources** panel under each answer shows the products used: price, stock, relevance score and match type,
  plus the retrieval strategy and applied filters.
- Suggested questions on a new chat; Enter sends, Shift+Enter adds a new line.
- Clear messages when the AI provider is unavailable, out of quota or not configured.

---

## 2. Quick start

For a Windows machine with PostgreSQL + pgvector and Ollama already installed (details in the sections below):

```powershell
# 1. Configuration
Copy-Item .env.example .env          # then edit .env: database, JWT_SECRET, admin account, AI provider

# 2. Backend  -> http://localhost:8080
cd backend
.\mvnw.cmd spring-boot:run

# 3. Frontend -> http://localhost:5173 (in a second terminal)
cd frontend
npm install
npm run dev

# 4. Optional: 20 sample products (in a third terminal, with the backend running)
node scripts/seed-sample-products.mjs
```

Open http://localhost:5173 and sign in with `ADMIN_EMAIL` / `ADMIN_PASSWORD` from `.env`.

---

## 3. Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| JDK | 21 | `java -version` |
| Maven | Not required; the Maven Wrapper is included | `backend\mvnw.cmd -v` |
| Node.js / npm | Node 22.12 or newer | `node --version`, `npm --version` |
| PostgreSQL + pgvector | PostgreSQL 13+ with pgvector 0.5+ | See [section 5](#5-database-setup-postgresql--pgvector) |
| AI provider | OpenAI API key **or** Ollama | See [section 7](#7-ai-provider-openai-or-ollama) |
| Docker (optional) | Any recent version | `docker --version`; used by `docker compose` and the Testcontainers tests |

On Windows, set `JAVA_HOME` if it is missing, for example `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`.

> **Maven Wrapper:** there is no need to install Maven. Use `.\mvnw.cmd` (Windows) or `./mvnw` (macOS/Linux)
> inside `backend/`. The Maven distribution is pinned by SHA-512 in `backend/.mvn/wrapper/maven-wrapper.properties`.
> Typing plain `mvn` fails with "not recognized" unless Maven is installed globally.

---

## 4. Repository layout

```text
.env.example            Configuration template (copy to .env; .env is git-ignored)
docker-compose.yml      PostgreSQL 17 + pgvector for local development
backend/                Spring Boot application (Maven Wrapper included)
  src/main/java/com/productrag/
    auth/               Users, JWT, login, admin bootstrap
    product/            Product entity, CRUD service/controller, structured filters
    embedding/          Embedding text builder, pgvector store (JDBC)
    vectorsync/         Transactional outbox, worker, retries, admin API, metrics
    rag/                Question parsing, retrieval strategies, prompt building, RAG service
    chat/               Conversations, messages, Server-Sent Events streaming
    ai/                 Spring AI wrappers, provider error mapping, timeouts
    common/, config/    Errors, rate limiting, request size limit, security config
  src/main/resources/db/migration/   Flyway migrations
frontend/               React + Vite admin UI
  src/features/chat/    Chat UI: message list, composer, sidebar, Markdown rendering
scripts/                Sample products and a seeding script (uses the REST API)
docs/                   Architecture, API, RAG and vector sync documentation
```

---

## 5. Database setup (PostgreSQL + pgvector)

Products and embeddings live in one PostgreSQL database.

### Option A: Docker (recommended)

`docker-compose.yml` starts `pgvector/pgvector:pg17` with a named volume:

```powershell
docker compose up -d     # reads DB_NAME, DB_USERNAME, DB_PASSWORD and DB_PORT from .env
docker compose ps        # wait until the status is "healthy"
```

The default host port is **5433**, so it doesn't clash with a locally installed PostgreSQL on 5432.

### Option B: an existing local PostgreSQL

Install the pgvector extension into that server, then point `DB_URL` at your database.

On Windows, build pgvector from the official source. This needs the Visual Studio C++ Build Tools and must run
in an **x64 Native Tools Command Prompt opened as Administrator**:

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

> The `public` schema of the target database should be empty on first start. Flyway refuses to migrate a
> non-empty schema without a history table (and `clean` is disabled), so existing data is never touched.

---

## 6. Configuration (`.env`)

```powershell
Copy-Item .env.example .env     # then edit .env
```

The backend imports `../.env` automatically (`spring.config.import`), Docker Compose reads it, and Vite exposes
only `VITE_*` variables to the browser. Real environment variables override the file.

### Database and security

| Variable | Purpose |
|----------|---------|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | JDBC connection, for example `jdbc:postgresql://localhost:5432/RagDB` |
| `JWT_SECRET` | HMAC signing key, at least 32 bytes (see below) |
| `JWT_EXPIRATION` | Token lifetime, default `PT8H` |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Initial admin, created once; password at least 12 characters, never overwritten |
| `CORS_ALLOWED_ORIGINS` | Browser origins allowed to call the API |
| `RATE_LIMIT_LOGIN_PER_MINUTE`, `RATE_LIMIT_CHAT_PER_MINUTE` | Per-user limits (in-memory, per instance) |

Generate a JWT secret:

```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
# or: openssl rand -base64 48
```

### AI provider

| Variable | Purpose |
|----------|---------|
| `OPENAI_API_KEY` | Enables embeddings and chat (`ollama` when using Ollama) |
| `OPENAI_BASE_URL` | `https://api.openai.com`, or `http://localhost:11434` for Ollama |
| `OPENAI_EMBEDDING_MODEL`, `EMBEDDING_DIMENSIONS` | Must match each other and the `vector(N)` column |
| `OPENAI_CHAT_MODEL`, `OPENAI_CHAT_TEMPERATURE` | Chat model; leave temperature empty for models that reject it |
| `AI_CONNECT_TIMEOUT`, `AI_READ_TIMEOUT` | Provider timeouts (ISO-8601, for example `PT180S`) |

### Retrieval (RAG)

| Variable | Default | Purpose |
|----------|---------|---------|
| `RAG_RETRIEVAL_LIMIT` | `5` | Maximum products found by vector search |
| `RAG_SIMILARITY_THRESHOLD` | `0.25` | Minimum cosine similarity; use about `0.5` for `nomic-embed-text` |
| `RAG_RELATIVE_SCORE_MARGIN` | `0.1` | Drops matches scoring more than this below the best match (`0` disables) |
| `RAG_STRUCTURED_RESULT_LIMIT` | `20` | Maximum products returned by exact SQL questions |

### Vector sync worker and frontend

| Variable | Purpose |
|----------|---------|
| `VECTOR_SYNC_*` | Worker on/off, poll interval, batch size, attempts, backoff, stale lease timeout |
| `VITE_API_BASE_URL` | API base path used by the browser, default `/api/v1` |
| `VITE_API_TIMEOUT_MS` | Browser request timeout; keep it above `AI_READ_TIMEOUT` |
| `VITE_DEV_PROXY_TARGET` | Backend address used by the Vite dev proxy, default `http://localhost:8080` |

> The application refuses to start with a missing or short `JWT_SECRET`, or with the `replace-with…`
> placeholders from `.env.example`. **Never commit `.env`**; it is listed in `.gitignore`. Only `VITE_*` values
> reach the browser, so never put secrets in them.

---

## 7. AI provider: OpenAI or Ollama

Without a configured provider the application still runs: product management works, vector sync events wait as
`PENDING` without consuming attempts, and chat returns `503 AI_PROVIDER_NOT_CONFIGURED`.

### Option A: OpenAI

Set `OPENAI_API_KEY` and restart the backend. The OpenAI **API** is billed separately from a ChatGPT
subscription and needs prepaid credits; without them calls fail with `502 AI_PROVIDER_QUOTA_EXCEEDED`.

### Option B: Ollama (free, local)

Ollama exposes an OpenAI-compatible API, so no code change is needed.

```powershell
winget install --id Ollama.Ollama -e
ollama pull nomic-embed-text   # embeddings, 768 dimensions (~270 MB)
ollama pull llama3.2:3b        # chat model (~2 GB)
```

Set these values in `.env`:

```properties
OPENAI_API_KEY=ollama
OPENAI_BASE_URL=http://localhost:11434
OPENAI_EMBEDDING_MODEL=nomic-embed-text
EMBEDDING_DIMENSIONS=768
OPENAI_CHAT_MODEL=llama3.2:3b
AI_READ_TIMEOUT=PT180S
RAG_SIMILARITY_THRESHOLD=0.5
VITE_API_TIMEOUT_MS=300000
```

Restart the backend (Flyway resizes the vector column, see [section 14](#14-database-migrations)), then click
**Index missing products** on the Products page.

**Performance tips for laptops**

- Intel/AMD integrated GPUs are ignored by default. Set the user environment variable `OLLAMA_IGPU_ENABLE=1`
  to use them (faster prompt processing), and `OLLAMA_KEEP_ALIVE=30m` to keep the model loaded between
  questions. Restart Ollama afterwards.
- On a typical laptop CPU the first words appear after about 7–11 seconds and a full answer takes 20–40 seconds.
  Streaming shows the text as it is written.
- Small local models such as `llama3.2:3b` are less accurate than OpenAI models. A larger model
  (for example `llama3.1:8b`) answers better but is roughly 2–3 times slower.

### Timeouts and errors

Calls use `AI_CONNECT_TIMEOUT` / `AI_READ_TIMEOUT` and at most 2 attempts per request. Provider errors are mapped
to `503 AI_PROVIDER_UNAVAILABLE` (temporary) or `502 AI_PROVIDER_ERROR` (rejected, for example an invalid key or
model). See [section 17](#17-troubleshooting).

---

## 8. Running the application

```powershell
# 1. Database (only for the Docker option)
docker compose up -d

# 2. Backend -> http://localhost:8080
cd backend
.\mvnw.cmd spring-boot:run

# 3. Frontend -> http://localhost:5173 (proxies /api to the backend)
cd frontend
npm install
npm run dev
```

- **Restart the backend after pulling new code or changing `.env`.** A backend started before an update keeps
  serving the old endpoints (for example, chat then reports that streaming is not supported).
- Health check: `GET http://localhost:8080/actuator/health`.
- Production build of the frontend: `npm run build`. The output in `frontend/dist` can be served by any static
  web server that proxies `/api` to the backend.

---

## 9. Sample data

With the backend running:

```powershell
node scripts/seed-sample-products.mjs
```

The script reads `ADMIN_EMAIL` / `ADMIN_PASSWORD` from the environment or `.env` (and prompts if they are
missing) and creates 20 products (prices in INR) through the REST API. Existing SKUs are skipped. The products are
validated and indexed exactly like products created in the UI.

---

## 10. Users and roles

On first start the backend creates the admin from `ADMIN_EMAIL` / `ADMIN_PASSWORD` if no user with that email
exists. If the variables are empty, no account is created and a warning is logged. There is no built-in default
password.

| Role | Can do |
|------|--------|
| `ADMIN` | Everything below, plus product changes, vector sync administration and actuator metrics |
| `USER` | View products and use the chat |

Additional users can be inserted into `app_users` with a BCrypt hash; a user-management UI is not part of this
project.

---

## 11. Using the API

All endpoints are under `/api/v1`. Full request and response examples: [docs/api-examples.md](docs/api-examples.md).

| Method and path | Purpose |
|-----------------|---------|
| `POST /auth/login`, `GET /auth/me` | Sign in (returns a JWT) and current user |
| `GET /products`, `GET /products/{id}`, `GET /products/categories` | List, search, view |
| `POST /products`, `PUT /products/{id}`, `PATCH /products/{id}/status`, `DELETE /products/{id}` | Manage products (admin) |
| `POST /chat` | Ask a question and get the full answer as JSON |
| `POST /chat/stream` | Ask a question and get the answer word by word as Server-Sent Events (used by the UI) |
| `GET /chat/conversations`, `GET /chat/conversations/{id}/messages` | Conversation history |
| `PATCH /chat/conversations/{id}`, `DELETE /chat/conversations/{id}` | Rename or delete a conversation |
| `GET /admin/vector-sync/status`, `POST /admin/vector-sync/retry-failed`, … | Search index administration (admin) |

### Example: products

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"<password>"}' | jq -r .accessToken)

curl -X POST http://localhost:8080/api/v1/products -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Wireless Headphones","sku":"WH-001","description":"Over-ear, ANC, 30-hour battery",
       "category":"Audio","brand":"SoundWave","price":7999.00,"stockQuantity":25,"attributes":{"color":"black"}}'

curl "http://localhost:8080/api/v1/products?q=headphones&status=ACTIVE&page=0&size=20&sort=price,asc" \
  -H "Authorization: Bearer $TOKEN"
```

### Example: chat

```bash
curl -X POST http://localhost:8080/api/v1/chat -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"Which products cost less than ₹1,000?","conversationId":null}'
```

The response contains `conversationId`, `answer`, `sources[]` (with `relevanceScore` and `matchType`) and a
`retrieval` block (strategy, applied filters, total matches, whether the LLM was used). Pass the returned
`conversationId` to continue the conversation.

For streaming, call `POST /chat/stream` with `Accept: text/event-stream, application/json`. The server sends a
`meta` event (conversation id and sources), then `delta` events with answer text, and finally `done` or `error`.

---

## 12. How RAG answers a question

1. **Validate** the question (length, control characters).
2. **Parse intent** with deterministic rules: SKU references, price/stock comparisons and ranges, categories,
   counts and sorting. Ambiguous numeric questions ("under 1000": price or stock?) get a clarifying question
   instead of a guess.
3. **Choose a strategy:**
   - **Exact SKU** lookup.
   - **Structured** SQL with allow-listed fields and operators and bound parameters.
   - **Hybrid**: SQL filters plus vector search.
   - **Semantic** vector search with a similarity threshold. Weak matches far below the best one are dropped
     (`RAG_RELATIVE_SCORE_MARGIN`), so small models aren't distracted by unrelated products.
4. **Reload** the matched products from the `products` table (only `ACTIVE` ones), so prices and stock are current.
5. If nothing relevant is found, return a fixed "not found" message **without calling the LLM**.
6. Otherwise **build the prompt**: the system prompt, the products wrapped as untrusted data, a retrieval summary
   (filters, exact count) and recent conversation history. Call the chat model and return the answer with its
   sources. If the requested kind of product doesn't exist, the model says so and suggests related products.

The LLM never generates or executes SQL. Details: [docs/rag-flow.md](docs/rag-flow.md).

---

## 13. Automatic vector synchronization

1. Every product create, update, status change or delete writes an outbox row (`vector_sync_events`) **in the same
   transaction** as the product change.
2. A background worker claims events with `FOR UPDATE SKIP LOCKED` and reads the current product.
3. It builds the embedding text and skips the API call if the text hash and model are unchanged.
4. Otherwise it calls the embedding API **outside any database transaction** and upserts the vector.
5. Failures are retried with bounded exponential backoff. Permanent failures stop and are shown in the UI with a
   retry button.

The search index is therefore **eventually consistent**, typically a few seconds behind. Inactive or deleted
products are excluded from answers immediately, regardless of sync state. Details:
[docs/vector-sync.md](docs/vector-sync.md).

**Monitoring:** `GET /api/v1/admin/vector-sync/status`, the **AI search index** panel on the Products page, and the
Micrometer gauge `vector.sync.events{status=PENDING|PROCESSING|COMPLETED|FAILED}` at
`/actuator/metrics/vector.sync.events` (admin only).

---

## 14. Database migrations

Flyway runs automatically when the backend starts (`backend/src/main/resources/db/migration`).

| Migration | What it does |
|-----------|--------------|
| `V1__initial_schema.sql` | Creates the `vector` and `pg_trgm` extensions and the tables `app_users`, `products`, `product_embeddings`, `vector_sync_events`, `chat_conversations` and `chat_messages`, with constraints and indexes. The embedding column is `vector(${embedding_dimensions})`, taken from `EMBEDDING_DIMENSIONS`. |
| `V2__align_embedding_dimension.sql` | Resizes the embedding column (and removes stored vectors) when the configured dimension changed, for example when switching from OpenAI (1536) to Ollama (768). Does nothing when the dimension already matches. |

At startup the backend checks that the column dimension matches the configuration and refuses to start if not.

---

## 15. Tests

```powershell
cd backend
.\mvnw.cmd test        # unit + MockMvc tests; Testcontainers tests run only when Docker is available

cd ..\frontend
npm run lint
npm run build          # includes the TypeScript type check
```

- **No test calls a paid AI API.** Unit tests use fakes and mocks; the integration test uses deterministic
  in-process embedding and chat models.
- **No test touches your database.** The integration test (`ProductRagIntegrationTest`) starts its own throwaway
  `pgvector/pgvector:pg17` container, and tests ignore the real `.env` (`APP_ENV_FILE` is redirected in `pom.xml`).
- Latest run on the development machine: 129 backend tests passed; the 13 Testcontainers tests were skipped
  because Docker was not available.

Coverage includes product CRUD, validation and duplicate SKUs, JWT and role-based access (401/403), outbox
creation, skipping unchanged embeddings, retries/backoff/permanent failures, stale lease recovery, idempotent
re-processing, deletion cleanup, query intent parsing, retrieval strategies and relevance filtering,
prompt-injection delimiting, no-results without LLM, AI provider failures, chat streaming (event order, errors,
partial answers), and conversation isolation between users.

---

## 16. Inspecting embeddings in the database

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

---

## 17. Troubleshooting

### Setup and startup

| Symptom | Cause and fix |
|---------|---------------|
| `mvn` is not recognized | Maven isn't installed globally. Use the wrapper: `cd backend; .\mvnw.cmd spring-boot:run`. |
| Backend exits: `JWT secret ...` | Set `JWT_SECRET` to at least 32 random bytes (not the placeholder). |
| Backend exits: `ADMIN_PASSWORD must be at least 12 characters` | Use a longer password, or leave both admin variables empty. |
| Backend exits: `ADMIN_PASSWORD still contains the placeholder` | Replace the `replace-with…` value from `.env.example`. |
| `Port 8080 was already in use` | Another backend is still running. Stop it (Ctrl+C in its terminal) before starting a new one. |
| `Found non-empty schema(s) "public" but no schema history table` | Use an empty database or schema for this application. |
| `Connection refused` to PostgreSQL | Check the `DB_URL` port (Docker default 5433), `docker compose ps` and the credentials. |
| `ERROR: extension "vector" is not available` | pgvector isn't installed in this PostgreSQL server ([section 5](#5-database-setup-postgresql--pgvector)). |
| `permission denied to create extension "vector"` | Run `CREATE EXTENSION vector;` once as a superuser, then restart the backend. |

### Frontend and chat

| Symptom | Cause and fix |
|---------|---------------|
| Blank white page after an update | Hard-refresh with Ctrl+Shift+R. If it persists, restart the dev server with `npx vite --force` to rebuild its cache. |
| "The backend does not support streaming chat yet" | The backend was started before the latest code. Restart it. |
| "Cannot reach the backend API" | Start the backend; check `VITE_DEV_PROXY_TARGET`. |
| Chat answers are slow | Normal for local models on a CPU; see the performance tips in [section 7](#7-ai-provider-openai-or-ollama). |
| Chat says a product doesn't exist although it does | Check that the product is `ACTIVE` and indexed (AI index status), then ask in a **new chat**: earlier wrong answers in the same conversation can mislead the model. |
| 401 after some hours | The JWT expired (`JWT_EXPIRATION`, default 8 h); sign in again. |
| 429 `RATE_LIMITED` | Login or chat rate limit exceeded; wait, or raise `RATE_LIMIT_*`. |

### AI provider and search index

| Symptom or error | Cause and fix |
|------------------|---------------|
| Products stay "Pending" | No AI provider configured, worker disabled (`VECTOR_SYNC_ENABLED`), or the provider is down; see the AI search index panel. |
| `503 AI_PROVIDER_NOT_CONFIGURED` | `OPENAI_API_KEY` is missing; set it and restart. |
| `503 AI_PROVIDER_UNAVAILABLE` | Timeout, network error, HTTP 429 or 5xx from the provider (or Ollama isn't running); retry later. Sync retries automatically. |
| `502 AI_PROVIDER_ERROR` | The provider rejected the request (invalid key, unknown model). Fix the configuration; sync marks it permanent. |
| `502 AI_PROVIDER_QUOTA_EXCEEDED` | OpenAI returned `429 insufficient_quota` (no credits). Add credits, then use **Retry all failed** on the Products page. Not retried automatically. |
| Startup: embedding column has N dimensions but `EMBEDDING_DIMENSIONS` is M | The model or dimension changed; follow the re-embedding procedure in [docs/vector-sync.md](docs/vector-sync.md). |
| Sync error "returned N dimensions but EMBEDDING_DIMENSIONS is M" | The model output doesn't match the configuration; marked as a permanent failure. |

---

## 18. Security and production readiness

**Implemented:**

- BCrypt password hashing; stateless HS256 JWTs with issuer and expiry validation (user re-loaded per request).
- Role-based authorization (URL rules + method security) with JSON 401/403 responses.
- CORS allow-list, request body size limit (256 KB), login and chat rate limiting.
- Input validation on every endpoint; parameterized SQL only; the LLM has no tools and no database access.
- Prompt-injection mitigation: product data is delimited as untrusted, delimiter characters are neutralized, and
  the system prompt tells the model to ignore instructions inside product data.
- Error responses without stack traces; logs without credentials, prompts or tokens.
- API keys and secrets never sent to the browser; `.env` is git-ignored.

> **This project is not production-ready as-is.** Before production you would at least need: TLS termination;
> a shared rate limiter (the built-in one is in-memory per instance; `RateLimiter` is the integration point for
> Redis or gateway limits); token revocation/refresh and an httpOnly-cookie or BFF session model instead of
> `sessionStorage`; user management and password policies; secret management (vault/KMS); database backups; an
> HNSW index once the catalog grows (see the docs); centralized logging and alerting on the sync metrics; load
> testing; and running the Testcontainers integration tests in CI (they were not executed on the development
> machine because Docker was unavailable).
