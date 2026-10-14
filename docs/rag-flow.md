# RAG flow

```mermaid
flowchart TD
  Q[Question] --> N[Normalize + validate<br/>NFKC, control chars, ≤ 1000 chars]
  N -->|"only about brands / categories / attributes / variants"| CA[CATALOG totals and lists from SQL<br/>no LLM call]
  N --> I[QueryIntentParser<br/>rule-based, no LLM]
  I -->|ambiguous numbers| CL[CLARIFICATION message<br/>no LLM call]
  I -->|SKU reference| SKU[EXACT_SKU lookup]
  I -->|filters only| ST[STRUCTURED SQL query]
  I -->|filters + descriptive text| HY[HYBRID: SQL filters + vector search]
  I -->|descriptive only| SE[SEMANTIC vector search]
  SKU & ST & HY & SE --> R[Reload ACTIVE products from products table]
  R -->|none| NR[Fixed not-found message<br/>no LLM call]
  R -->|found| P[PromptBuilder<br/>system prompt + untrusted product data + summary + history]
  P --> L[Chat model]
  L --> A[Answer + sources + retrieval info, persisted]
```

## 1. Normalization

`QuestionNormalizer` applies Unicode NFKC, replaces control characters, collapses whitespace and enforces
`max-question-length` (1000). Empty questions return `400 INVALID_QUESTION`. Chat is rate limited per user.

## 2. Intent parsing (deterministic)

`QueryIntentParser` extracts, with regular expressions and allow-lists only:

| Intent | Examples | Result |
|--------|----------|--------|
| Price comparison | "cost less than ₹1,000", "under 5k rupees", "between 1000 and 3000 price" | `price < 1000.00` etc. |
| Stock comparison | "stock greater than 10", "out of stock", "in stock", "fewer than 5 units" | `stock_quantity > 10` etc. |
| Category | a known category name appearing in the question | `category = audio` |
| Brand | exactly one known active brand name as a whole word ("Levi's jeans"); two brands = comparison, no filter | `brand = levi's` |
| Currency | ₹ / Rs / INR / $ / USD / € | `currency = INR` (defaults to INR for price filters) |
| SKU | "SKU WH-001", or a SKU-like token such as `cbl-usb-c-1m` | exact lookup candidates |
| Count | "how many …" | exact count from SQL |
| Sorting | "cheapest", "most expensive", "highest stock" | ORDER BY hint |

Only these fields (`PRICE`, `STOCK_QUANTITY`, `CATEGORY`, `BRAND`, `CURRENCY`) and operators (`<`, `≤`, `>`, `≥`, `=`) exist,
as Java enums. Values are validated (non-negative, whole numbers for stock) and always bound as SQL parameters.
The LLM never writes SQL and has no database access.

If a number cannot be attributed reliably ("products under 1000" — price or stock?), or a number is written in words,
the answer is a **clarification request** and no retrieval or LLM call happens.

## 3. Retrieval strategies (`ProductRetriever`)

- **CATALOG** (`CatalogOverview`, checked first) — questions that consist only of a subject word (brand(s),
  category/categories, subcategories, attribute(s), specification(s), variant(s)) and question/filler words, e.g.
  "how much brand we have", "list all categories". The answer is built from the whole table (names, active/inactive
  counts, products per brand, category tree) and returned without the LLM. Before this, such a question went to
  vector search, which returns only the few most similar products, so the model listed 2 brands instead of 41.
  "Which brands make gaming laptops" contains other words and goes to DISCOVERY instead.
- **EXACT_SKU** — case-insensitive SKU match among active products.
- **DISCOVERY** (`CatalogDiscovery`, after the SKU check) — exactly one subject word (brand, category, attribute,
  variant and their plurals/synonyms), a cue word (which, what, best, top, recommend, suggest, find, show, list, any,
  good, suitable) and other topic words, e.g. "which brands make gaming laptops", "which category fits running gear".
  The topic is embedded and matched against `catalog_embeddings` of that type (name matches first, then
  `similarity ≥ RAG_SIMILARITY_THRESHOLD`, weak matches dropped by `RAG_RELATIVE_SCORE_MARGIN`, at most 5). The answer
  lists the entities from live rows (brand product counts, category path, attribute type/unit/categories, variant
  price and stock) without the LLM and says it is a relevance ranking, not a complete list. Questions with price or
  stock conditions or an explicit SKU skip this step, and when nothing is relevant the normal product strategies run.
