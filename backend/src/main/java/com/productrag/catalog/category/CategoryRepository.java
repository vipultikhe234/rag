package com.productrag.catalog.category;

import com.productrag.catalog.CatalogStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID>, JpaSpecificationExecutor<Category> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    Optional<Category> findByNameIgnoreCase(String name);

    Optional<Category> findBySlug(String slug);

    boolean existsByParentId(UUID parentId);

    List<Category> findAllByStatusOrderByNameAsc(CatalogStatus status);

    List<Category> findAllByOrderByNameAsc();

    /** Rows of [parentId, childCount]. */
    @Query("select c.parentId, count(c) from Category c where c.parentId in :ids group by c.parentId")
    List<Object[]> countChildren(@Param("ids") Collection<UUID> ids);

    /** The category itself plus every descendant. UNION (not UNION ALL) also stops on corrupted cyclic data. */
    @Query(value = """
            WITH RECURSIVE tree(id) AS (
                SELECT id FROM categories WHERE id = :id
                UNION
                SELECT c.id FROM categories c JOIN tree t ON c.parent_id = t.id
            )
            SELECT id FROM tree
            """, nativeQuery = true)
    List<UUID> findSelfAndDescendantIds(@Param("id") UUID id);

    /** The category itself plus every ancestor up to the root. */
    @Query(value = """
            WITH RECURSIVE chain(id, parent_id) AS (
                SELECT id, parent_id FROM categories WHERE id = :id
                UNION
                SELECT c.id, c.parent_id FROM categories c JOIN chain ch ON c.id = ch.parent_id
            )
            SELECT id FROM chain
            """, nativeQuery = true)
    List<UUID> findSelfAndAncestorIds(@Param("id") UUID id);
}
