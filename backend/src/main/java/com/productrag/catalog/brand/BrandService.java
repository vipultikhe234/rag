package com.productrag.catalog.brand;

import static com.productrag.catalog.CatalogText.checkVersion;
import static com.productrag.catalog.CatalogText.trimToNull;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.Counts;
import com.productrag.catalog.Slugs;
import com.productrag.catalog.brand.dto.BrandOption;
import com.productrag.catalog.brand.dto.BrandRequest;
import com.productrag.catalog.brand.dto.BrandResponse;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Brand CRUD. Brands referenced by products can only be deactivated, never deleted. */
@Service
public class BrandService {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final VectorSyncOutbox outbox;

    public BrandService(BrandRepository brandRepository, ProductRepository productRepository,
                        VectorSyncOutbox outbox) {
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public PageResponse<BrandResponse> list(String search, CatalogStatus status, Pageable pageable) {
        Specification<Brand> spec = BrandSpecifications.all();
        if (search != null && !search.isBlank()) {
            spec = spec.and(BrandSpecifications.textSearch(search));
        }
        if (status != null) {
            spec = spec.and(BrandSpecifications.statusEquals(status));
        }
        Page<Brand> page = brandRepository.findAll(spec, pageable);
        return PageResponse.of(page, toResponses(page.getContent()));
    }

    @Transactional(readOnly = true)
    public List<BrandOption> options(boolean includeInactive) {
        List<Brand> brands = includeInactive ? brandRepository.findAllByOrderByNameAsc()
                : brandRepository.findAllByStatusOrderByNameAsc(CatalogStatus.ACTIVE);
        return brands.stream().map(b -> new BrandOption(b.getId(), b.getName(), b.getSlug(), b.getStatus())).toList();
    }

    @Transactional(readOnly = true)
    public BrandResponse get(UUID id) {
        return toResponses(List.of(find(id))).getFirst();
    }

    /** Brands in the order of {@code ids}; unknown ids are skipped. */
    @Transactional(readOnly = true)
    public List<BrandResponse> getAll(List<UUID> ids) {
        Map<UUID, Brand> found = new HashMap<>();
        brandRepository.findAllById(ids).forEach(brand -> found.put(brand.getId(), brand));
        return toResponses(ids.stream().map(found::get).filter(Objects::nonNull).toList());
    }

    @Transactional
    public BrandResponse create(BrandRequest request) {
        String name = request.name().trim();
        String slug = resolveSlug(request);
        if (brandRepository.existsByNameIgnoreCase(name)) {
            throw nameConflict(name);
        }
        if (brandRepository.existsBySlug(slug)) {
            throw slugConflict(slug);
        }
        Brand brand = new Brand();
        apply(brand, request, name, slug);
        brand.setStatus(request.status() == null ? CatalogStatus.ACTIVE : request.status());
        Brand saved = brandRepository.saveAndFlush(brand);
        outbox.enqueueEntity(CatalogEntityType.BRAND, saved.getId());
        return get(saved.getId());
    }

    @Transactional
    public BrandResponse update(UUID id, BrandRequest request) {
        Brand brand = find(id);
        checkVersion(request.version(), brand.getVersion(), "brand");
        String name = request.name().trim();
        String slug = resolveSlug(request);
        if (brandRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw nameConflict(name);
        }
        if (brandRepository.existsBySlugAndIdNot(slug, id)) {
            throw slugConflict(slug);
        }
        boolean renamed = !name.equals(brand.getName());
        String previousDescription = brand.getDescription();
        apply(brand, request, name, slug);
        if (request.status() != null) {
            brand.setStatus(request.status());
        }
        Brand saved = brandRepository.saveAndFlush(brand);
        if (renamed) {
            propagateRename(saved);
        }
        if (renamed || !Objects.equals(previousDescription, saved.getDescription())) {
            outbox.enqueueEntity(CatalogEntityType.BRAND, saved.getId());
        }
        return get(saved.getId());
    }

    /** Deactivation keeps existing product associations; it only stops the brand being assigned to new products. */
    @Transactional
    public BrandResponse changeStatus(UUID id, CatalogStatus status) {
        Brand brand = find(id);
        if (brand.getStatus() != status) {
            brand.setStatus(status);
            brandRepository.saveAndFlush(brand);
        }
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        Brand brand = find(id);
        if (productRepository.existsByBrandId(id)) {
            throw new ConflictException("BRAND_IN_USE",
                    "The brand is assigned to products. Deactivate it instead of deleting it.");
        }
        outbox.enqueueEntityDeleted(CatalogEntityType.BRAND, id);
        brandRepository.delete(brand);
        brandRepository.flush();
    }

    private Brand find(UUID id) {
        return brandRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("BRAND_NOT_FOUND", "Brand " + id + " was not found"));
    }

    private static void apply(Brand brand, BrandRequest request, String name, String slug) {
        brand.setName(name);
        brand.setSlug(slug);
        brand.setDescription(trimToNull(request.description()));
        brand.setLogoUrl(trimToNull(request.logoUrl()));
        brand.setWebsiteUrl(trimToNull(request.websiteUrl()));
    }

    private static String resolveSlug(BrandRequest request) {
        String slug = Slugs.resolve(request.slug(), request.name());
        if (slug.isEmpty()) {
            throw FieldValidationException.of("slug", "A slug could not be derived from the name; enter one");
        }
        if (slug.length() > 120) {
            throw FieldValidationException.of("slug", "Slug must be at most 120 characters");
        }
        return slug;
    }

    private void propagateRename(Brand brand) {
        List<Product> products = productRepository.findAllByBrandId(brand.getId());
        for (Product product : products) {
            product.setBrand(brand.getName());
        }
        productRepository.saveAllAndFlush(products);
        products.forEach(product -> outbox.enqueue(product, VectorSyncEventType.PRODUCT_UPDATED));
    }

    private List<BrandResponse> toResponses(List<Brand> brands) {
        if (brands.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = Counts.toMap(productRepository.countByBrandIds(brands.stream().map(Brand::getId).toList()));
        return brands.stream().map(b -> BrandResponse.from(b, counts.getOrDefault(b.getId(), 0L))).toList();
    }

    private static ConflictException nameConflict(String name) {
        return new ConflictException("BRAND_ALREADY_EXISTS", "A brand named '" + name + "' already exists");
    }

    private static ConflictException slugConflict(String slug) {
        return new ConflictException("SLUG_ALREADY_EXISTS", "A brand with slug '" + slug + "' already exists");
    }
}
