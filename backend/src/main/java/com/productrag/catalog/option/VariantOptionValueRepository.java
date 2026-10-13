package com.productrag.catalog.option;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantOptionValueRepository extends JpaRepository<VariantOptionValue, VariantOptionValue.Key> {

    List<VariantOptionValue> findAllByVariantIdIn(Collection<UUID> variantIds);

    List<VariantOptionValue> findAllByProductId(UUID productId);

    boolean existsByOptionId(UUID optionId);

    boolean existsByOptionValueId(UUID optionValueId);
}
