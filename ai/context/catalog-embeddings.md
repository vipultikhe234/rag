# Catalog embeddings (brands, categories, attributes, variants)

Status: implemented and verified 2026-10-14 (unit, WebMvc, integration on `ragdb_it_test`, live on `RagDB`).
Schema: `ai/DATABASE.md` (V5). API: `ai/API_CONTRACTS.md`. Product embeddings: `product-embeddings.md`.

## Files
- `backend/src/main/resources/db/migration/V5__catalog_embeddings.sql`
- `embedding/CatalogEntityType.java`, `CatalogDocumentLoader.java` (documents), `CatalogEmbeddingStore.java`
  (upsert with `source_read_at` guard, ACTIVE-only vector search, name matches, counts, keyset ids),
  `EmbeddingSchemaVerifier.java` (both tables)
- `vectorsync/CatalogEmbeddingSynchronizer.java`, `VectorSyncProcessor.java` (dispatch on `aggregate_type`),
  `VectorSyncOutbox.java` (`enqueueEntity*`, `enqueueCategoryChains`, `enqueuePreviousSummaries`, product fan-out),
  `JdbcOutboxStore.java` / `OutboxStore.java`, `CatalogStaleEmbeddingScanner.java`, `VectorSyncAdminService.java`
- `search/CatalogSemanticSearchService.java`, `CatalogSemanticSearchController.java`,
  `dto/CatalogSemanticSearchResponse.java`; batch `getAll(ids)` in Brand/Category/ProductAttribute/ProductVariant services
- `rag/CatalogDiscovery.java` (chat `DISCOVERY`), wired in `ProductRetriever` after the SKU check
- Frontend: `features/products/VectorSyncPanel.tsx` (per-module counts), `api/types.ts`, `api/endpoints.ts`,
  `features/chat/ChatMessageItem.tsx` (strategy label)
- Tests: `vectorsync/{CatalogEmbeddingSynchronizerTest,CatalogStaleEmbeddingScannerTest}`,
  `search/{CatalogSemanticSearchServiceTest,CatalogSemanticSearchControllerTest}`, `rag/CatalogDiscoveryTest`,
  `ProductManagementIntegrationTest` (3 catalog scenarios)

## Documents ("Label: value" lines, no prices, stock, statuses or ids)
- Brand: name, description, distinct categories of its ACTIVE products, up to 20 product names.
- Category: name, path, description, subcategories, brands and up to 20 ACTIVE product names in its subtree.
- Attribute: name, type, unit, options, assigned categories.
- Variant: title, SKU, product, brand, leaf category, option values (NUMBER units added).

## Triggers (all in the writing transaction, through the outbox)
- Product create/update/status/delete/variant change -> product event + its brand, category chain, variants.
- Product moved to another brand/category -> previous brand and previous category chain too.
- Brand create / rename / description change; delete -> `ENTITY_DELETED`. Brand rename also re-queues its products
  (existing behaviour), which fans out to their variants.
- Category create -> self + ancestors; rename/move -> whole subtree (products and categories), old and new
  ancestors, attributes assigned to it (rename); description change -> itself; delete -> deleted + parent chain.
- Attribute create, or change of name/type/unit/options/categories -> itself; rename/unit change also re-queues
  variants with options linked to it.
- Variant create (batch), title/SKU change -> itself.
Unchanged documents are skipped by hash (no provider call).

## Search and chat
- API: name matches first, then vectors >= `RAG_SIMILARITY_THRESHOLD`; ACTIVE only; items read live via the
  module services; provider failure -> `KEYWORD_ONLY` + warning.
- Chat `DISCOVERY`: one subject word (brand(s), manufacturer(s), category/ies, subcategory/ies, attribute(s),
  specification(s), variant(s)) + a cue word (which, what, best, top, recommend, suggest, find, show, list, any,
  good, suitable) + remaining topic words. Not used with price/stock filters, an explicit SKU, an ambiguous
  condition or several subjects. At most 5 entities, weak matches dropped by `RAG_RELATIVE_SCORE_MARGIN`; nothing
  relevant -> normal product retrieval. Answer says it is a relevance ranking, not an exhaustive list.

## Repair
- `resync-missing` queues ACTIVE entities without an embedding for the current model (`catalogEnqueued`).
- `refresh-stale` rebuilds documents in keyset batches of 200 and queues only hash/model mismatches (`catalog`).
- Inactive entities are embedded only when they change; search never returns them anyway.

## Live state (`RagDB`, 2026-10-14)
38 brands, 36 categories, 28 attributes, 28 variants embedded (`nomic-embed-text`, 768); 0 failed, 0 stale.
Examples: brands "gaming laptops" -> Lenovo, ASUS, Dell; categories "running gear" -> Footwear, Fitness
Accessories; attributes "screen size" -> Screen Size; chat "Which brands make gaming laptops?" -> `DISCOVERY`.
