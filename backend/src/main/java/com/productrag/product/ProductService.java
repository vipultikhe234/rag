package com.productrag.product;

import com.productrag.catalog.Slugs;
import com.productrag.catalog.attribute.AttributeValueValidator;
import com.productrag.catalog.brand.Brand;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.catalog.option.ProductOptionService;
import com.productrag.catalog.option.dto.ProductOptionResponse;
import com.productrag.catalog.variant.ProductVariantRepository;
import com.productrag.catalog.variant.ProductVariantService;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.catalog.variant.dto.VariantDraft;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.BadRequestException;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.config.AppProperties;
import com.productrag.product.dto.ProductRequest;
import com.productrag.product.dto.ProductResponse;
import com.productrag.product.dto.ProductResponse.VariantSummary;
import com.productrag.product.image.ProductImageResponse;
import com.productrag.product.image.ProductImageService;
import com.productrag.vectorsync.ProductSyncStatusService;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import com.productrag.vectorsync.dto.ProductSyncInfo;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product CRUD. Every write stores the product change and its vector-sync outbox event in the same
 * transaction; vectors are then updated asynchronously (eventual consistency).
 * <p>
 * A product with variants is created together with its images, options and variants in one transaction, so a
 * failing variant leaves nothing behind. Its price and stock are a summary of the active variants.
 */
@Service
public class ProductService {

    private static final int MAX_SLUG_LENGTH = 200;

    private final ProductRepository productRepository;
    private final VectorSyncOutbox outbox;
    private final ProductSyncStatusService syncStatusService;
    private final ProductCatalogLinks catalogLinks;
    private final AttributeValueValidator attributeValueValidator;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductVariantService variantService;
    private final ProductOptionService optionService;
    private final ProductImageService imageService;
    private final EntityManager entityManager;
    private final String defaultCurrency;
    private final int lowStockThreshold;

