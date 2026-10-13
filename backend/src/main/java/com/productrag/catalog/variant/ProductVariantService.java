package com.productrag.catalog.variant;

import static com.productrag.catalog.CatalogText.checkVersion;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.AttributeValueValidator;
import com.productrag.catalog.variant.dto.ProductVariantRequest;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.BadRequestException;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.FieldValidationException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.config.AppProperties;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product variants (size/colour/storage options with their own SKU, price and stock). Variants are deactivated
 * rather than deleted so order history and other references keep resolving.
 */
@Service
public class ProductVariantService {

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final AttributeValueValidator attributeValueValidator;
    private final int lowStockThreshold;

    public ProductVariantService(ProductVariantRepository variantRepository, ProductRepository productRepository,
                                 AttributeValueValidator attributeValueValidator, AppProperties properties) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.attributeValueValidator = attributeValueValidator;
        this.lowStockThreshold = properties.products().lowStockThreshold();
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductVariantResponse> list(String search, UUID productId, String sku, CatalogStatus status,
                                                     BigDecimal minPrice, BigDecimal maxPrice, StockFilter stock,
                                                     Pageable pageable) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BadRequestException("INVALID_PARAMETER", "minPrice must not be greater than maxPrice");
        }
        Specification<ProductVariant> spec = ProductVariantSpecifications.all();
        if (search != null && !search.isBlank()) {
            spec = spec.and(ProductVariantSpecifications.textSearch(search));
        }
        if (productId != null) {
            spec = spec.and(ProductVariantSpecifications.productEquals(productId));
        }
        if (sku != null && !sku.isBlank()) {
            spec = spec.and(ProductVariantSpecifications.skuContains(sku));
        }
        if (status != null) {
            spec = spec.and(ProductVariantSpecifications.statusEquals(status));
        }
        if (minPrice != null) {
            spec = spec.and(ProductVariantSpecifications.priceAtLeast(minPrice));
        }
        if (maxPrice != null) {
            spec = spec.and(ProductVariantSpecifications.priceAtMost(maxPrice));
        }
        if (stock != null) {
            spec = spec.and(switch (stock) {
                case AVAILABLE -> ProductVariantSpecifications.available();
                case IN_STOCK -> ProductVariantSpecifications.stockLevel(StockLevel.IN_STOCK, lowStockThreshold);
                case LOW_STOCK -> ProductVariantSpecifications.stockLevel(StockLevel.LOW_STOCK, lowStockThreshold);
                case OUT_OF_STOCK -> ProductVariantSpecifications.stockLevel(StockLevel.OUT_OF_STOCK, lowStockThreshold);
            });
        }
        Page<ProductVariant> page = variantRepository.findAll(spec, pageable);
        return PageResponse.of(page, page.getContent().stream()
                .map(v -> ProductVariantResponse.from(v, lowStockThreshold)).toList());
    }

    @Transactional(readOnly = true)
    public ProductVariantResponse get(UUID id) {
        return ProductVariantResponse.from(find(id), lowStockThreshold);
    }

    @Transactional
    public ProductVariantResponse create(ProductVariantRequest request) {
        String sku = normalizeSku(request.sku());
        if (variantRepository.existsBySku(sku) || productRepository.existsBySku(sku)) {
            throw skuConflict(sku);
        }
        ProductVariant variant = new ProductVariant();
        apply(variant, request, sku);
        variant.setStatus(request.status() == null ? CatalogStatus.ACTIVE : request.status());
        return ProductVariantResponse.from(variantRepository.saveAndFlush(variant), lowStockThreshold);
    }

    @Transactional
    public ProductVariantResponse update(UUID id, ProductVariantRequest request) {
        ProductVariant variant = find(id);
        checkVersion(request.version(), variant.getVersion(), "variant");
        String sku = normalizeSku(request.sku());
        if (!sku.equals(variant.getSku())
                && (variantRepository.existsBySkuAndIdNot(sku, id) || productRepository.existsBySku(sku))) {
            throw skuConflict(sku);
        }
        apply(variant, request, sku);
        if (request.status() != null) {
            variant.setStatus(request.status());
        }
        return ProductVariantResponse.from(variantRepository.saveAndFlush(variant), lowStockThreshold);
    }

    @Transactional
    public ProductVariantResponse changeStatus(UUID id, CatalogStatus status) {
        ProductVariant variant = find(id);
        if (variant.getStatus() != status) {
            variant.setStatus(status);
            variantRepository.saveAndFlush(variant);
        }
        return ProductVariantResponse.from(variant, lowStockThreshold);
    }

    private ProductVariant find(UUID id) {
        return variantRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("VARIANT_NOT_FOUND", "Variant " + id + " was not found"));
    }

    private void apply(ProductVariant variant, ProductVariantRequest request, String sku) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> FieldValidationException.of("productId", "Product does not exist"));
        boolean productChanged = variant.getProduct() == null || !product.getId().equals(variant.getProduct().getId());
        if (productChanged && !product.isActive()) {
            throw FieldValidationException.of("productId", "Variants cannot be added to an inactive product");
        }
        BigDecimal price = request.price().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal salePrice = request.salePrice() == null ? null
                : request.salePrice().setScale(2, RoundingMode.UNNECESSARY);
        if (salePrice != null && salePrice.compareTo(price) >= 0) {
            throw FieldValidationException.of("salePrice", "Sale price must be lower than the price");
        }
        Map<String, Object> attributes = cleanAttributes(request.attributes());
        if (product.getCategoryId() != null) {
            Map<String, Object> previous = productChanged ? Map.of() : variant.getAttributes();
            attributes = attributeValueValidator.validate(product.getCategoryId(), attributes, previous, false);
        }
        variant.setProduct(product);
        variant.setSku(sku);
        variant.setVariantName(request.variantName().trim());
        variant.setAttributes(attributes);
        variant.setPrice(price);
        variant.setSalePrice(salePrice);
        variant.setStockQuantity(request.stockQuantity());
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

    private static ConflictException skuConflict(String sku) {
        return new ConflictException("SKU_ALREADY_EXISTS",
                "SKU '" + sku + "' is already used by another product or variant");
    }
}
