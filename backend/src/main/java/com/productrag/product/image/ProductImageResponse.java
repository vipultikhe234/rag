package com.productrag.product.image;

import java.util.UUID;

public record ProductImageResponse(UUID id, String url, String altText, int position) {

    static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(image.getId(), image.getUrl(), image.getAltText(), image.getPosition());
    }
}
