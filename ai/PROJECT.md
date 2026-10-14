# Project overview

Product catalog admin panel with an AI chat (RAG) over the catalog. Admins manage products and catalog data;
any signed-in user can browse and chat. Long-form docs live in `docs/` and `README.md`; this folder is the short
version for AI-assisted work.

## Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 21, Spring Boot 3.5, Spring Security (JWT HS256), Spring Data JPA, Bean Validation, Flyway, Spring AI 1.1 |
| Database | PostgreSQL 16 (local DB `RagDB`, localhost:5432) with `vector` (pgvector) and `pg_trgm` |
| AI | Ollama locally (`nomic-embed-text` 768-dim, `llama3.2:3b`) or OpenAI-compatible API |
| Frontend | React 19, TypeScript, Vite 8, Tailwind 4, React Router 7, Axios, react-hook-form + zod |
| Tests | JUnit 5, Mockito, MockMvc (`@WebMvcTest`), opt-in integration test on a local `*_test` DB (`IT_DB_URL`); no Docker |

## Layout

```text
backend/                 Spring Boot app (Maven wrapper: backend/mvnw.cmd)
  src/main/java/com/productrag/
    auth/ chat/ rag/ ai/ embedding/ vectorsync/ product/ common/ config/
    catalog/             category/ brand/ attribute/ variant/ (+ shared CatalogStatus, Slugs, ...)
  src/main/resources/application.yml, db/migration/V*.sql
frontend/src/            api/ auth/ components/ features/ pages/ lib/
scripts/                 seed-sample-products.mjs, sample-products.json
docs/                    architecture.md, api-examples.md, rag-flow.md, vector-sync.md
ai/                      this context folder
```

## Modules

- `product`: product CRUD, JSONB attributes, search/filters, outbox events for vector sync.
- `catalog`: categories, brands, attribute definitions, variants (see `ai/context/`).
- `vectorsync` + `embedding`: transactional outbox, pgvector embeddings of products and of brands, categories,
  attributes and variants (`ai/context/catalog-embeddings.md`).
- `rag` + `chat` + `ai`: retrieval (catalog overview, SQL filters, vector search), streaming chat,
  conversation history.
- `search`: `GET /products/semantic-search` — hybrid vector/keyword/SKU search verified by SQL filters
  (`ai/context/semantic-search.md`); per-module `GET /{brands|categories|product-attributes|product-variants}/
  semantic-search` (`ai/context/catalog-embeddings.md`).
- `embedding` + `vectorsync`: product search document, pgvector store, outbox worker and repair actions
  (`ai/context/product-embeddings.md`, `ai/context/embedding-sync.md`).
- `auth`: JWT login, roles `ADMIN` / `USER`, admin bootstrap from `.env`.

## Commands (Windows / PowerShell)

```powershell
cd backend; .\mvnw.cmd spring-boot:run          # API on http://localhost:8080
cd backend; .\mvnw.cmd test                     # backend tests
cd frontend; npm run dev                        # UI on http://localhost:5173 (proxies /api)
cd frontend; npm run lint; npm run build        # lint + typecheck + build
node scripts/seed-sample-products.mjs           # sample products via REST (backend must run)
```

Configuration comes from `.env` in the repo root (git-ignored); `.env.example` is the template. Never copy
values from `.env` into docs or code.

## Conventions

- API base path `/api/v1`; JSON errors use `ApiError` (`code`, `message`, `fieldErrors`).
- DTOs are Java records; entities never leave controllers.
- Lists: `page`, `size` (1-100), `sort=prop,asc|desc` against an allowlist (`PageRequests.of`).
- Writes require `ADMIN` (URL rule in `SecurityConfig` + `@PreAuthorize`).
- Optimistic locking via `version` on update -> `409 CONCURRENT_MODIFICATION`.
- Schema changes only through new Flyway files (`V{n}__*.sql`); never edit applied migrations.
