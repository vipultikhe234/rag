package com.productrag.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Counts {

    private Counts() {
    }

    /** Converts grouped-count query rows of [UUID id, Number count] into a map. */
    public static Map<UUID, Long> toMap(List<Object[]> rows) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                counts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return counts;
    }
}