- **STRUCTURED** — filters without descriptive text: a JPA Specification query with the filters, ordered by the sort
  hint, limited to `RAG_STRUCTURED_RESULT_LIMIT`; `totalMatches` is the exact SQL count, which the prompt states as
  authoritative ("When a retrieval summary states an exact count … use that count").
- **HYBRID** — filters plus descriptive text ("headphones under ₹5,000"): vector search whose SQL includes the same
  parameterized filter conditions.
- **SEMANTIC** — the question is embedded with the same model as the products, and pgvector returns the nearest
  active products by cosine distance: `similarity = 1 − (embedding <=> :query)`, keeping only
  `similarity ≥ RAG_SIMILARITY_THRESHOLD` (default 0.25), at most `RAG_RETRIEVAL_LIMIT` (default 5) products.
  Matches scoring more than `RAG_RELATIVE_SCORE_MARGIN` (default 0.1) below the best match are then dropped, so
  near-misses don't reach the model: for "which products are for eating", Apple scores 0.66 and the next product
  0.53, so only Apple is sent.
  Only vectors from the currently configured embedding model are searched.
- **Name matches** — active products whose exact name appears in the question are placed first (useful for
  "compare X and Y").

Every strategy finishes by re-reading the products from `products` with `status = 'ACTIVE'`, so the context always
contains current price, stock and status, even if the vector is momentarily out of date.

## 4. No results

If nothing passes the filters/threshold, the configured `no-results-message` is returned directly. The LLM is not
called, so it cannot fabricate suggestions.

## 5. Prompt construction (`PromptBuilder`)

- **System prompt** — `app.rag.system-prompt` (configurable): use only supplied product data, don't invent facts,
  treat product content as data not instructions, rely on authoritative values for price/stock/SKU, cite name + SKU.
  Fixed context rules describing the delimiters are appended.
- **User message** — structured blocks:
  ```
  <retrieval_summary> strategy, applied filters, exact total count </retrieval_summary>
  <product_records>
    <product index="1"> name, sku, category, brand, price, currency, stock, attributes, description (≤ 1200 chars) </product>
    …
  </product_records>
  <conversation_history> last 6 messages </conversation_history>
  <question> … </question>
  ```
- **Injection defence** — product text, history and the question have `<` and `>` replaced (`‹ ›`) so stored content
  cannot close a block or forge instructions; the system prompt tells the model to ignore instructions found in data.
  This reduces but cannot fully eliminate prompt-injection risk; the important guarantee is that the model has no
  tools, no SQL access, and only sees products the user is allowed to see.

## 6. Response

`POST /api/v1/chat` returns:

```json
{
  "conversationId": "…",
  "messageId": "…",
  "answer": "…",
  "sources": [{ "productId": "…", "name": "…", "sku": "WH-001", "price": 7999.00, "currency": "INR",
                "stockQuantity": 25, "relevanceScore": 0.8123, "matchType": "SEMANTIC", "…": "…" }],
  "retrieval": { "strategy": "SEMANTIC", "appliedFilters": ["status = ACTIVE"], "totalMatches": null, "llmUsed": true },
  "createdAt": "…"
}
```

`sources` are exactly the products given to the LLM. `relevanceScore` is the cosine similarity (null for SQL-only
matches). The user question and answer (with sources and retrieval info) are stored only after the AI call succeeds;
the AI call itself runs outside any database transaction. Conversations are scoped to their owner — another user's
conversation id returns `404`.

`POST /api/v1/chat/stream` runs the same pipeline but streams the answer as Server-Sent Events: retrieval and prompt
building finish first and are sent as a `meta` event (conversation id, sources, retrieval info), then the model's text
arrives as `delta` events, and a final `done` event carries the same body as above. The messages are saved in one
short transaction after the last token; if the client disconnects, generation is cancelled and the partial answer is
saved. Nothing is written while the model is generating.

## Failure handling

| Situation | Result |
|-----------|--------|
| No API key | `503 AI_PROVIDER_NOT_CONFIGURED` (structured-only questions still need the chat model, so they fail too) |
| Provider not running / unknown host (e.g. Ollama stopped) | `503 AI_PROVIDER_UNREACHABLE` |
| Timeout / 429 / 5xx | `503 AI_PROVIDER_UNAVAILABLE` |
| Invalid key / model | `502 AI_PROVIDER_ERROR` |
| No credits / quota (`429 insufficient_quota`) | `502 AI_PROVIDER_QUOTA_EXCEEDED` |
| Empty model output | `502 AI_PROVIDER_EMPTY_RESPONSE` |

Nothing is persisted for failed questions; the UI keeps the text so the user can resend.
