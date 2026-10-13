package com.productrag.common.ratelimit;

import com.productrag.common.exception.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fixed-window counter per key, held in memory. */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public InMemoryRateLimiter() {
        this(Clock.systemUTC());
    }

    InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void acquire(String key, int limit, Duration window) {
        long now = clock.millis();
        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || now >= existing.resetAt()) {
                return new Window(now + window.toMillis(), 1);
            }
            return new Window(existing.resetAt(), existing.count() + 1);
        });
        if (current.count() > limit) {
            long retryAfter = Math.max(1, (current.resetAt() - now + 999) / 1000);
            throw new RateLimitExceededException(retryAfter);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    void evictExpired() {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> now >= entry.getValue().resetAt());
    }

    private record Window(long resetAt, int count) {
    }
}
