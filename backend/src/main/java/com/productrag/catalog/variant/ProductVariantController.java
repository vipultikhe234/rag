package com.productrag.catalog.variant;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.StatusRequest;
import com.productrag.catalog.variant.dto.ProductVariantRequest;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.common.api.PageRequests;
import com.productrag.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** No DELETE endpoint: variants are deactivated so existing references keep resolving. */
@RestController
@RequestMapping("/api/v1/product-variants")
@Validated
public class ProductVariantController {

    static final Map<String, String> SORTABLE = Map.of(
            "sku", "sku",
            "variantName", "variantName",
            "productName", "product.name",
            "price", "price",
            "stockQuantity", "stockQuantity",
            "status", "status",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final ProductVariantService variantService;

    public ProductVariantController(ProductVariantService variantService) {
        this.variantService = variantService;
    }

    @GetMapping
    public PageResponse<ProductVariantResponse> list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) @Size(max = 64) String sku,
            @RequestParam(required = false) CatalogStatus status,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
            @RequestParam(required = false) StockFilter stock,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return variantService.list(q, productId, sku, status, minPrice, maxPrice, stock,
                PageRequests.of(page, size, sort, SORTABLE));
    }

    @GetMapping("/{id}")
    public ProductVariantResponse get(@PathVariable UUID id) {
        return variantService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductVariantResponse> create(@Valid @RequestBody ProductVariantRequest request) {
        ProductVariantResponse created = variantService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/product-variants/" + created.id())).body(created);
    }

    @RequestMapping(path = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("hasRole('ADMIN')")
    public ProductVariantResponse update(@PathVariable UUID id, @Valid @RequestBody ProductVariantRequest request) {
        return variantService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductVariantResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return variantService.changeStatus(id, request.status());
    }
}
