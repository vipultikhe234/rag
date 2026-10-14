# Embedding sync

**Status:** done — unit tests, integration tests on `ragdb_it_test`, live `refresh-stale` on `RagDB`
(checked 31, stale 0). Operator guide: `docs/vector-sync.md`.
Since V5 the same outbox also carries BRAND / CATEGORY / ATTRIBUTE / VARIANT events (`VectorSyncProcessor`
dispatches on `aggregate_type`); their triggers, worker and repair are in `catalog-embeddings.md`.

## Files
- `vectorsync/VectorSyncOutbox` (MANDATORY propagation: `enqueue`, `enqueueForCategories`, `enqueueForAttribute`),
  `JdbcOutboxStore` (bulk `INSERT ... SELECT`, skips products with a PENDING event), `VectorSyncProcessor`
  (claim with `FOR UPDATE SKIP LOCKED`, retries via `BackoffPolicy`, `SyncFailureClassifier`),
  `ProductEmbeddingSynchronizer` (hash check, embed outside transactions, `source_version` guard),
  `VectorSyncScheduler`, `VectorSyncTrigger` (after-commit wake-up), `StaleEmbeddingScanner`,
  `VectorSyncAdminService` / `VectorSyncAdminController`.
- UI: `frontend/src/features/products/VectorSyncPanel.tsx` (status, failed events, retry, index missing, refresh outdated).

## Triggers (all in the business transaction)
| Change | Event | Products queued |
|--------|-------|-----------------|
| Product create / update / status / delete | `PRODUCT_*` | that product |
| Brand rename | `PRODUCT_UPDATED` | products of the brand |
| Category rename or move | `PRODUCT_UPDATED` | products of the category and all subcategories |
| Attribute name / slug / data type / unit change | `PRODUCT_UPDATED` | products having the attribute key (old slug) |
| Variant create, option change, status change | `PRODUCT_UPDATED` | parent product |
| Variant price / stock / SKU only, product price / stock only | none | — |
| `refresh-stale` | `PRODUCT_RESYNC_REQUESTED` | ACTIVE products whose rebuilt hash or model differs |

## Rules
- Never call the embedding provider inside a DB transaction; never delete a vector before a replacement exists.
- Stale writes are ignored when the stored `source_version` is newer.
- Repair actions are explicit admin calls, never run at startup; `refresh-stale` reads in keyset batches of 200,
  is idempotent and can be re-run after an interruption.

## Tests
- `CategoryServiceTest`, `ProductAttributeServiceTest`, `ProductVariantServiceTest` (outbox calls),
  `StaleEmbeddingScannerTest`, `VectorSyncProcessorTest`, `ProductEmbeddingSynchronizerTest`.
- `ProductManagementIntegrationTest`: `onlyChangesThatAlterTheSearchDocumentQueueReembedding`,
  `refreshStaleQueuesOnlyProductsWhoseEmbeddingIsOutdated`.

## Known issues
- Events for many products are processed in worker batches; a rename of a large category creates one event per
  product (bounded by catalog size).
