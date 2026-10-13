package com.productrag.embedding;

import java.time.Instant;
import java.util.UUID;

/** Stored embedding row without the vector itself. */
public record EmbeddingMetadata(
        UUID productId,
        String contentHash,
        String embeddingModel,
        long sourceVersion,
        Instant sourceUpdatedAt,
        Instant updatedAt) {
}
