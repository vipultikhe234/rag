package com.productrag.embedding;

import com.productrag.product.Product;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads the related catalog data for product search documents, in batches (no per-product queries). */
@FunctionalInterface
public interface ProductSearchContextLoader {

    /** Context per product id; products without related data may be missing from the map. */
    Map<UUID, ProductSearchContext> load(Collection<Product> products);

    default ProductSearchContext load(Product product) {
        return load(List.of(product)).getOrDefault(product.getId(), ProductSearchContext.EMPTY);
    }
}
