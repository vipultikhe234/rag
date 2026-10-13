package com.productrag.catalog.attribute;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.StatusRequest;
import com.productrag.catalog.attribute.dto.ProductAttributeRequest;
import com.productrag.catalog.attribute.dto.ProductAttributeResponse;
import com.productrag.common.api.PageRequests;
import com.productrag.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** No DELETE endpoint: attributes are deactivated so historical product specifications stay interpretable. */
@RestController
@RequestMapping("/api/v1/product-attributes")
@Validated
public class ProductAttributeController {

    static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "slug", "slug",
            "dataType", "dataType",
            "status", "status",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final ProductAttributeService attributeService;

    public ProductAttributeController(ProductAttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @GetMapping
    public PageResponse<ProductAttributeResponse> list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) AttributeDataType dataType,
            @RequestParam(required = false) CatalogStatus status,
            @RequestParam(required = false) Boolean filterable,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return attributeService.list(q, categoryId, dataType, status, filterable,
                PageRequests.of(page, size, sort, SORTABLE));
    }

    /** Attributes for a product in the category, including those inherited from parent categories. */
    @GetMapping("/applicable")
    public List<ProductAttributeResponse> applicable(@RequestParam UUID categoryId,
                                                     @RequestParam(defaultValue = "false") boolean includeInactive) {
        return attributeService.applicable(categoryId, includeInactive);
    }

    @GetMapping("/{id}")
    public ProductAttributeResponse get(@PathVariable UUID id) {
        return attributeService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductAttributeResponse> create(@Valid @RequestBody ProductAttributeRequest request) {
        ProductAttributeResponse created = attributeService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/product-attributes/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductAttributeResponse update(@PathVariable UUID id,
                                           @Valid @RequestBody ProductAttributeRequest request) {
        return attributeService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductAttributeResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return attributeService.changeStatus(id, request.status());
    }
}
