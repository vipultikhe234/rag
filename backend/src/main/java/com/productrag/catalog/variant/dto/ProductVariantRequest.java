package com.productrag.catalog.variant.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.product.validation.ValidAttributes;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Create/update payload. {@code salePrice} is optional and must be lower than {@code price}. */
public record ProductVariantRequest(
        @NotNull(message = "Product is required")
        UUID productId,

        @NotBlank(message = "SKU is required")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$",
                message = "SKU must be 1-64 characters: letters, digits, '.', '_' or '-', starting with a letter or digit")
        String sku,

        @NotBlank(message = "Variant name is required")
        @Size(max = 200, message = "Variant name must be at most 200 characters")
        String variantName,

        @ValidAttributes
        Map<String, Object> attributes,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.00", message = "Price must not be negative")
        @Digits(integer = 10, fraction = 2, message = "Price must have at most 10 integer digits and 2 decimals")
        BigDecimal price,

        @DecimalMin(value = "0.00", message = "Sale price must not be negative")
        @Digits(integer = 10, fraction = 2, message = "Sale price must have at most 10 integer digits and 2 decimals")
        BigDecimal salePrice,

        @NotNull(message = "Stock quantity is required")
        @Min(value = 0, message = "Stock quantity must not be negative")
        @Max(value = 1_000_000_000, message = "Stock quantity is too large")
        Integer stockQuantity,

        CatalogStatus status,

        Long version) {
}
