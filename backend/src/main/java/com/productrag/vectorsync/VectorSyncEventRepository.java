package com.productrag.vectorsync;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VectorSyncEventRepository extends JpaRepository<VectorSyncEvent, UUID> {

    Page<VectorSyncEvent> findAllByStatus(VectorSyncStatus status, Pageable pageable);

    Page<VectorSyncEvent> findAllByAggregateId(UUID aggregateId, Pageable pageable);

    long countByStatus(VectorSyncStatus status);
}
