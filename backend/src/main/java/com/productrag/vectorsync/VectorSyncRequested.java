package com.productrag.vectorsync;

import java.util.UUID;

/** In-process notification that a new outbox event was written; delivered only after commit. */
public record VectorSyncRequested(UUID eventId) {
}
