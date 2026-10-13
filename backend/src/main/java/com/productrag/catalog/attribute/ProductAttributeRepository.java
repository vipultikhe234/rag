package com.productrag.catalog.attribute;

import com.productrag.catalog.CatalogStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductAttributeRepository
        extends JpaRepository<ProductAttribute, UUID>, JpaSpecificationExecutor<ProductAttribute> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    @Query("select count(a) > 0 from ProductAttribute a join a.categoryIds c where c = :categoryId")
    boolean existsAssignedToCategory(@Param("categoryId") UUID categoryId);

    /** Attributes assigned to at least one of the categories. */
    @Query("select distinct a from ProductAttribute a join a.categoryIds c where c in :categoryIds")
    List<ProductAttribute> findAllAssignedToAny(@Param("categoryIds") Collection<UUID> categoryIds);

    @Query("select distinct a from ProductAttribute a join a.categoryIds c where c in :categoryIds and a.status = :status")
    List<ProductAttribute> findAllAssignedToAnyWithStatus(@Param("categoryIds") Collection<UUID> categoryIds,
                                                          @Param("status") CatalogStatus status);

    /** Rows of [categoryId, attributeCount]. */
    @Query("select c, count(a) from ProductAttribute a join a.categoryIds c where c in :ids group by c")
    List<Object[]> countByCategoryIds(@Param("ids") Collection<UUID> ids);
}
