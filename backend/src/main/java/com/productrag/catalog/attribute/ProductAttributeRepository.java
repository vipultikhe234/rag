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

    boolean existsByCategoryIdAndNameIgnoreCase(UUID categoryId, String name);

    boolean existsByCategoryIdAndNameIgnoreCaseAndIdNot(UUID categoryId, String name, UUID id);

    boolean existsByCategoryIdAndSlug(UUID categoryId, String slug);

    boolean existsByCategoryIdAndSlugAndIdNot(UUID categoryId, String slug, UUID id);

    boolean existsByCategoryId(UUID categoryId);

    List<ProductAttribute> findAllByCategoryIdIn(Collection<UUID> categoryIds);

    List<ProductAttribute> findAllByCategoryIdInAndStatus(Collection<UUID> categoryIds, CatalogStatus status);

    /** Rows of [categoryId, attributeCount]. */
    @Query("select a.categoryId, count(a) from ProductAttribute a where a.categoryId in :ids group by a.categoryId")
    List<Object[]> countByCategoryIds(@Param("ids") Collection<UUID> ids);
}
