package com.productrag.common.ratelimit;

import com.productrag.common.exception.RateLimitExceededException;
import java.time.Duration;

/**
 * Rate limiting integration point. The default implementation is in-memory and per instance; for
 * multi-instance deployments provide a shared implementation (e.g. Redis/Bucket4j) or enforce the
 * limit at the API gateway.
 */
public interface RateLimiter {

    /**
     * Records one request for {@code key}.
     *
     * @throws RateLimitExceededException when more than {@code limit} requests happened in {@code window}
     */
    void acquire(String key, int limit, Duration window);
}
