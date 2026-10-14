package com.productrag.catalog.variant;

import static com.productrag.catalog.CatalogText.checkVersion;
import static com.productrag.catalog.CatalogText.trimToNull;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.AttributeValueValidator;
import com.productrag.catalog.category.Category;
import com.productrag.catalog.category.CategoryRepository;
import com.productrag.catalog.option.ProductOptionService;
import com.productrag.catalog.option.ProductOptionService.Combination;
import com.productrag.catalog.option.dto.SelectedOption;
import com.productrag.catalog.variant.dto.ProductVariantRequest;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.catalog.variant.dto.VariantDraft;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.BadRequestException;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.embedding.CatalogEntityType;
import com.productrag.config.AppProperties;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.image.ProductImage;
import com.productrag.product.image.ProductImageResponse;
import com.productrag.product.image.ProductImageService;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product variants: one sellable combination of the product's option values with its own SKU, price and stock.
 * Variants are deactivated rather than deleted so order history and other references keep resolving. Every write
 * locks the parent product row, so combination checks and the product's price/stock summary stay consistent.
 */
@Service
public class ProductVariantService {

    public static final int MAX_VARIANTS_PER_PRODUCT = 100;
    static final int MAX_OPTION_FILTERS = 5;

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final AttributeValueValidator attributeValueValidator;
    private final ProductOptionService optionService;
    private final ProductImageService imageService;
    private final VectorSyncOutbox outbox;
    private final int lowStockThreshold;

