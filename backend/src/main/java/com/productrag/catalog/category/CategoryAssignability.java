package com.productrag.catalog.category;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A category can be newly assigned (to products, attributes or as a parent) only when it and every ancestor are
 * active. Deactivating a parent therefore blocks its whole subtree without changing the subcategories' own status;
 * reactivating the parent makes the active subcategories assignable again.
 */
@Component
public class CategoryAssignability {

    private final CategoryRepository categoryRepository;

    public CategoryAssignability(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /** @return the inactive category (itself or the nearest inactive ancestor) that blocks assignment, if any */
    public Optional<Category> blocker(Category category) {
        if (!category.isActive()) {
            return Optional.of(category);
        }
        List<UUID> chain = categoryRepository.findSelfAndAncestorIds(category.getId());
        Map<UUID, Category> byId = categoryRepository.findAllById(chain).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        return firstInactive(category, byId);
    }

    /** Message for a field error, e.g. "Category 'Laptops' is under inactive category 'Electronics'". */
    public static String message(Category category, Category blocker, String purpose) {
        if (blocker.getId().equals(category.getId())) {
            return "Category '" + category.getName() + "' is inactive and cannot be " + purpose;
        }
        return "Category '" + category.getName() + "' is under inactive category '" + blocker.getName()
                + "' and cannot be " + purpose;
    }

    /** Walks from the category up to the root using an already loaded id -> category map. */
    static Optional<Category> firstInactive(Category category, Map<UUID, Category> byId) {
        Set<UUID> seen = new HashSet<>();
        Category current = category;
        while (current != null && seen.add(current.getId())) {
            if (!current.isActive()) {
                return Optional.of(current);
            }
            current = current.getParentId() == null ? null : byId.get(current.getParentId());
        }
        return Optional.empty();
    }
}
