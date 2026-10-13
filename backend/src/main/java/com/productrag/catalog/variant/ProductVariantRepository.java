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

    boolean existsByBarcode(String barcode);

    boolean existsByBarcodeAndIdNot(String barcode, UUID id);

    boolean existsByProductIdAndOptionSignature(UUID productId, String optionSignature);

    boolean existsByProductIdAndOptionSignatureAndIdNot(UUID productId, String optionSignature, UUID id);

    boolean existsByProductId(UUID productId);

    long countByProductId(UUID productId);

    List<ProductVariant> findAllByProductId(UUID productId);

    @EntityGraph(attributePaths = "product")
    List<ProductVariant> findAllByProductIdOrderByCreatedAtAscIdAsc(UUID productId);

    /** Loads the owning product in the same query so list rows can show its name without N+1 selects. */
    @Override
    @EntityGraph(attributePaths = "product")
    Page<ProductVariant> findAll(Specification<ProductVariant> spec, Pageable pageable);

    /** Rows of [productId, variantCount]. */
    @Query("select v.product.id, count(v) from ProductVariant v where v.product.id in :ids group by v.product.id")
    List<Object[]> countByProductIds(@Param("ids") Collection<UUID> ids);

    /** Rows of [productId, variantCount, activeCount, minActivePrice, maxActivePrice]. */
    @Query("""
            select v.product.id, count(v),
                   sum(case when v.status = com.productrag.catalog.CatalogStatus.ACTIVE then 1 else 0 end),
                   min(case when v.status = com.productrag.catalog.CatalogStatus.ACTIVE then v.price end),
                   max(case when v.status = com.productrag.catalog.CatalogStatus.ACTIVE then v.price end)
            from ProductVariant v where v.product.id in :ids group by v.product.id
            """)
    List<Object[]> statsByProductIds(@Param("ids") Collection<UUID> ids);
}
