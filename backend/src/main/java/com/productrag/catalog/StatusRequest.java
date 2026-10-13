package com.productrag.catalog;

import jakarta.validation.constraints.NotNull;

public record StatusRequest(@NotNull(message = "Status is required") CatalogStatus status) {
}
