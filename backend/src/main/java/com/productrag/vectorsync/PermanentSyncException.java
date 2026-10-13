package com.productrag.vectorsync;

/** A synchronization failure that retrying cannot fix (e.g. invalid data or a dimension mismatch). */
public class PermanentSyncException extends RuntimeException {

    public PermanentSyncException(String message) {
        super(message);
    }
}
