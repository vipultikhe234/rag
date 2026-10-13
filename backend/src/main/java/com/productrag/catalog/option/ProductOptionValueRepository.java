package com.productrag.catalog.option;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValue, UUID> {

    List<ProductOptionValue> findAllByOptionIdInOrderByPosition(Collection<UUID> optionIds);
}
