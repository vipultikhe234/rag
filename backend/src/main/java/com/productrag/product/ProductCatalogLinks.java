package com.productrag.product;

import com.productrag.catalog.brand.Brand;
import com.productrag.catalog.brand.BrandRepository;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryAssignability;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.product.dto.ProductRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the category and brand of a product request. Only active records can be newly assigned; a product
 * that already uses a since-deactivated category or brand may keep it.
 */
@Component
public class ProductCatalogLinks {

    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final CategoryAssignability assignability;

    public ProductCatalogLinks(CategoryRepository categoryRepository, BrandRepository brandRepository,
                               CategoryAssignability assignability) {
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.assignability = assignability;
    }

    /** @param currentCategoryId the product's current category, or null for a new product */
    public Category category(ProductRequest request, UUID currentCategoryId) {
        Category category;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> FieldValidationException.of("categoryId", "Category does not exist"));
        } else if (request.category() != null && !request.category().isBlank()) {
            String name = request.category().trim();
            category = categoryRepository.findByNameIgnoreCase(name)
                    .orElseThrow(() -> FieldValidationException.of("category",
                            "Unknown category '" + name + "'. Create it under Categories first."));
        } else {
            throw FieldValidationException.of("categoryId", "Category is required");
        }
        if (!Objects.equals(category.getId(), currentCategoryId)) {
            Category resolved = category;
            assignability.blocker(category).ifPresent(blocker -> {
                throw FieldValidationException.of("categoryId",
                        CategoryAssignability.message(resolved, blocker, "assigned to products"));
            });
        }
        return category;
    }

    /** @return the brand, or null when the request has none */
    public Brand brand(ProductRequest request, UUID currentBrandId) {
        Brand brand;
        if (request.brandId() != null) {
            brand = brandRepository.findById(request.brandId())
                    .orElseThrow(() -> FieldValidationException.of("brandId", "Brand does not exist"));
        } else if (request.brand() != null && !request.brand().isBlank()) {
            String name = request.brand().trim();
            brand = brandRepository.findByNameIgnoreCase(name)
                    .orElseThrow(() -> FieldValidationException.of("brand",
                            "Unknown brand '" + name + "'. Create it under Brands first."));
        } else {
            return null;
        }
        if (!brand.isActive() && !Objects.equals(brand.getId(), currentBrandId)) {
            throw FieldValidationException.of("brandId",
                    "Brand '" + brand.getName() + "' is inactive and cannot be assigned to products");
        }
        return brand;
    }
}
