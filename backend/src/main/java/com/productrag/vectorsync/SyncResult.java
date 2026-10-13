package com.productrag.vectorsync;

/** Outcome of reconciling one product's vector with its current relational state. */
public enum SyncResult {
    /** A new embedding was generated and stored. */
    EMBEDDED,
    /** Semantic content unchanged - no embedding API call was made. */
    UNCHANGED,
    /** The product no longer exists; its vectors were removed. */
    VECTORS_DELETED,
    /** A newer product version was already synchronized; this stale result was discarded. */
    SUPERSEDED
}
