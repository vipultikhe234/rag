package com.productrag.product;

import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.config.AppProperties;
import com.productrag.product.dto.ProductRequest;
import com.productrag.product.dto.ProductResponse;
import com.productrag.vectorsync.ProductSyncStatusService;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncOutbox;
import com.productrag.vectorsync.dto.ProductSyncInfo;
import java.math.RoundingMode;
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
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final VectorSyncOutbox outbox;
    private final ProductSyncStatusService syncStatusService;
    private final String defaultCurrency;

    public ProductService(ProductRepository productRepository, VectorSyncOutbox outbox,
                          ProductSyncStatusService syncStatusService, AppProperties properties) {
        this.productRepository = productRepository;
        this.outbox = outbox;
        this.syncStatusService = syncStatusService;
        this.defaultCurrency = properties.products().defaultCurrency();
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        String sku = normalizeSku(request.sku());
        if (productRepository.existsBySku(sku)) {
            throw skuConflict(sku);
        }
        Product product = new Product();
        apply(product, request, sku);
        product.setStatus(request.status() == null ? ProductStatus.ACTIVE : request.status());
        Product saved = productRepository.saveAndFlush(product);
        outbox.enqueue(saved, VectorSyncEventType.PRODUCT_CREATED);
        return toResponse(saved, pendingSync());
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(String search, String category, ProductStatus status,
                                              Pageable pageable) {
        Specification<Product> spec = ProductSpecifications.all();
        if (search != null && !search.isBlank()) {
            spec = spec.and(ProductSpecifications.textSearch(search));
        }
        if (category != null && !category.isBlank()) {
            spec = spec.and(ProductSpecifications.categoryEquals(category));
        }
        if (status != null) {
            spec = spec.and(ProductSpecifications.statusEquals(status));
        }
        Page<Product> page = productRepository.findAll(spec, pageable);
        List<UUID> ids = page.getContent().stream().map(Product::getId).toList();
        Map<UUID, ProductSyncInfo> sync = syncStatusService.statusFor(ids);
        return PageResponse.of(page, page.getContent().stream()
                .map(product -> ProductResponse.from(product, sync.get(product.getId())))
                .toList());
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        Product product = find(id);
        return ProductResponse.from(product, syncStatusService.statusFor(id));
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        return productRepository.findDistinctCategories();
    }

    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = find(id);
        if (request.version() != null && !request.version().equals(product.getVersion())) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The product was modified by someone else. Reload it and apply your changes again.");
        }
        String sku = normalizeSku(request.sku());
        if (!sku.equals(product.getSku()) && productRepository.existsBySkuAndIdNot(sku, id)) {
            throw skuConflict(sku);
        }
        ProductStatus previousStatus = product.getStatus();
        apply(product, request, sku);
        if (request.status() != null) {
            product.setStatus(request.status());
        }
        Product saved = productRepository.saveAndFlush(product);
        outbox.enqueue(saved, VectorSyncEventType.PRODUCT_UPDATED);
        if (saved.getStatus() != previousStatus) {
            outbox.enqueue(saved, statusEventType(saved.getStatus()));
        }
        return toResponse(saved, pendingSync());
    }

    @Transactional
    public ProductResponse changeStatus(UUID id, ProductStatus status) {
        Product product = find(id);
        if (product.getStatus() == status) {
            return ProductResponse.from(product, syncStatusService.statusFor(id));
        }
        product.setStatus(status);
        Product saved = productRepository.saveAndFlush(product);
        outbox.enqueue(saved, statusEventType(status));
        return toResponse(saved, pendingSync());
    }

    @Transactional
    public void delete(UUID id) {
        Product product = find(id);
        // The outbox event keeps id/SKU/name so the worker can verify vector cleanup after the row is gone.
        // product_embeddings rows are removed by ON DELETE CASCADE in this same transaction.
        outbox.enqueue(product, VectorSyncEventType.PRODUCT_DELETED);
        productRepository.delete(product);
        productRepository.flush();
    }

    private Product find(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product " + id + " was not found"));
    }

    private void apply(Product product, ProductRequest request, String sku) {
        product.setName(request.name().trim());
        product.setSku(sku);
        product.setDescription(request.description().trim());
        product.setCategory(request.category().trim());
        product.setBrand(request.brand() == null || request.brand().isBlank() ? null : request.brand().trim());
        product.setPrice(request.price().setScale(2, RoundingMode.UNNECESSARY));
        product.setCurrency(request.currency() == null || request.currency().isBlank()
                ? defaultCurrency : request.currency().trim().toUpperCase(Locale.ROOT));
        product.setStockQuantity(request.stockQuantity());
        product.setAttributes(cleanAttributes(request.attributes()));
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

    private static ConflictException skuConflict(String sku) {
        return new ConflictException("SKU_ALREADY_EXISTS", "A product with SKU '" + sku + "' already exists");
    }

    private static ProductSyncInfo pendingSync() {
        return new ProductSyncInfo(ProductSyncInfo.PENDING, null, null, null, null, false, null, null, null);
    }

    private static ProductResponse toResponse(Product product, ProductSyncInfo syncInfo) {
        return ProductResponse.from(product, syncInfo);
    }
}
