package com.productrag.catalog.attribute;

import static com.productrag.catalog.CatalogText.checkVersion;
import static com.productrag.catalog.CatalogText.trimToNull;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.Slugs;
import com.productrag.catalog.attribute.dto.ProductAttributeRequest;
import com.productrag.catalog.attribute.dto.ProductAttributeResponse;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.common.exception.NotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category-specific attribute definitions. An attribute defined on a category also applies to all of its
 * subcategories, so a slug must be unique across a category's ancestors and descendants (product values are keyed
 * by slug). Attributes are deactivated, never deleted, so historical product specifications keep their meaning.
 */
@Service
public class ProductAttributeService {

    private final ProductAttributeRepository attributeRepository;
    private final CategoryRepository categoryRepository;

    public ProductAttributeService(ProductAttributeRepository attributeRepository,
                                   CategoryRepository categoryRepository) {
        this.attributeRepository = attributeRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductAttributeResponse> list(String search, UUID categoryId, AttributeDataType dataType,
                                                       CatalogStatus status, Boolean filterable, Pageable pageable) {
        Specification<ProductAttribute> spec = ProductAttributeSpecifications.all();
        if (search != null && !search.isBlank()) {
            spec = spec.and(ProductAttributeSpecifications.textSearch(search));
        }
        if (categoryId != null) {
            spec = spec.and(ProductAttributeSpecifications.categoryEquals(categoryId));
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
        Page<ProductAttribute> page = attributeRepository.findAll(spec, pageable);
        return PageResponse.of(page, toResponses(page.getContent()));
    }

    /**
     * Attributes that apply to products in the category: its own plus those inherited from its ancestors, ordered
     * from the root category down, then by name.
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
        List<ProductAttribute> attributes = includeInactive ? attributeRepository.findAllByCategoryIdIn(chain)
                : attributeRepository.findAllByCategoryIdInAndStatus(chain, CatalogStatus.ACTIVE);
        return attributes.stream()
                .sorted(Comparator.comparing((ProductAttribute a) -> depth.get(a.getCategoryId()))
                        .thenComparing(a -> a.getName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductAttributeResponse get(UUID id) {
        return toResponses(List.of(find(id))).getFirst();
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

    private ProductAttribute find(UUID id) {
        return attributeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ATTRIBUTE_NOT_FOUND", "Attribute " + id + " was not found"));
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
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> FieldValidationException.of("categoryId", "Category does not exist"));
        boolean categoryChanged = !category.getId().equals(attribute.getCategoryId());
        if (!category.isActive() && categoryChanged) {
            throw FieldValidationException.of("categoryId", "Attributes cannot be added to an inactive category");
        }
        UUID categoryId = category.getId();
        boolean nameTaken = id == null ? attributeRepository.existsByCategoryIdAndNameIgnoreCase(categoryId, name)
                : attributeRepository.existsByCategoryIdAndNameIgnoreCaseAndIdNot(categoryId, name, id);
        if (nameTaken) {
            throw new ConflictException("ATTRIBUTE_ALREADY_EXISTS",
                    "Category '" + category.getName() + "' already has an attribute named '" + name + "'");
        }
        ensureSlugFreeInHierarchy(categoryId, slug, id);

        List<String> options = normalizeOptions(request.dataType(), request.options());
        attribute.setCategoryId(categoryId);
        attribute.setName(name);
        attribute.setSlug(slug);
        attribute.setDataType(request.dataType());
        attribute.setOptions(options);
        attribute.setUnit(trimToNull(request.unit()));
        attribute.setRequired(Boolean.TRUE.equals(request.required()));
        attribute.setFilterable(Boolean.TRUE.equals(request.filterable()));
    }

    private void ensureSlugFreeInHierarchy(UUID categoryId, String slug, UUID id) {
        Set<UUID> related = new HashSet<>(categoryRepository.findSelfAndAncestorIds(categoryId));
        related.addAll(categoryRepository.findSelfAndDescendantIds(categoryId));
        attributeRepository.findAllByCategoryIdIn(related).stream()
                .filter(a -> a.getSlug().equals(slug) && !a.getId().equals(id))
                .findFirst()
                .ifPresent(clash -> {
                    String where = clash.getCategoryId().equals(categoryId) ? "this category"
                            : "related category '" + categoryRepository.findById(clash.getCategoryId())
                                    .map(Category::getName).orElse("?") + "'";
                    throw new ConflictException("ATTRIBUTE_ALREADY_EXISTS",
                            "An attribute with slug '" + slug + "' is already defined on " + where);
                });
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
        Set<UUID> categoryIds = attributes.stream().map(ProductAttribute::getCategoryId).collect(Collectors.toSet());
        Map<UUID, String> names = new LinkedHashMap<>();
        categoryRepository.findAllById(categoryIds).forEach(c -> names.put(c.getId(), c.getName()));
        return attributes.stream()
                .map(a -> ProductAttributeResponse.from(a, names.get(a.getCategoryId())))
                .toList();
    }
}
