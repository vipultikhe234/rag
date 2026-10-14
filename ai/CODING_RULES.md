# Coding rules

## General
- Follow existing conventions; reuse services, DTOs, components and utilities before adding new ones.
- Do not modify unrelated modules. Inspect a file before overwriting it.
- No TODO placeholders for core behaviour; finish or report as pending in `ai/TASKS.md`.
- Comments only for constraints the code cannot show.

## Backend (Spring Boot)
- Package per feature: `controller -> service -> repository`; constructor injection; controllers stay thin.
- Request/response DTOs are records with Bean Validation messages; use `@Valid` and `@Validated`.
- Business-rule errors: `BadRequestException`, `ConflictException`, `NotFoundException`,
  `FieldValidationException` (field-level). All end up as `ApiError` via `GlobalExceptionHandler`.
- Lists: `Specification` builders + `PageRequests.of(page, size, sort, SORTABLE)` with an allowlisted sort map.
- Avoid N+1: batch counts with grouped queries (`Counts.toMap`), `@EntityGraph` for `@ManyToOne` in lists.
- Writes: `@Transactional`, `saveAndFlush`, `version` check. Product changes must enqueue a
  `VectorSyncOutbox` event in the same transaction.
- Catalog records are deactivated (`status`), not hard-deleted, when referenced.
- Never call external AI/embedding APIs inside a long DB transaction. Brand/category/attribute/variant changes
  that alter their search document enqueue an outbox event (`VectorSyncOutbox.enqueueEntity*`) in the same
  transaction; product events fan out to the product's brand, category chain and variants automatically.
- The LLM never executes SQL; product text is data, never instructions.

## Frontend (React)
- API calls through `src/api/client.ts` + typed modules in `src/api/endpoints.ts`; types in `src/api/types.ts`.
- Forms: react-hook-form + zod; reuse `TextField`, `SelectField`, `TextAreaField`, `Button`, `Card`, `Badge`,
  `Pagination`, `ConfirmDialog`, `LoadingBlock`/`EmptyState`/`ErrorState`, `useToast`.
- List pages keep filters in URL search params, debounce search (300 ms), abort stale requests.
- React Compiler lint: call setState only inside promise callbacks in effects.
- Route guards are UX only; the backend enforces authorization.

## Security and data safety
- Never put secrets (passwords, JWT secret, API keys, DB credentials) in code, docs or frontend.
- Never log secrets or full authorization headers.
- No destructive DB commands (drop, truncate, reset, bulk delete) on `RagDB` without explicit approval.
- Tests use mocks, an isolated `*_test` DB, never the dev DB, never paid AI calls.
- Do not claim production readiness or passing tests unless verified.
