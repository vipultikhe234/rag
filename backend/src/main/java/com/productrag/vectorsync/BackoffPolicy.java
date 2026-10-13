package com.productrag.vectorsync;

import com.productrag.config.AppProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded exponential backoff with +/-20% jitter: initial * 2^(attempt-1), capped at max. */
@Component
public class BackoffPolicy {

    private final Duration initial;
    private final Duration max;
    private final DoubleSupplier random;

    @Autowired
    public BackoffPolicy(AppProperties properties) {
        this(properties.vectorSync().initialBackoff(), properties.vectorSync().maxBackoff(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    BackoffPolicy(Duration initial, Duration max, DoubleSupplier random) {
        this.initial = initial;
        this.max = max;
        this.random = random;
    }

    /** @param attempt 1-based number of the attempt that just failed */
    public Duration delayAfter(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long base = initial.toMillis() * (1L << exponent);
        long capped = Math.min(base, max.toMillis());
        double jitter = 0.8 + (random.getAsDouble() * 0.4);
        return Duration.ofMillis(Math.max(1, Math.min((long) (capped * jitter), max.toMillis())));
    }
}
