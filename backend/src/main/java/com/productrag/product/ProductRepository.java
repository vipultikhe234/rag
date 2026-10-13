package com.productrag.product;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    /** Serializes variant writes per product (combination checks and the price/stock summary). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Variant products: price = lowest active variant price (all variants when none is active), stock = total stock
     * of active variants. Bypasses the entity version on purpose: the summary is not an admin edit.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE products p
            SET price = COALESCE(
                    (SELECT min(v.price) FROM product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE'),
                    (SELECT min(v.price) FROM product_variants v WHERE v.product_id = p.id),
                    p.price),
                stock_quantity = LEAST(COALESCE(
                    (SELECT sum(v.stock_quantity) FROM product_variants v
                     WHERE v.product_id = p.id AND v.status = 'ACTIVE'), 0), 2147483647)
            WHERE p.id = :id AND p.has_variants
            """, nativeQuery = true)
    int refreshVariantSummary(@Param("id") UUID id);

    Optional<Product> findBySkuAndStatus(String sku, ProductStatus status);

    List<Product> findAllByIdInAndStatus(List<UUID> ids, ProductStatus status);

    boolean existsByCategoryId(UUID categoryId);

    boolean existsByBrandId(UUID brandId);

    List<Product> findAllByCategoryId(UUID categoryId);

    List<Product> findAllByBrandId(UUID brandId);

    Optional<Product> findBySku(String sku);

    /** Rows of [categoryId, productCount]. */
    @Query("select p.categoryId, count(p) from Product p where p.categoryId in :ids group by p.categoryId")
    List<Object[]> countByCategoryIds(@Param("ids") Collection<UUID> ids);

    /** Rows of [brandId, productCount]. */
    @Query("select p.brandId, count(p) from Product p where p.brandId in :ids group by p.brandId")
    List<Object[]> countByBrandIds(@Param("ids") Collection<UUID> ids);

    @Query("select distinct p.category from Product p order by p.category")
    List<String> findDistinctCategories();

    @Query("select distinct p.category from Product p where p.status = com.productrag.product.ProductStatus.ACTIVE")
    List<String> findDistinctActiveCategories();

    /**
     * Active products whose full name appears verbatim (case-insensitive) in the given text.
     * Names shorter than 4 characters are ignored to avoid accidental matches.
     */
    @Query(value = """
            SELECT p.id FROM products p
            WHERE p.status = 'ACTIVE'
              AND char_length(p.name) >= 4
              AND strpos(lower(:text), lower(p.name)) > 0
            ORDER BY char_length(p.name) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findActiveIdsWithNameMentionedIn(@Param("text") String text, @Param("limit") int limit);
}
