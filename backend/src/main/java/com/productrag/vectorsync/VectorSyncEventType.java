package com.productrag.vectorsync;

public enum VectorSyncEventType {
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_DEACTIVATED,
    PRODUCT_REACTIVATED,
    PRODUCT_DELETED,
    PRODUCT_RESYNC_REQUESTED,
    /** A brand, category, attribute or variant (aggregate_type) changed, or a record its document shows did. */
    ENTITY_CHANGED,
    ENTITY_DELETED,
    ENTITY_RESYNC_REQUESTED
}
