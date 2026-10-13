package com.productrag.catalog.option.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * An option of a variant product. Existing options and values are matched by {@code id} (or by name/value text when
 * no id is sent), so renaming keeps variant references. {@code attributeId} optionally links the option to an
 * attribute definition whose type and select options validate the values.
 */
public record ProductOptionRequest(
        UUID id,

        @NotBlank(message = "Option name is required")
        @Size(max = 50, message = "Option name must be at most 50 characters")
        String name,

        UUID attributeId,

        @NotEmpty(message = "An option needs at least one value")
        @Size(max = 50, message = "An option can have at most 50 values")
        List<@Valid @NotNull(message = "Option values must not be empty") Value> values) {

    public record Value(
            UUID id,

            @NotBlank(message = "Option values must not be blank")
            @Size(max = 100, message = "Option values must be at most 100 characters")
            String value) {
    }
}
