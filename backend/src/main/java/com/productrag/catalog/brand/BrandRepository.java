package com.productrag.catalog.brand;

import com.productrag.catalog.CatalogStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BrandRepository extends JpaRepository<Brand, UUID>, JpaSpecificationExecutor<Brand> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    Optional<Brand> findByNameIgnoreCase(String name);

    List<Brand> findAllByOrderByNameAsc();

    List<Brand> findAllByStatusOrderByNameAsc(CatalogStatus status);
}
