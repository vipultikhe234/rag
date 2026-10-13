package com.productrag.product;

import com.productrag.common.api.PageRequests;
import com.productrag.common.api.PageResponse;
import com.productrag.product.dto.ProductRequest;
import com.productrag.product.dto.ProductResponse;
import com.productrag.product.dto.ProductStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
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
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return productService.list(q, category, status, pageRequest(page, size, sort));
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
