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
 * Create/update payload for one variant. {@code options} maps each product option name to one of its values
 * (e.g. {"Color": "Black", "Storage Capacity": "256"}); the variant title is generated from them.
 * {@code compareAtPrice} is optional and must be higher than {@code price}. {@code imageId} must be one of the
 * product's images. The product cannot be changed after creation.
 */
public record ProductVariantRequest(
        @NotNull(message = "Product is required")
        UUID productId,

        @NotBlank(message = "SKU is required")
        @Pattern(regexp = VariantDraft.SKU_PATTERN, message = VariantDraft.SKU_MESSAGE)
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

        @Pattern(regexp = VariantDraft.BARCODE_PATTERN, message = VariantDraft.BARCODE_MESSAGE)
        String barcode,

        UUID imageId,

        CatalogStatus status,

        Long version) {

    public VariantDraft toDraft() {
        return new VariantDraft(sku, options, attributes, price, compareAtPrice, stockQuantity, barcode, imageId, null,
                status);
    }
}
