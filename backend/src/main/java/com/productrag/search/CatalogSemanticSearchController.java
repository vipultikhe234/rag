package com.productrag.search;

import com.productrag.catalog.attribute.dto.ProductAttributeResponse;
import com.productrag.catalog.brand.dto.BrandResponse;
import com.productrag.catalog.category.dto.CategoryResponse;
import com.productrag.catalog.variant.dto.ProductVariantResponse;
import com.productrag.search.dto.CatalogSemanticSearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Natural-language search per catalog module for any signed-in user (GET on catalog paths requires authentication). */
@RestController
public class CatalogSemanticSearchController {

    private final CatalogSemanticSearchService searchService;

    public CatalogSemanticSearchController(CatalogSemanticSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/v1/brands/semantic-search")
    public CatalogSemanticSearchResponse<BrandResponse> brands(@RequestParam String q,
                                                               @RequestParam(defaultValue = "10") int limit) {
        return searchService.brands(q, limit);
    }

    @GetMapping("/api/v1/categories/semantic-search")
    public CatalogSemanticSearchResponse<CategoryResponse> categories(@RequestParam String q,
                                                                     @RequestParam(defaultValue = "10") int limit) {
        return searchService.categories(q, limit);
    }

    @GetMapping("/api/v1/product-attributes/semantic-search")
    public CatalogSemanticSearchResponse<ProductAttributeResponse> attributes(
            @RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        return searchService.attributes(q, limit);
    }

    @GetMapping("/api/v1/product-variants/semantic-search")
    public CatalogSemanticSearchResponse<ProductVariantResponse> variants(
            @RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        return searchService.variants(q, limit);
    }
}
