package com.productrag.support;

import java.util.Locale;

/**
 * Bag-of-words "embedding": each lower-cased word is hashed into a bucket and the vector is
 * L2-normalized. Texts sharing words get a high cosine similarity, which is enough to exercise
 * real pgvector similarity search in tests without calling a paid API.
 */
public final class HashingEmbedder {

    private HashingEmbedder() {
    }

    public static float[] embed(String text, int dimensions) {
        float[] vector = new float[dimensions];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() < 3) {
                continue;
            }
            int bucket = Math.floorMod(token.hashCode(), dimensions);
            vector[bucket] += 1.0f;
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) {
            vector[0] = 1.0f;
            return vector;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }
}
