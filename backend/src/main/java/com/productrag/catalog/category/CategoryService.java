package com.productrag.catalog.category;

import static com.productrag.catalog.CatalogText.checkVersion;
import static com.productrag.catalog.CatalogText.trimToNull;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.Counts;
import com.productrag.catalog.Slugs;
import com.productrag.catalog.attribute.ProductAttributeRepository;
import com.productrag.catalog.category.dto.CategoryOption;
import com.productrag.catalog.category.dto.CategoryRequest;
import com.productrag.catalog.category.dto.CategoryResponse;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category CRUD with a parent/child hierarchy. Categories referenced by products, subcategories or attribute
 * definitions can only be deactivated, never deleted.
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductAttributeRepository attributeRepository;
    private final VectorSyncOutbox outbox;
    private final CategoryAssignability assignability;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository,
                           ProductAttributeRepository attributeRepository, VectorSyncOutbox outbox,
                           CategoryAssignability assignability) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.attributeRepository = attributeRepository;
        this.outbox = outbox;
        this.assignability = assignability;
    }

    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> list(String search, CatalogStatus status, UUID parentId, boolean rootsOnly,
                                               Pageable pageable) {
        Specification<Category> spec = CategorySpecifications.all();
        if (search != null && !search.isBlank()) {
            spec = spec.and(CategorySpecifications.textSearch(search));
        }
        if (status != null) {
            spec = spec.and(CategorySpecifications.statusEquals(status));
        }
        if (parentId != null) {
            spec = spec.and(CategorySpecifications.parentEquals(parentId));
        } else if (rootsOnly) {
            spec = spec.and(CategorySpecifications.rootsOnly());
        }
        Page<Category> page = categoryRepository.findAll(spec, pageable);
        return PageResponse.of(page, toResponses(page.getContent()));
    }

    /** Every category (or only active ones) with its full path, sorted by path, for dropdowns. */
    @Transactional(readOnly = true)
    public List<CategoryOption> options(boolean includeInactive) {
        List<Category> all = categoryRepository.findAllByOrderByNameAsc();
        Map<UUID, Category> byId = all.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        return all.stream()
                .filter(c -> includeInactive || c.isActive())
                .map(c -> new CategoryOption(c.getId(), c.getName(), c.getSlug(), c.getParentId(), path(c, byId),
                        c.getStatus(), CategoryAssignability.firstInactive(c, byId).isEmpty()))
                .sorted((a, b) -> a.path().compareToIgnoreCase(b.path()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(UUID id) {
        return toResponses(List.of(find(id))).getFirst();
    }

    /** Categories in the order of {@code ids}; unknown ids are skipped. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> getAll(List<UUID> ids) {
        Map<UUID, Category> found = categoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        return toResponses(ids.stream().map(found::get).filter(Objects::nonNull).toList());
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        String name = request.name().trim();
        String slug = resolveSlug(request);
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw nameConflict(name);
        }
        if (categoryRepository.existsBySlug(slug)) {
            throw slugConflict(slug);
        }
        Category category = new Category();
        validateParent(null, request.parentId(), null);
        apply(category, request, name, slug);
        category.setStatus(request.status() == null ? CatalogStatus.ACTIVE : request.status());
        Category saved = categoryRepository.saveAndFlush(category);
        // The new category and its ancestors, whose documents list their subcategories.
        outbox.enqueueCategoryChains(List.of(saved.getId()));
        return get(saved.getId());
    }

    @Transactional
    public CategoryResponse update(UUID id, CategoryRequest request) {
        Category category = find(id);
        checkVersion(request.version(), category.getVersion(), "category");
        String name = request.name().trim();
        String slug = resolveSlug(request);
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw nameConflict(name);
        }
        if (categoryRepository.existsBySlugAndIdNot(slug, id)) {
            throw slugConflict(slug);
        }
        validateParent(id, request.parentId(), category.getParentId());
        boolean renamed = !name.equals(category.getName());
        boolean moved = !Objects.equals(request.parentId(), category.getParentId());
        UUID previousParentId = category.getParentId();
        String previousDescription = category.getDescription();
        apply(category, request, name, slug);
        if (request.status() != null) {
            category.setStatus(request.status());
        }
        Category saved = categoryRepository.saveAndFlush(category);
        if (renamed) {
            propagateRename(saved);
        }
        if (renamed || moved) {
            // Search documents contain the full category path, so products in subcategories change too.
            List<UUID> subtree = categoryRepository.findSelfAndDescendantIds(saved.getId());
            outbox.enqueueForCategories(subtree);
            outbox.enqueueEntities(CatalogEntityType.CATEGORY, subtree);
            // Old and new ancestors list their subcategories and the products below them.
            outbox.enqueueCategoryChains(Arrays.asList(saved.getParentId(), previousParentId));
            if (renamed) {
                outbox.enqueueAttributesOfCategories(List.of(saved.getId()));
            }
        } else if (!Objects.equals(previousDescription, saved.getDescription())) {
            outbox.enqueueEntity(CatalogEntityType.CATEGORY, saved.getId());
        }
        return get(saved.getId());
    }

    /** Deactivation keeps every reference intact; it only stops the category being assigned to new products. */
    @Transactional
    public CategoryResponse changeStatus(UUID id, CatalogStatus status) {
        Category category = find(id);
        if (category.getStatus() != status) {
            category.setStatus(status);
            categoryRepository.saveAndFlush(category);
        }
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        Category category = find(id);
        if (productRepository.existsByCategoryId(id)) {
            throw new ConflictException("CATEGORY_IN_USE",
                    "The category is assigned to products. Deactivate it instead of deleting it.");
        }
        if (categoryRepository.existsByParentId(id)) {
            throw new ConflictException("CATEGORY_HAS_SUBCATEGORIES",
                    "The category has subcategories. Move or delete them first, or deactivate the category.");
        }
        if (attributeRepository.existsAssignedToCategory(id)) {
            throw new ConflictException("CATEGORY_HAS_ATTRIBUTES",
                    "The category has attribute definitions. Deactivate the category instead of deleting it.");
        }
        outbox.enqueueEntityDeleted(CatalogEntityType.CATEGORY, id);
        if (category.getParentId() != null) {
            outbox.enqueueCategoryChains(List.of(category.getParentId()));
        }
        categoryRepository.delete(category);
        categoryRepository.flush();
    }

    Category find(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("CATEGORY_NOT_FOUND", "Category " + id + " was not found"));
    }

    private void validateParent(UUID id, UUID parentId, UUID currentParentId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(id)) {
            throw FieldValidationException.of("parentId", "A category cannot be its own parent");
        }
        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> FieldValidationException.of("parentId", "Parent category does not exist"));
        if (id != null && categoryRepository.findSelfAndAncestorIds(parentId).contains(id)) {
            throw FieldValidationException.of("parentId",
                    "The selected parent is a subcategory of this category; that would create a cycle");
        }
        if (!parentId.equals(currentParentId)) {
            assignability.blocker(parent).ifPresent(blocker -> {
                throw FieldValidationException.of("parentId",
                        CategoryAssignability.message(parent, blocker, "chosen as a parent"));
            });
        }
    }

    private static void apply(Category category, CategoryRequest request, String name, String slug) {
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(trimToNull(request.description()));
        category.setImageUrl(trimToNull(request.imageUrl()));
        category.setParentId(request.parentId());
    }

    private static String resolveSlug(CategoryRequest request) {
        String slug = Slugs.resolve(request.slug(), request.name());
        if (slug.isEmpty()) {
            throw FieldValidationException.of("slug", "A slug could not be derived from the name; enter one");
        }
        if (slug.length() > 120) {
            throw FieldValidationException.of("slug", "Slug must be at most 120 characters");
        }
        return slug;
    }

    /** Products keep the category name as denormalized text (used by search and embeddings), so re-sync them. */
    private void propagateRename(Category category) {
        List<Product> products = productRepository.findAllByCategoryId(category.getId());
        for (Product product : products) {
            product.setCategory(category.getName());
        }
        productRepository.saveAllAndFlush(products);
        products.forEach(product -> outbox.enqueue(product, VectorSyncEventType.PRODUCT_UPDATED));
    }

    private List<CategoryResponse> toResponses(List<Category> categories) {
        if (categories.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = categories.stream().map(Category::getId).toList();
        Map<UUID, Long> products = Counts.toMap(productRepository.countByCategoryIds(ids));
        Map<UUID, Long> children = Counts.toMap(categoryRepository.countChildren(ids));
        Map<UUID, Long> attributes = Counts.toMap(attributeRepository.countByCategoryIds(ids));
        Set<UUID> parentIds = categories.stream().map(Category::getParentId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> parentNames = parentIds.isEmpty() ? Collections.emptyMap()
                : categoryRepository.findAllById(parentIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName));
        List<CategoryResponse> responses = new ArrayList<>(categories.size());
        for (Category c : categories) {
            responses.add(CategoryResponse.from(c, c.getParentId() == null ? null : parentNames.get(c.getParentId()),
                    products.getOrDefault(c.getId(), 0L), children.getOrDefault(c.getId(), 0L),
                    attributes.getOrDefault(c.getId(), 0L)));
        }
        return responses;
    }

    private static String path(Category category, Map<UUID, Category> byId) {
        List<String> names = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Category current = category;
        while (current != null && seen.add(current.getId())) {
            names.addFirst(current.getName());
            current = current.getParentId() == null ? null : byId.get(current.getParentId());
        }
        return String.join(" / ", names);
    }

    private static ConflictException nameConflict(String name) {
        return new ConflictException("CATEGORY_ALREADY_EXISTS", "A category named '" + name + "' already exists");
    }

    private static ConflictException slugConflict(String slug) {
        return new ConflictException("SLUG_ALREADY_EXISTS", "A category with slug '" + slug + "' already exists");
    }
}
