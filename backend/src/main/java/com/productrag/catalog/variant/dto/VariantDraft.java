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

/**
 * A new variant inside a product request (or a bulk request for one product). The image is chosen by
 * {@code imageId} or by the URL of one of the product's images ({@code imageUrl}), so variants created together with
 * their product can reference images that do not have ids yet.
 */
public record VariantDraft(
        @NotBlank(message = "SKU is required")
        @Pattern(regexp = SKU_PATTERN, message = SKU_MESSAGE)
        String sku,

        @Size(max = 3, message = "A variant has at most 3 options")
        Map<String, String> options,

        @ValidAttributes
        Map<String, Object> attributes,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.00", message = "Price must not be negative")
        @Digits(integer = 10, fraction = 2, message = "Price must have at most 10 integer digits and 2 decimals")
        BigDecimal price,

        @DecimalMin(value = "0.00", message = "Compare-at price must not be negative")
        @Digits(integer = 10, fraction = 2,
                message = "Compare-at price must have at most 10 integer digits and 2 decimals")
        BigDecimal compareAtPrice,

        @NotNull(message = "Stock quantity is required")
        @Min(value = 0, message = "Stock quantity must not be negative")
        @Max(value = 1_000_000_000, message = "Stock quantity is too large")
        Integer stockQuantity,

        @Pattern(regexp = BARCODE_PATTERN, message = BARCODE_MESSAGE)
        String barcode,

        UUID imageId,

        @Size(max = 500, message = "Image URL must be at most 500 characters")
        String imageUrl,

        CatalogStatus status) {

    public static final String SKU_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    public static final String SKU_MESSAGE =
            "SKU must be 1-64 characters: letters, digits, '.', '_' or '-', starting with a letter or digit";
    public static final String BARCODE_PATTERN = "^$|^[A-Za-z0-9-]{4,64}$";
    public static final String BARCODE_MESSAGE = "Barcode must be 4-64 letters, digits or '-'";
}
