package com.productrag.catalog.category;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.StatusRequest;
import com.productrag.catalog.category.dto.CategoryOption;
import com.productrag.catalog.category.dto.CategoryRequest;
import com.productrag.catalog.category.dto.CategoryResponse;
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
@RequestMapping("/api/v1/categories")
@Validated
public class CategoryController {

    static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "slug", "slug",
            "status", "status",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public PageResponse<CategoryResponse> list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) CatalogStatus status,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "false") boolean rootsOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return categoryService.list(q, status, parentId, rootsOnly, PageRequests.of(page, size, sort, SORTABLE));
    }

    @GetMapping("/options")
    public List<CategoryOption> options(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return categoryService.options(includeInactive);
    }

    @GetMapping("/{id}")
    public CategoryResponse get(@PathVariable UUID id) {
        return categoryService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = categoryService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/categories/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return categoryService.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
