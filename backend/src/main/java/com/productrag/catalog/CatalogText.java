package com.productrag.catalog;

import com.productrag.common.exception.ConflictException;

/** Small normalization and concurrency helpers shared by the catalog services. */
public final class CatalogText {

    private CatalogText() {
    }

    /** Trimmed value, or null when blank. */
    public static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Rejects stale updates when the client sent the version it last read. */
    public static void checkVersion(Long requested, Long current, String label) {
        if (requested != null && !requested.equals(current)) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The " + label + " was modified by someone else. Reload it and apply your changes again.");
        }
    }
}