    public ProductService(ProductRepository productRepository, VectorSyncOutbox outbox,
                          ProductSyncStatusService syncStatusService, ProductCatalogLinks catalogLinks,
                          AttributeValueValidator attributeValueValidator, CategoryRepository categoryRepository,
                          ProductVariantRepository variantRepository, ProductVariantService variantService,
                          ProductOptionService optionService, ProductImageService imageService,
                          EntityManager entityManager, AppProperties properties) {
        this.productRepository = productRepository;
        this.outbox = outbox;
        this.syncStatusService = syncStatusService;
        this.catalogLinks = catalogLinks;
        this.attributeValueValidator = attributeValueValidator;
        this.categoryRepository = categoryRepository;
        this.variantRepository = variantRepository;
        this.variantService = variantService;
        this.optionService = optionService;
        this.imageService = imageService;
        this.entityManager = entityManager;
        this.defaultCurrency = properties.products().defaultCurrency();
        this.lowStockThreshold = properties.products().lowStockThreshold();
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        String sku = normalizeSku(request.sku());
        if (productRepository.existsBySku(sku) || variantRepository.existsBySku(sku)) {
            throw skuConflict(sku);
        }
        boolean hasVariants = Boolean.TRUE.equals(request.hasVariants());
        validateShape(request, hasVariants, 0);
        Product product = new Product();
        apply(product, request, sku, hasVariants);
        product.setSlug(resolveSlug(request.slug(), request.name(), null, null));
        product.setStatus(request.status() == null ? ProductStatus.ACTIVE : request.status());
        Product saved = productRepository.saveAndFlush(product);
        imageService.sync(saved.getId(), request.images());
        if (hasVariants) {
            optionService.sync(saved, request.options());
            variantService.createAll(saved, request.variants(), "variants");
            refreshSummary(saved);
        }
        outbox.enqueue(saved, VectorSyncEventType.PRODUCT_CREATED);
        return detailed(List.of(saved), Map.of(saved.getId(), pendingSync())).get(0);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(ProductListQuery query, Pageable pageable) {
        if (query.minPrice() != null && query.maxPrice() != null && query.minPrice().compareTo(query.maxPrice()) > 0) {
            throw new BadRequestException("INVALID_PARAMETER", "minPrice must not be greater than maxPrice");
        }
        Specification<Product> spec = ProductSpecifications.all();
        if (query.search() != null && !query.search().isBlank()) {
            spec = spec.and(ProductSpecifications.textSearch(query.search()));
        }
        if (query.category() != null && !query.category().isBlank()) {
            spec = spec.and(ProductSpecifications.categoryEquals(query.category()));
        }
        if (query.categoryId() != null) {
            spec = spec.and(ProductSpecifications.categoryIdIn(
                    categoryRepository.findSelfAndDescendantIds(query.categoryId())));
        }
        if (query.brandId() != null) {
            spec = spec.and(ProductSpecifications.brandIdEquals(query.brandId()));
        }
        if (query.status() != null) {
            spec = spec.and(ProductSpecifications.statusEquals(query.status()));
        }
        if (query.hasVariants() != null) {
            spec = spec.and(ProductSpecifications.hasVariantsEquals(query.hasVariants()));
        }
        if (query.minPrice() != null || query.maxPrice() != null) {
            spec = spec.and(ProductSpecifications.sellablePriceBetween(query.minPrice(), query.maxPrice()));
        }
        if (query.stock() != null) {
            spec = spec.and(switch (query.stock()) {
                case AVAILABLE -> ProductSpecifications.stockGreaterThan(0);
                case IN_STOCK -> ProductSpecifications.stockGreaterThan(lowStockThreshold);
                case LOW_STOCK -> ProductSpecifications.stockGreaterThan(0)
                        .and(ProductSpecifications.stockLessThanOrEqual(lowStockThreshold));
                case OUT_OF_STOCK -> ProductSpecifications.stockLessThanOrEqual(0);
            });
        }
        for (Map.Entry<String, String> filter : query.attributes().entrySet()) {
            spec = spec.and(ProductSpecifications.attributeEquals(filter.getKey(), filter.getValue()));
        }
        Page<Product> page = productRepository.findAll(spec, pageable);
        List<UUID> ids = page.getContent().stream().map(Product::getId).toList();
        return PageResponse.of(page, detailed(page.getContent(), syncStatusService.statusFor(ids)));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        Product product = find(id);
        return detailed(List.of(product), syncStatusService.statusFor(List.of(id))).get(0);
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        return productRepository.findDistinctCategories();
    }

    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = productRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (request.version() != null && !request.version().equals(product.getVersion())) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The product was modified by someone else. Reload it and apply your changes again.");
        }
        String sku = normalizeSku(request.sku());
        if (!sku.equals(product.getSku())
                && (productRepository.existsBySkuAndIdNot(sku, id) || variantRepository.existsBySku(sku))) {
            throw skuConflict(sku);
        }
        boolean hasVariants = request.hasVariants() == null ? product.hasVariants() : request.hasVariants();
        long existingVariants = variantRepository.countByProductId(id);
        if (!hasVariants && existingVariants > 0) {
            throw new ConflictException("PRODUCT_HAS_VARIANTS", "The product has variants, so it cannot become a "
                    + "simple product. Deactivate the variants you no longer sell instead.");
        }
        validateShape(request, hasVariants, existingVariants);
        ProductStatus previousStatus = product.getStatus();
        apply(product, request, sku, hasVariants);
        product.setSlug(resolveSlug(request.slug(), request.name(), id, product.getSlug()));
        if (request.status() != null) {
            product.setStatus(request.status());
        }
        Product saved = productRepository.saveAndFlush(product);
        imageService.sync(id, request.images());
        if (hasVariants) {
            if (request.options() != null) {
                optionService.sync(saved, request.options());
            }
            variantService.createAll(saved, request.variants(), "variants");
            refreshSummary(saved);
        } else if (request.options() != null || !optionService.options(id).isEmpty()) {
            optionService.sync(saved, List.of());
        }
        outbox.enqueue(saved, VectorSyncEventType.PRODUCT_UPDATED);
        if (saved.getStatus() != previousStatus) {
            outbox.enqueue(saved, statusEventType(saved.getStatus()));
        }
        return detailed(List.of(saved), Map.of(id, pendingSync())).get(0);
    }

    @Transactional
    public ProductResponse changeStatus(UUID id, ProductStatus status) {
        Product product = find(id);
        if (product.getStatus() == status) {
            return detailed(List.of(product), syncStatusService.statusFor(List.of(id))).get(0);
        }
        product.setStatus(status);
        Product saved = productRepository.saveAndFlush(product);
        outbox.enqueue(saved, statusEventType(status));
        return detailed(List.of(saved), Map.of(id, pendingSync())).get(0);
    }

    @Transactional
    public void delete(UUID id) {
        Product product = find(id);
        if (variantRepository.existsByProductId(id)) {
            throw new ConflictException("PRODUCT_HAS_VARIANTS",
                    "The product has variants. Deactivate the product instead of deleting it.");
        }
        // The outbox event keeps id/SKU/name so the worker can verify vector cleanup after the row is gone.
        // product_embeddings, product_images and product_options rows are removed by ON DELETE CASCADE.
        outbox.enqueue(product, VectorSyncEventType.PRODUCT_DELETED);
        productRepository.delete(product);
        productRepository.flush();
    }

    @Transactional(readOnly = true)
    public List<ProductVariantResponse> variants(UUID productId) {
        return variantService.listForProduct(productId);
    }

    @Transactional
    public List<ProductVariantResponse> addVariants(UUID productId, List<VariantDraft> drafts) {
        return variantService.createForProduct(productId, drafts);
    }

    private Product find(UUID id) {
        return productRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    /** Simple products need price and stock and have no options; variant products need options and a variant. */
    private static void validateShape(ProductRequest request, boolean hasVariants, long existingVariants) {
        if (!hasVariants) {
            if (request.price() == null) {
                throw FieldValidationException.of("price", "Price is required");
            }
            if (request.stockQuantity() == null) {
                throw FieldValidationException.of("stockQuantity", "Stock quantity is required");
            }
            if (request.options() != null && !request.options().isEmpty()) {
                throw FieldValidationException.of("options", "Turn on variants to define options");
            }
            if (request.variants() != null && !request.variants().isEmpty()) {
                throw FieldValidationException.of("variants", "Turn on variants to add variants");
            }
            return;
        }
        boolean newOptionList = existingVariants == 0 || request.options() != null;
        if (newOptionList && (request.options() == null || request.options().isEmpty())) {
            throw FieldValidationException.of("options", "Add at least one option, such as Color or Size");
        }
        if (existingVariants == 0 && (request.variants() == null || request.variants().isEmpty())) {
            throw FieldValidationException.of("variants", "Add at least one variant");
        }
    }

    private void apply(Product product, ProductRequest request, String sku, boolean hasVariants) {
        Category category = catalogLinks.category(request, product.getCategoryId());
        Brand brand = catalogLinks.brand(request, product.getBrandId());
        Map<String, Object> attributes = attributeValueValidator.validate(category.getId(),
                cleanAttributes(request.attributes()), Objects.requireNonNullElse(product.getAttributes(), Map.of()));
        product.setName(request.name().trim());
        product.setSku(sku);
        product.setDescription(request.description().trim());
        product.setCategoryId(category.getId());
        product.setCategory(category.getName());
        product.setBrandId(brand == null ? null : brand.getId());
        product.setBrand(brand == null ? null : brand.getName());
        product.setCurrency(request.currency() == null || request.currency().isBlank()
                ? defaultCurrency : request.currency().trim().toUpperCase(Locale.ROOT));
        product.setAttributes(attributes);
        if (!hasVariants) {
            product.setPrice(request.price().setScale(2, RoundingMode.UNNECESSARY));
            product.setStockQuantity(request.stockQuantity());
        } else if (product.getPrice() == null) {
            product.setPrice(BigDecimal.ZERO.setScale(2));
            product.setStockQuantity(0);
        }
        product.setHasVariants(hasVariants);
    }

    /** Explicit slugs must be free; otherwise the current slug is kept, or one is derived from the name. */
    private String resolveSlug(String requested, String name, UUID productId, String currentSlug) {
        if (requested != null && !requested.isBlank()) {
            String slug = requested.trim().toLowerCase(Locale.ROOT);
            if (!slug.equals(currentSlug) && slugTaken(slug, productId)) {
                throw new ConflictException("SLUG_ALREADY_EXISTS", "Another product already uses the slug '"
                        + slug + "'");
            }
            return slug;
        }
        if (currentSlug != null) {
            return currentSlug;
        }
        String base = Slugs.from(name);
        if (base.isEmpty()) {
            base = "product";
        }
        if (base.length() > MAX_SLUG_LENGTH - 4) {
            base = base.substring(0, MAX_SLUG_LENGTH - 4).replaceAll("-+$", "");
        }
        String slug = base;
        for (int n = 2; slugTaken(slug, productId); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private boolean slugTaken(String slug, UUID productId) {
        return productId == null ? productRepository.existsBySlug(slug)
                : productRepository.existsBySlugAndIdNot(slug, productId);
    }

    /** Recalculates price/stock from the variants and reloads them into the managed entity. */
    private void refreshSummary(Product product) {
        productRepository.refreshVariantSummary(product.getId());
        entityManager.refresh(product);
    }

    /** Responses with images, options and variant statistics loaded in batches (no per-row queries). */
    private List<ProductResponse> detailed(List<Product> products, Map<UUID, ProductSyncInfo> sync) {
        if (products.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = products.stream().map(Product::getId).toList();
        Map<UUID, List<ProductImageResponse>> images = imageService.imagesByProduct(ids);
        List<UUID> variantProductIds = products.stream().filter(Product::hasVariants).map(Product::getId).toList();
        Map<UUID, List<ProductOptionResponse>> options = optionService.optionsByProduct(variantProductIds);
        Map<UUID, VariantSummary> stats = variantStats(variantProductIds);
        return products.stream().map(p -> ProductResponse.from(p, sync.get(p.getId()),
                images.getOrDefault(p.getId(), List.of()), options.getOrDefault(p.getId(), List.of()),
                stats.getOrDefault(p.getId(), VariantSummary.NONE))).toList();
    }

    private Map<UUID, VariantSummary> variantStats(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, VariantSummary> stats = new HashMap<>();
        for (Object[] row : variantRepository.statsByProductIds(productIds)) {
            stats.put((UUID) row[0], new VariantSummary(((Number) row[1]).longValue(),
                    row[2] == null ? 0 : ((Number) row[2]).longValue(), (BigDecimal) row[3], (BigDecimal) row[4]));
        }
        return stats;
    }

    private static Map<String, Object> cleanAttributes(Map<String, Object> attributes) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (key != null && value != null) {
                    cleaned.put(key.trim(), value instanceof String s ? s.trim() : value);
                }
            });
        }
        return cleaned;
    }

    static String normalizeSku(String sku) {
        return Objects.requireNonNull(sku).trim().toUpperCase(Locale.ROOT);
    }

    private static VectorSyncEventType statusEventType(ProductStatus status) {
        return status == ProductStatus.ACTIVE
                ? VectorSyncEventType.PRODUCT_REACTIVATED : VectorSyncEventType.PRODUCT_DEACTIVATED;
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("PRODUCT_NOT_FOUND", "Product " + id + " was not found");
    }

    private static ConflictException skuConflict(String sku) {
        return new ConflictException("SKU_ALREADY_EXISTS", "A product with SKU '" + sku + "' already exists");
    }

    private static ProductSyncInfo pendingSync() {
        return new ProductSyncInfo(ProductSyncInfo.PENDING, null, null, null, null, false, null, null, null);
    }
}
