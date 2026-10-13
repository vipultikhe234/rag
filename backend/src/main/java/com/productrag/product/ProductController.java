package com.productrag.product;

import com.productrag.catalog.Slugs;
import com.productrag.catalog.variant.StockFilter;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.catalog.variant.dto.VariantBatchRequest;
import com.productrag.common.api.PageRequests;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.BadRequestException;
import com.productrag.product.dto.ProductRequest;
import com.productrag.product.dto.ProductResponse;
import com.productrag.product.dto.ProductStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/products")
public class ProductController {

    /** Allowlisted sort properties (API name -> entity attribute). */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "sku", "sku",
            "slug", "slug",
            "category", "category",
            "brand", "brand",
            "price", "price",
            "stockQuantity", "stockQuantity",
            "status", "status",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<ProductResponse> list(
            @RequestParam(required = false) @Size(max = 100, message = "Search text must be at most 100 characters") String q,
            @RequestParam(required = false) @Size(max = 100) String category,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) @Size(max = 10, message = "At most 10 attribute filters are allowed")
            List<String> attr,
            @RequestParam(required = false) @DecimalMin(value = "0", message = "minPrice must not be negative")
            BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin(value = "0", message = "maxPrice must not be negative")
            BigDecimal maxPrice,
            @RequestParam(required = false) StockFilter stock,
            @RequestParam(required = false) Boolean hasVariants,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        ProductListQuery query = new ProductListQuery(q, category, categoryId, brandId, status,
                attributeFilters(attr), minPrice, maxPrice, stock, hasVariants);
        return productService.list(query, pageRequest(page, size, sort));
    }

    @GetMapping("/{id}/variants")
    public List<ProductVariantResponse> variants(@PathVariable UUID id) {
        return productService.variants(id);
    }

    /** Adds variants to an existing variant product in one transaction (all or nothing). */
    @PostMapping("/{id}/variants")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ProductVariantResponse>> addVariants(@PathVariable UUID id,
                                                                    @Valid @RequestBody VariantBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addVariants(id, request.variants()));
    }

    /** Parses repeated {@code attr=slug:value} parameters. */
    static Map<String, String> attributeFilters(List<String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> filters = new LinkedHashMap<>();
        for (String param : params) {
            int colon = param.indexOf(':');
            String key = colon > 0 ? param.substring(0, colon).trim() : "";
            String value = colon > 0 ? param.substring(colon + 1).trim() : "";
            if (!key.matches(Slugs.PATTERN) || key.length() > 64 || value.isEmpty() || value.length() > 200) {
                throw new BadRequestException("INVALID_PARAMETER",
                        "Attribute filters must look like attr=slug:value (slug of at most 64 characters, value of "
                                + "1-200 characters)");
            }
            filters.put(key, value);
        }
        return filters;
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return productService.categories();
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable UUID id) {
        return productService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ProductStatusRequest request) {
        return productService.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    static PageRequest pageRequest(int page, int size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE);
    }
}
