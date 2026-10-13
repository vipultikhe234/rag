package com.productrag.catalog.variant;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductVariantRepository
        extends JpaRepository<ProductVariant, UUID>, JpaSpecificationExecutor<ProductVariant> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    boolean existsByProductId(UUID productId);

    /** Loads the owning product in the same query so list rows can show its name without N+1 selects. */
    @Override
    @EntityGraph(attributePaths = "product")
    Page<ProductVariant> findAll(Specification<ProductVariant> spec, Pageable pageable);

    /** Rows of [productId, variantCount]. */
    @Query("select v.product.id, count(v) from ProductVariant v where v.product.id in :ids group by v.product.id")
    List<Object[]> countByProductIds(@Param("ids") Collection<UUID> ids);
}
