package com.productrag.catalog.brand;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.StatusRequest;
import com.productrag.catalog.brand.dto.BrandOption;
import com.productrag.catalog.brand.dto.BrandRequest;
import com.productrag.catalog.brand.dto.BrandResponse;
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
@RequestMapping("/api/v1/brands")
@Validated
public class BrandController {

    static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "slug", "slug",
            "status", "status",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final BrandService brandService;

    public BrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    @GetMapping
    public PageResponse<BrandResponse> list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) CatalogStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return brandService.list(q, status, PageRequests.of(page, size, sort, SORTABLE));
    }

    @GetMapping("/options")
    public List<BrandOption> options(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return brandService.options(includeInactive);
    }

    @GetMapping("/{id}")
    public BrandResponse get(@PathVariable UUID id) {
        return brandService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BrandResponse> create(@Valid @RequestBody BrandRequest request) {
        BrandResponse created = brandService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/brands/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public BrandResponse update(@PathVariable UUID id, @Valid @RequestBody BrandRequest request) {
        return brandService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public BrandResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return brandService.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        brandService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
