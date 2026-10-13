package com.productrag.embedding;

import java.util.UUID;

/** A product id with its cosine similarity (1 - cosine distance) to the query vector. */
public record ScoredProduct(UUID productId, double similarity) {
}
