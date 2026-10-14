package com.productrag.search;

import com.productrag.search.dto.SemanticSearchResponse.MatchingVariant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only PostgreSQL lookups behind semantic search; every query is limited to ACTIVE products. */
public interface SearchCandidateStore {

    /** Products whose SKU, or one of whose ACTIVE variants' SKU, equals {@code sku} (case-insensitive). */
    List<UUID> exactSku(String sku);

    /** Products whose name, SKU, brand or category contains the text (case-insensitive), at most {@code limit}. */
    List<UUID> keyword(String text, int limit);

    /** Which candidates satisfy the structured filters, with the ACTIVE variants that satisfy them. */
    Verified verify(Collection<UUID> productIds, SemanticSearchQuery query);

    record Verified(Set<UUID> productIds, Map<UUID, List<MatchingVariant>> variants) {
    }
}
