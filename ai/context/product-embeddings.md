# Product embeddings

**Status:** done — unit + integration tests on scratch DB `ragdb_it_test`; live on `RagDB` (31 products, all
current after `refresh-stale`). Sync workflow: `ai/context/embedding-sync.md`. Schema: `ai/DATABASE.md`.

## Files
- `embedding/EmbeddingContentBuilder` (document + SHA-256 hash), `ProductSearchContext`,
  `ProductSearchContextLoader` / `JdbcProductSearchContextLoader` (category path, attribute labels, active
  variant option values; 3 queries per batch), `ProductEmbeddingStore` / `JdbcProductEmbeddingStore`
  (upsert, metadata, nearest search), `EmbeddingSchemaVerifier` (column dimension = `EMBEDDING_DIMENSIONS`).
- Table `product_embeddings` (V1, dimension from V2). Model: Ollama `nomic-embed-text`, 768 dims (`.env`).

## Document (one per product, deterministic)
- Lines: `Product`, `SKU`, `Category` (path "Electronics > Mobiles > Smartphones"), `Brand`, `Description`
  (max 6000 chars), `Attributes` (definition name + value + unit for NUMBER, sorted by key), `Options` (values of
  ACTIVE variants in option order, max 20 per option).
- Excluded on purpose: price, compare-at price, currency, stock, status, variant SKUs, ids, timestamps,
  brand/category descriptions. Price/stock edits never cost an embedding call.
- Whitespace normalized; empty fields omitted. Hash = SHA-256 of the text; the worker skips the provider call
  when hash and model are unchanged.

## Decisions
- One vector per product. Brands, categories, attributes and variants have their own vectors since V5
  (`catalog-embeddings.md`); product search still uses them only as exact SQL filters.
- Inactive products keep their vector; every vector query joins `products` with `status = 'ACTIVE'`.
- A model change is detected by `embedding_model`; a dimension change needs a new migration (the column is typed).

## Tests
- `EmbeddingContentBuilderTest` (format, ordering, units, options, hash stability).
- `ProductManagementIntegrationTest.onlyChangesThatAlterTheSearchDocumentQueueReembedding`.

## Known issues / not done
- nomic `search_document:` / `search_query:` task prefixes are not used (changing them re-embeds everything;
  needs an A/B check first). See `ai/RESEARCH.md`.
