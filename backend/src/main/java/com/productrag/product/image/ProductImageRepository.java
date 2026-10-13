package com.productrag.product.image;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    List<ProductImage> findAllByProductIdOrderByPosition(UUID productId);

    List<ProductImage> findAllByProductIdInOrderByPosition(Collection<UUID> productIds);
}
