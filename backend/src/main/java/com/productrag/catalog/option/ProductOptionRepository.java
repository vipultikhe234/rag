package com.productrag.catalog.option;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionRepository extends JpaRepository<ProductOption, UUID> {

    List<ProductOption> findAllByProductIdOrderByPosition(UUID productId);

    List<ProductOption> findAllByProductIdInOrderByPosition(Collection<UUID> productIds);
}
