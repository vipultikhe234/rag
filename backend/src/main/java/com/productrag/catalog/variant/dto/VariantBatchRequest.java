package com.productrag.catalog.variant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** New variants for one existing variant product. */
public record VariantBatchRequest(
        @NotEmpty(message = "Add at least one variant")
        @Size(max = 100, message = "A product can have at most 100 variants")
        List<@Valid @NotNull VariantDraft> variants) {
}
