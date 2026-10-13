package com.productrag.product.dto;

import com.productrag.product.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record ProductStatusRequest(@NotNull(message = "Status is required (ACTIVE or INACTIVE)") ProductStatus status) {
}
