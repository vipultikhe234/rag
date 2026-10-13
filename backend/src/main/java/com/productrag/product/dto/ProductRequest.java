package com.productrag.product.dto;

import com.productrag.product.ProductStatus;
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

/**
 * Create/update payload. {@code status} is optional (defaults to ACTIVE on create, unchanged on update);
 * {@code version} enables optimistic concurrency checks on update.
 */
public record ProductRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name must be at most 200 characters")
        String name,

        @NotBlank(message = "SKU is required")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$",
                message = "SKU must be 1-64 characters: letters, digits, '.', '_' or '-', starting with a letter or digit")
        String sku,

        @NotBlank(message = "Description is required")
        @Size(max = 5000, message = "Description must be at most 5000 characters")
        String description,

        @NotBlank(message = "Category is required")
        @Size(max = 100, message = "Category must be at most 100 characters")
        String category,

        @Size(max = 100, message = "Brand must be at most 100 characters")
        String brand,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.00", message = "Price must not be negative")
        @Digits(integer = 10, fraction = 2, message = "Price must have at most 10 integer digits and 2 decimals")
        BigDecimal price,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter ISO code such as INR")
        String currency,

        @NotNull(message = "Stock quantity is required")
        @Min(value = 0, message = "Stock quantity must not be negative")
        @Max(value = 1_000_000_000, message = "Stock quantity is too large")
        Integer stockQuantity,

        @ValidAttributes
        Map<String, Object> attributes,

        ProductStatus status,

        Long version) {
}
