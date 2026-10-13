package com.productrag.catalog.option.dto;

import java.util.List;
import java.util.UUID;

/** {@code unit} comes from a linked NUMBER attribute (e.g. "GB") and is appended to values for display. */
public record ProductOptionResponse(UUID id, String name, UUID attributeId, String unit, int position,
                                    List<Value> values) {

    public record Value(UUID id, String value, int position) {
    }
}
