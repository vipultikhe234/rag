package com.productrag.common.exception;

import java.io.IOException;

/** Raised while streaming a request body that exceeds the configured maximum size. */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long maxBytes) {
        super("Request body exceeds the maximum allowed size of " + maxBytes + " bytes");
    }
}
