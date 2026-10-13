package com.productrag.catalog.option.dto;

import java.util.UUID;

/** The value a variant has for one option; {@code label} includes the unit, e.g. "256 GB". */
public record SelectedOption(UUID optionId, String name, UUID valueId, String value, String label) {
}
