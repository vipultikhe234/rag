package com.productrag.product.dto;

import com.productrag.catalog.Slugs;
import com.productrag.catalog.option.dto.ProductOptionRequest;
import com.productrag.catalog.variant.dto.VariantDraft;
import com.productrag.product.ProductStatus;
import com.productrag.product.image.ProductImageRequest;
import com.productrag.product.validation.ValidAttributes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Create/update payload. {@code status} is optional (defaults to ACTIVE on create, unchanged on update);
 * {@code version} enables optimistic concurrency checks on update.
 * <p>
 * The category is identified by {@code categoryId}, or (for older clients) by an existing category {@code category}
 * name; one of the two is required. The brand works the same way with {@code brandId} / {@code brand} and is
 * optional.
 * <p>
 * Simple products ({@code hasVariants} false, the default on create) need {@code price} and {@code stockQuantity}.
 * Variant products define {@code options} and sell through {@code variants}; their price and stock are calculated
 * from the variants and any submitted values are ignored. On update, {@code hasVariants}, {@code images} and
 * {@code options} keep their current values when omitted, and {@code variants} only adds new variants.
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

        @Size(max = 100, message = "Category must be at most 100 characters")
        String category,

        @Size(max = 100, message = "Brand must be at most 100 characters")
        String brand,

        @DecimalMin(value = "0.00", message = "Price must not be negative")
        @Digits(integer = 10, fraction = 2, message = "Price must have at most 10 integer digits and 2 decimals")
        BigDecimal price,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter ISO code such as INR")
        String currency,

        @Min(value = 0, message = "Stock quantity must not be negative")
        @Max(value = 1_000_000_000, message = "Stock quantity is too large")
        Integer stockQuantity,

        @ValidAttributes
        Map<String, Object> attributes,

        ProductStatus status,

        Long version,

        UUID categoryId,

        UUID brandId,

        @Size(max = 200, message = "Slug must be at most 200 characters")
        @Pattern(regexp = "^$|" + Slugs.PATTERN,
                message = "Slug may contain lower-case letters, digits and single hyphens only")
        String slug,

        Boolean hasVariants,

        @Size(max = 20, message = "A product can have at most 20 images")
        List<@Valid @NotNull ProductImageRequest> images,

        @Size(max = 3, message = "A product can have at most 3 options")
        List<@Valid @NotNull ProductOptionRequest> options,

        @Size(max = 100, message = "A product can have at most 100 variants")
        List<@Valid @NotNull VariantDraft> variants) {

    /** Request without the product-management fields (slug, variants, images, options). */
    public ProductRequest(String name, String sku, String description, String category, String brand,
                          BigDecimal price, String currency, Integer stockQuantity, Map<String, Object> attributes,
                          ProductStatus status, Long version, UUID categoryId, UUID brandId) {
        this(name, sku, description, category, brand, price, currency, stockQuantity, attributes, status, version,
                categoryId, brandId, null, null, null, null, null);
    }
}
