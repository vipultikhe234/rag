package com.productrag.catalog.attribute;

import static com.productrag.catalog.CatalogText.checkVersion;
import static com.productrag.catalog.CatalogText.trimToNull;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.Slugs;
import com.productrag.catalog.attribute.dto.ProductAttributeRequest;
import com.productrag.catalog.attribute.dto.ProductAttributeResponse;
import com.productrag.catalog.attribute.dto.ProductAttributeResponse.CategoryRef;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryAssignability;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.common.exception.NotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * Global attribute definitions. An attribute is assigned to one or more categories and also applies to their
 * subcategories; name and slug are unique across all attributes (product values are keyed by slug). Attributes are
 * deactivated, never deleted, so historical product specifications keep their meaning.
 */
@Service
public class ProductAttributeService {

    private final ProductAttributeRepository attributeRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryAssignability assignability;

    public ProductAttributeService(ProductAttributeRepository attributeRepository,
                                   CategoryRepository categoryRepository, CategoryAssignability assignability) {
        this.attributeRepository = attributeRepository;
        this.categoryRepository = categoryRepository;
        this.assignability = assignability;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductAttributeResponse> list(String search, UUID categoryId, AttributeDataType dataType,
                                                       CatalogStatus status, Boolean filterable, Boolean required,
                                                       Pageable pageable) {
        Specification<ProductAttribute> spec = ProductAttributeSpecifications.all();
        if (search != null && !search.isBlank()) {
            spec = spec.and(ProductAttributeSpecifications.textSearch(search));
        }
        if (categoryId != null) {
            spec = spec.and(ProductAttributeSpecifications.assignedTo(categoryId));
        }
        if (dataType != null) {
            spec = spec.and(ProductAttributeSpecifications.dataTypeEquals(dataType));
        }
        if (status != null) {
            spec = spec.and(ProductAttributeSpecifications.statusEquals(status));
        }
        if (filterable != null) {
            spec = spec.and(ProductAttributeSpecifications.filterableEquals(filterable));
        }
        if (required != null) {
            spec = spec.and(ProductAttributeSpecifications.requiredEquals(required));
        }
        Page<ProductAttribute> page = attributeRepository.findAll(spec, pageable);
        return PageResponse.of(page, toResponses(page.getContent()));
    }

    /**
     * Attributes that apply to products in the category: those assigned to it or to any ancestor, ordered from the
     * root category down, then by name.
     */
    @Transactional(readOnly = true)
    public List<ProductAttributeResponse> applicable(UUID categoryId, boolean includeInactive) {
        return toResponses(applicableDefinitions(categoryId, includeInactive));
    }

    @Transactional(readOnly = true)
    public List<ProductAttribute> applicableDefinitions(UUID categoryId, boolean includeInactive) {
        List<UUID> chain = ancestorChain(categoryId);
        if (chain.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> depth = new HashMap<>();
        for (int i = 0; i < chain.size(); i++) {
            depth.put(chain.get(i), i);
        }
        List<ProductAttribute> attributes = includeInactive ? attributeRepository.findAllAssignedToAny(chain)
                : attributeRepository.findAllAssignedToAnyWithStatus(chain, CatalogStatus.ACTIVE);
        Function<ProductAttribute, Integer> shallowest = a -> a.getCategoryIds().stream()
                .map(depth::get).filter(Objects::nonNull).min(Integer::compare).orElse(Integer.MAX_VALUE);
        return attributes.stream()
                .sorted(Comparator.comparing(shallowest).thenComparing(a -> a.getName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductAttributeResponse get(UUID id) {
        return toResponses(List.of(find(id))).getFirst();
    }

    @Transactional(readOnly = true)
    public ProductAttribute find(UUID id) {
        return attributeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ATTRIBUTE_NOT_FOUND", "Attribute " + id + " was not found"));
    }

    @Transactional
    public ProductAttributeResponse create(ProductAttributeRequest request) {
        ProductAttribute attribute = new ProductAttribute();
        validateAndApply(attribute, request, null);
        attribute.setStatus(request.status() == null ? CatalogStatus.ACTIVE : request.status());
        return get(attributeRepository.saveAndFlush(attribute).getId());
    }

    @Transactional
    public ProductAttributeResponse update(UUID id, ProductAttributeRequest request) {
        ProductAttribute attribute = find(id);
        checkVersion(request.version(), attribute.getVersion(), "attribute");
        validateAndApply(attribute, request, id);
        if (request.status() != null) {
            attribute.setStatus(request.status());
        }
        return get(attributeRepository.saveAndFlush(attribute).getId());
    }

    @Transactional
    public ProductAttributeResponse changeStatus(UUID id, CatalogStatus status) {
        ProductAttribute attribute = find(id);
        if (attribute.getStatus() != status) {
            attribute.setStatus(status);
            attributeRepository.saveAndFlush(attribute);
        }
        return get(id);
    }

    private void validateAndApply(ProductAttribute attribute, ProductAttributeRequest request, UUID id) {
        String name = request.name().trim();
        String slug = Slugs.resolve(request.slug(), name);
        if (slug.isEmpty()) {
            throw FieldValidationException.of("slug", "A slug could not be derived from the name; enter one");
        }
        if (slug.length() > 64) {
            throw FieldValidationException.of("slug", "Slug must be at most 64 characters");
        }
        Set<UUID> categoryIds = resolveCategories(request, attribute.getCategoryIds());
        boolean nameTaken = id == null ? attributeRepository.existsByNameIgnoreCase(name)
                : attributeRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (nameTaken) {
            throw new ConflictException("ATTRIBUTE_ALREADY_EXISTS", "An attribute named '" + name + "' already exists");
        }
        boolean slugTaken = id == null ? attributeRepository.existsBySlug(slug)
                : attributeRepository.existsBySlugAndIdNot(slug, id);
        if (slugTaken) {
            throw new ConflictException("SLUG_ALREADY_EXISTS", "An attribute with slug '" + slug + "' already exists");
        }
        List<String> options = normalizeOptions(request.dataType(), request.options());
        attribute.setCategoryIds(categoryIds);
        attribute.setName(name);
        attribute.setSlug(slug);
        attribute.setDataType(request.dataType());
        attribute.setOptions(options);
        attribute.setUnit(trimToNull(request.unit()));
        attribute.setRequired(Boolean.TRUE.equals(request.required()));
        attribute.setFilterable(Boolean.TRUE.equals(request.filterable()));
    }

    /** Existing assignments may stay even if the category was deactivated later; new ones must be assignable. */
    private Set<UUID> resolveCategories(ProductAttributeRequest request, Set<UUID> current) {
        Set<UUID> requested = new LinkedHashSet<>();
        if (request.categoryIds() != null) {
            requested.addAll(request.categoryIds());
        }
        if (request.categoryId() != null) {
            requested.add(request.categoryId());
        }
        if (requested.isEmpty()) {
            throw FieldValidationException.of("categoryIds", "Select at least one category");
        }
        Map<UUID, Category> found = categoryRepository.findAllById(requested).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        for (UUID categoryId : requested) {
            Category category = found.get(categoryId);
            if (category == null) {
                throw FieldValidationException.of("categoryIds", "Category " + categoryId + " does not exist");
            }
            if (!current.contains(categoryId)) {
                assignability.blocker(category).ifPresent(blocker -> {
                    throw FieldValidationException.of("categoryIds",
                            CategoryAssignability.message(category, blocker, "given new attributes"));
                });
            }
        }
        return requested;
    }

    static List<String> normalizeOptions(AttributeDataType dataType, List<String> options) {
        List<String> cleaned = options == null ? List.of()
                : options.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (dataType != AttributeDataType.SELECT) {
            if (!cleaned.isEmpty()) {
                throw FieldValidationException.of("options", "Only select attributes can have options");
            }
            return List.of();
        }
        if (cleaned.isEmpty()) {
            throw FieldValidationException.of("options", "Select attributes need at least one option");
        }
        Set<String> seen = new HashSet<>();
        for (String option : cleaned) {
            if (!seen.add(option.toLowerCase(Locale.ROOT))) {
                throw FieldValidationException.of("options", "Duplicate option '" + option + "'");
            }
        }
        return cleaned;
    }

    /** Category ids from the root down to the given category; empty when the category does not exist. */
    private List<UUID> ancestorChain(UUID categoryId) {
        List<UUID> ids = categoryRepository.findSelfAndAncestorIds(categoryId);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, UUID> parentOf = categoryRepository.findAllById(ids).stream()
                .collect(HashMap::new, (m, c) -> m.put(c.getId(), c.getParentId()), HashMap::putAll);
        List<UUID> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = categoryId;
        while (current != null && parentOf.containsKey(current) && seen.add(current)) {
            chain.addFirst(current);
            current = parentOf.get(current);
        }
        return chain;
    }

    private List<ProductAttributeResponse> toResponses(List<ProductAttribute> attributes) {
        if (attributes.isEmpty()) {
            return List.of();
        }
        Set<UUID> categoryIds = attributes.stream().flatMap(a -> a.getCategoryIds().stream())
                .collect(Collectors.toSet());
        Map<UUID, Category> categories = categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        return attributes.stream()
                .map(a -> ProductAttributeResponse.from(a, a.getCategoryIds().stream()
                        .map(categories::get).filter(Objects::nonNull)
                        .sorted(Comparator.comparing(c -> c.getName().toLowerCase(Locale.ROOT)))
                        .map(c -> new CategoryRef(c.getId(), c.getName(), c.isActive()))
                        .toList()))
                .toList();
    }
}
