package com.productrag.search;

import com.productrag.catalog.variant.ProductVariantController;
import com.productrag.search.dto.SemanticSearchResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Natural-language product search for any signed-in user (GET on the products path requires authentication). */
@RestController
@Validated
@RequestMapping("/api/v1/products")
public class SemanticSearchController {

    private final SemanticProductSearchService searchService;

    public SemanticSearchController(SemanticProductSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/semantic-search")
    public SemanticSearchResponse search(
            @RequestParam String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) @DecimalMin(value = "0", message = "minPrice must not be negative")
            BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin(value = "0", message = "maxPrice must not be negative")
            BigDecimal maxPrice,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(required = false) @Size(max = 5, message = "At most 5 option filters are allowed")
            List<String> option,
            @RequestParam(defaultValue = "10") int limit) {
        return searchService.search(new SemanticSearchQuery(q, categoryId, brandId, minPrice, maxPrice, inStock,
                ProductVariantController.optionFilters(option), limit));
    }
}
