# Semantic product search

**Status:** done — unit, controller and integration tests; live queries on `RagDB` returned 200 with expected
products. API: `ai/API_CONTRACTS.md` (`GET /products/semantic-search`), examples `docs/api-examples.md`.
Per-module search (brands, categories, attributes, variants) is separate: `catalog-embeddings.md`.

## Files
- `search/SemanticSearchController`, `SemanticProductSearchService`, `SemanticSearchQuery`,
  `SearchCandidateStore` / `JdbcSearchCandidateStore`, `dto/SemanticSearchResponse`.
- Reuses `ProductEmbeddingStore.searchActive`, `EmbeddingClient`, `ProductService.getAll(ids)` (ordered responses).
- Chat side: `rag/CatalogOverview` (whole-catalog answers), `rag/QueryIntentParser` (brand filter).

## Flow
1. Validate (q 2-200, limit 1-50, min <= max price, max 5 options). Not transactional.
2. Candidates: exact SKU (when q is one SKU-charset token containing a digit or `.`/`_`/`-`; plain words are
   skipped), vector top 100 (query embedded once, not stored), keyword
   top 20. Provider failure -> keyword only, `mode=KEYWORD_ONLY` + warning.
3. SQL verification of all candidates: ACTIVE, category subtree (recursive CTE), brand, price, stock and all
   options on the same ACTIVE variant (simple products fail option filters). Returns matching variants (max 10).
4. Order: exact SKU, then similarity, then keyword-only; trim to `limit`; load live product data.

## Decisions
- LLM never used; no generated SQL; filter values are bind parameters.
- Vector candidates use the same minimum similarity as chat (`RAG_SIMILARITY_THRESHOLD`) and only the configured
  embedding model's vectors (`embeddingStore.searchActive`).

## Tests
- `SemanticProductSearchServiceTest` (7), `SemanticSearchControllerTest` (3), `CatalogOverviewTest` (3),
  `QueryIntentParserTest.aSingleKnownBrandBecomesAnExactFilter`,
  `ProductManagementIntegrationTest.semanticSearchVerifiesFiltersAndReadsLivePricesFromTheDatabase`.

## Known issues / not done
- A query with no real match (e.g. "jeans") still returns the nearest products above the threshold; filters are explicit params,
  free-text constraints ("under 50000") are not parsed in this API (the chat parser does that).
- Exact scan, no HNSW; when an index is added, filtered queries need `hnsw.ef_search` / iterative scans.
