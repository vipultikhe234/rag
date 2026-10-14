package com.productrag.embedding;

import java.util.List;
import java.util.Map;

/**
 * Related catalog data that goes into a product's search document besides the product row itself.
 *
 * @param categoryPath category names from the top-level category down to the product's own category
 * @param attributes   attribute definitions for the product's attribute keys, by slug (display name and unit)
 * @param options      option names with the values used by at least one ACTIVE variant, in display order
 */
public record ProductSearchContext(List<String> categoryPath, Map<String, AttributeLabel> attributes,
                                   List<OptionSummary> options) {

    public static final ProductSearchContext EMPTY = new ProductSearchContext(List.of(), Map.of(), List.of());

    public ProductSearchContext {
        categoryPath = List.copyOf(categoryPath);
        attributes = Map.copyOf(attributes);
        options = List.copyOf(options);
    }

    public record AttributeLabel(String name, String dataType, String unit) {
    }

    public record OptionSummary(String name, List<String> values) {

        public OptionSummary {
            values = List.copyOf(values);
        }
    }
}
