package com.productrag.product.image;

import com.productrag.catalog.validation.HttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** An image in display order; existing images are matched by {@code id} or URL so variant references survive. */
public record ProductImageRequest(
        UUID id,

        @NotBlank(message = "Image URL is required")
        @Size(max = 500, message = "Image URL must be at most 500 characters")
        @HttpUrl
        String url,

        @Size(max = 200, message = "Alt text must be at most 200 characters")
        String altText) {
}
