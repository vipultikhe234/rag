package com.productrag.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    Optional<Product> findBySkuAndStatus(String sku, ProductStatus status);

    List<Product> findAllByIdInAndStatus(List<UUID> ids, ProductStatus status);

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