    public ProductVariantService(ProductVariantRepository variantRepository, ProductRepository productRepository,
                                 CategoryRepository categoryRepository, AttributeValueValidator attributeValueValidator,
                                 ProductOptionService optionService, ProductImageService imageService,
                                 VectorSyncOutbox outbox, AppProperties properties) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.attributeValueValidator = attributeValueValidator;
        this.optionService = optionService;
        this.imageService = imageService;
        this.outbox = outbox;
        this.lowStockThreshold = properties.products().lowStockThreshold();
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductVariantResponse> list(VariantListQuery query, Pageable pageable) {
        if (query.minPrice() != null && query.maxPrice() != null && query.minPrice().compareTo(query.maxPrice()) > 0) {
            throw new BadRequestException("INVALID_PARAMETER", "minPrice must not be greater than maxPrice");
        }
        if (query.options().size() > MAX_OPTION_FILTERS) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "At most " + MAX_OPTION_FILTERS + " option filters are allowed");
        }
        Specification<ProductVariant> spec = ProductVariantSpecifications.all();
        if (query.search() != null && !query.search().isBlank()) {
            spec = spec.and(ProductVariantSpecifications.textSearch(query.search()));
        }
        if (query.productId() != null) {
            spec = spec.and(ProductVariantSpecifications.productEquals(query.productId()));
        }
        if (query.sku() != null && !query.sku().isBlank()) {
            spec = spec.and(ProductVariantSpecifications.skuContains(query.sku()));
        }
        if (query.categoryId() != null) {
            spec = spec.and(ProductVariantSpecifications.productCategoryIn(categoryWithDescendants(query.categoryId())));
        }
        if (query.brandId() != null) {
            spec = spec.and(ProductVariantSpecifications.productBrandEquals(query.brandId()));
        }
        if (query.status() != null) {
            spec = spec.and(ProductVariantSpecifications.statusEquals(query.status()));
        }
        if (query.minPrice() != null) {
            spec = spec.and(ProductVariantSpecifications.priceAtLeast(query.minPrice()));
        }
        if (query.maxPrice() != null) {
            spec = spec.and(ProductVariantSpecifications.priceAtMost(query.maxPrice()));
        }
        if (query.stock() != null) {
            spec = spec.and(switch (query.stock()) {
                case AVAILABLE -> ProductVariantSpecifications.available();
                case IN_STOCK -> ProductVariantSpecifications.stockLevel(StockLevel.IN_STOCK, lowStockThreshold);
                case LOW_STOCK -> ProductVariantSpecifications.stockLevel(StockLevel.LOW_STOCK, lowStockThreshold);
                case OUT_OF_STOCK -> ProductVariantSpecifications.stockLevel(StockLevel.OUT_OF_STOCK, lowStockThreshold);
            });
        }
        for (Map.Entry<String, String> option : query.options().entrySet()) {
            spec = spec.and(ProductVariantSpecifications.optionEquals(option.getKey(), option.getValue()));
        }
        Page<ProductVariant> page = variantRepository.findAll(spec, pageable);
        return PageResponse.of(page, toResponses(page.getContent()));
    }

    @Transactional(readOnly = true)
    public ProductVariantResponse get(UUID id) {
        return toResponses(List.of(find(id))).get(0);
    }

    /** Variants in the order of {@code ids}; unknown ids are skipped. */
    @Transactional(readOnly = true)
    public List<ProductVariantResponse> getAll(List<UUID> ids) {
        Map<UUID, ProductVariant> found = new HashMap<>();
        variantRepository.findAllById(ids).forEach(variant -> found.put(variant.getId(), variant));
        return toResponses(ids.stream().map(found::get).filter(Objects::nonNull).toList());
    }

    /** All variants of one product, oldest first (the Variants tab of Product Details). */
    @Transactional(readOnly = true)
    public List<ProductVariantResponse> listForProduct(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw productNotFound(productId);
        }
        return toResponses(variantRepository.findAllByProductIdOrderByCreatedAtAscIdAsc(productId));
    }

    @Transactional
    public ProductVariantResponse create(ProductVariantRequest request) {
        Product product = productRepository.findByIdForUpdate(request.productId())
                .orElseThrow(() -> FieldValidationException.of("productId", "Product does not exist"));
        requireVariantProduct(product, "productId");
        List<ProductVariant> created = createAll(product, List.of(request.toDraft()), "");
        productRepository.refreshVariantSummary(product.getId());
        outbox.enqueue(product, VectorSyncEventType.PRODUCT_UPDATED);
        return toResponses(created).get(0);
    }

    /** Adds several variants to one product in a single transaction (all or nothing). */
    @Transactional
    public List<ProductVariantResponse> createForProduct(UUID productId, List<VariantDraft> drafts) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> productNotFound(productId));
        requireVariantProduct(product, "variants");
        if (drafts == null || drafts.isEmpty()) {
            throw FieldValidationException.of("variants", "Add at least one variant");
        }
        List<ProductVariant> created = createAll(product, drafts, "variants");
        productRepository.refreshVariantSummary(product.getId());
        outbox.enqueue(product, VectorSyncEventType.PRODUCT_UPDATED);
        return toResponses(created);
    }

    /**
     * Inserts new variants for a product the caller has locked (or just created). Field errors are reported as
     * {@code <fieldPrefix>[i].<field>}, or as plain field names when the prefix is empty (single create).
     * Does not refresh the product summary.
     */
    @Transactional
    public List<ProductVariant> createAll(Product product, List<VariantDraft> drafts, String fieldPrefix) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        long existing = variantRepository.countByProductId(product.getId());
        if (existing + drafts.size() > MAX_VARIANTS_PER_PRODUCT) {
            throw FieldValidationException.of(fieldPrefix.isEmpty() ? "productId" : fieldPrefix,
                    "A product can have at most " + MAX_VARIANTS_PER_PRODUCT + " variants (it has " + existing + ")");
        }
        List<ProductImage> images = imageService.images(product.getId());
        Set<String> skus = new HashSet<>();
        Set<String> barcodes = new HashSet<>();
        Set<String> signatures = new HashSet<>();
        List<ProductVariant> created = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            VariantDraft draft = drafts.get(i);
            String sku = normalizeSku(draft.sku());
            if (!skus.add(sku)) {
                throw FieldValidationException.of(field(fieldPrefix, i, "sku"),
                        "SKU '" + sku + "' is used by more than one variant in this request");
            }
            if (variantRepository.existsBySku(sku) || productRepository.existsBySku(sku)) {
                throw skuConflict(sku);
            }
            Combination combination = optionService.resolve(product.getId(), draft.options(),
                    field(fieldPrefix, i, "options"));
            if (!signatures.add(combination.signature())) {
                throw FieldValidationException.of(field(fieldPrefix, i, "options"),
                        "The combination '" + combination.title() + "' is listed more than once");
            }
            if (variantRepository.existsByProductIdAndOptionSignature(product.getId(), combination.signature())) {
                throw combinationConflict(combination);
            }
            String barcode = trimToNull(draft.barcode());
            if (barcode != null) {
                if (!barcodes.add(barcode)) {
                    throw FieldValidationException.of(field(fieldPrefix, i, "barcode"),
                            "Barcode '" + barcode + "' is used by more than one variant in this request");
                }
                if (variantRepository.existsByBarcode(barcode)) {
                    throw barcodeConflict(barcode);
                }
            }
            ProductVariant variant = new ProductVariant();
            variant.setProduct(product);
            apply(variant, product, draft, sku, barcode, combination, images, Map.of(), fieldPrefix, i);
            variant.setStatus(draft.status() == null ? CatalogStatus.ACTIVE : draft.status());
            variant = variantRepository.saveAndFlush(variant);
            optionService.assign(variant.getId(), product.getId(), combination);
            created.add(variant);
        }
        outbox.enqueueEntities(CatalogEntityType.VARIANT, created.stream().map(ProductVariant::getId).toList());
        return created;
    }

    @Transactional
    public ProductVariantResponse update(UUID id, ProductVariantRequest request) {
        ProductVariant variant = find(id);
        Product product = lockProduct(variant.getProduct().getId());
        checkVersion(request.version(), variant.getVersion(), "variant");
        if (request.productId() != null && !request.productId().equals(product.getId())) {
            throw FieldValidationException.of("productId", "A variant cannot be moved to another product");
        }
        String sku = normalizeSku(request.sku());
        if (!sku.equals(variant.getSku())
                && (variantRepository.existsBySkuAndIdNot(sku, id) || productRepository.existsBySku(sku))) {
            throw skuConflict(sku);
        }
        Combination combination = optionService.resolve(product.getId(), request.options(), "options");
        if (variantRepository.existsByProductIdAndOptionSignatureAndIdNot(product.getId(), combination.signature(),
                id)) {
            throw combinationConflict(combination);
        }
        String barcode = trimToNull(request.barcode());
        if (barcode != null && variantRepository.existsByBarcodeAndIdNot(barcode, id)) {
            throw barcodeConflict(barcode);
        }
        // The product's search document lists the option values of its active variants; price and stock are not in it.
        boolean searchChange = !combination.signature().equals(variant.getOptionSignature())
                || (request.status() != null && request.status() != variant.getStatus());
        // The variant's own document shows its SKU and options (the title follows the options).
        boolean ownDocumentChange = !combination.signature().equals(variant.getOptionSignature())
                || !sku.equals(variant.getSku());
        apply(variant, product, request.toDraft(), sku, barcode, combination, imageService.images(product.getId()),
                variant.getAttributes(), "", 0);
        if (request.status() != null) {
            variant.setStatus(request.status());
        }
        variantRepository.saveAndFlush(variant);
        optionService.assign(variant.getId(), product.getId(), combination);
        productRepository.refreshVariantSummary(product.getId());
        if (searchChange) {
            outbox.enqueue(product, VectorSyncEventType.PRODUCT_UPDATED);
        }
        if (ownDocumentChange) {
            outbox.enqueueEntity(CatalogEntityType.VARIANT, variant.getId());
        }
        return toResponses(List.of(variant)).get(0);
    }

    @Transactional
    public ProductVariantResponse changeStatus(UUID id, CatalogStatus status) {
        ProductVariant variant = find(id);
        if (variant.getStatus() != status) {
            Product product = lockProduct(variant.getProduct().getId());
            variant.setStatus(status);
            variantRepository.saveAndFlush(variant);
            productRepository.refreshVariantSummary(product.getId());
            outbox.enqueue(product, VectorSyncEventType.PRODUCT_UPDATED);
        }
        return toResponses(List.of(variant)).get(0);
    }

    /** Responses with option selections and image URLs loaded in batches (no per-row queries). */
    public List<ProductVariantResponse> toResponses(List<ProductVariant> variants) {
        if (variants.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<SelectedOption>> selections =
                optionService.selectionsByVariant(variants.stream().map(ProductVariant::getId).toList());
        Map<UUID, ProductImageResponse> images = imageService.byIds(
                variants.stream().map(ProductVariant::getImageId).filter(Objects::nonNull).distinct().toList());
        return variants.stream().map(v -> {
            ProductImageResponse image = v.getImageId() == null ? null : images.get(v.getImageId());
            return ProductVariantResponse.from(v, selections.getOrDefault(v.getId(), List.of()),
                    image == null ? null : image.url(), lowStockThreshold);
        }).toList();
    }

    private void apply(ProductVariant variant, Product product, VariantDraft draft, String sku, String barcode,
                       Combination combination, List<ProductImage> images, Map<String, Object> previousAttributes,
                       String fieldPrefix, int index) {
        BigDecimal price = draft.price().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal compareAtPrice = draft.compareAtPrice() == null ? null
                : draft.compareAtPrice().setScale(2, RoundingMode.UNNECESSARY);
        if (compareAtPrice != null && compareAtPrice.compareTo(price) <= 0) {
            throw FieldValidationException.of(field(fieldPrefix, index, "compareAtPrice"),
                    "Compare-at price must be higher than the price");
        }
        Map<String, Object> attributes = cleanAttributes(draft.attributes());
        if (product.getCategoryId() != null) {
            attributes = attributeValueValidator.validate(product.getCategoryId(), attributes, previousAttributes,
                    false);
        }
        variant.setSku(sku);
        variant.setVariantName(combination.title());
        variant.setOptionSignature(combination.signature());
        variant.setAttributes(attributes);
        variant.setPrice(price);
        variant.setCompareAtPrice(compareAtPrice);
        variant.setStockQuantity(draft.stockQuantity());
        variant.setBarcode(barcode);
        variant.setImageId(resolveImage(images, draft.imageId(), draft.imageUrl(),
                field(fieldPrefix, index, draft.imageId() != null ? "imageId" : "imageUrl")));
    }

    private static UUID resolveImage(List<ProductImage> images, UUID imageId, String imageUrl, String field) {
        if (imageId != null) {
            return images.stream().filter(img -> img.getId().equals(imageId)).findFirst()
                    .map(ProductImage::getId)
                    .orElseThrow(() -> FieldValidationException.of(field, "Image must be one of the product's images"));
        }
        String url = trimToNull(imageUrl);
        if (url == null) {
            return null;
        }
        return images.stream().filter(img -> img.getUrl().equals(url)).findFirst()
                .map(ProductImage::getId)
                .orElseThrow(() -> FieldValidationException.of(field, "Image must be one of the product's images"));
    }

    private Collection<UUID> categoryWithDescendants(UUID categoryId) {
        List<Category> all = categoryRepository.findAll();
        Map<UUID, List<UUID>> children = new LinkedHashMap<>();
        all.forEach(c -> {
            if (c.getParentId() != null) {
                children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
            }
        });
        Set<UUID> result = new HashSet<>();
        List<UUID> queue = new ArrayList<>(List.of(categoryId));
        while (!queue.isEmpty()) {
            UUID current = queue.remove(queue.size() - 1);
            if (result.add(current)) {
                queue.addAll(children.getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    private Product lockProduct(UUID productId) {
        return productRepository.findByIdForUpdate(productId).orElseThrow(() -> productNotFound(productId));
    }

    private static void requireVariantProduct(Product product, String field) {
        if (!product.hasVariants()) {
            throw FieldValidationException.of(field, "Product '" + product.getName()
                    + "' is a simple product. Turn on variants for it before adding variants.");
        }
    }

    private ProductVariant find(UUID id) {
        return variantRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("VARIANT_NOT_FOUND", "Variant " + id + " was not found"));
    }

    private static String field(String prefix, int index, String name) {
        return prefix.isEmpty() ? name : prefix + "[" + index + "]." + name;
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

    private static String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    private static NotFoundException productNotFound(UUID productId) {
        return new NotFoundException("PRODUCT_NOT_FOUND", "Product " + productId + " was not found");
    }

    private static ConflictException skuConflict(String sku) {
        return new ConflictException("SKU_ALREADY_EXISTS",
                "SKU '" + sku + "' is already used by another product or variant");
    }

    private static ConflictException barcodeConflict(String barcode) {
        return new ConflictException("BARCODE_ALREADY_EXISTS",
                "Barcode '" + barcode + "' is already used by another variant");
    }

    private static ConflictException combinationConflict(Combination combination) {
        return new ConflictException("VARIANT_COMBINATION_EXISTS",
                "A variant with the options '" + combination.title() + "' already exists for this product");
    }
}
