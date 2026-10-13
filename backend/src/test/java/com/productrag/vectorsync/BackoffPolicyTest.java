package com.productrag.vectorsync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    @Test
    void doublesPerAttemptWithoutJitterAtMidpoint() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(10), Duration.ofMinutes(15), () -> 0.5);

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofSeconds(80));
    }

    @Test
    void isCappedAtMaximum() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(10), Duration.ofMinutes(15), () -> 1.0);

        assertThat(policy.delayAfter(50)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        BackoffPolicy low = new BackoffPolicy(Duration.ofSeconds(10), Duration.ofMinutes(15), () -> 0.0);
        BackoffPolicy high = new BackoffPolicy(Duration.ofSeconds(10), Duration.ofMinutes(15), () -> 0.999);

        assertThat(low.delayAfter(1)).isEqualTo(Duration.ofSeconds(8));
        assertThat(high.delayAfter(1)).isBetween(Duration.ofMillis(11_900), Duration.ofSeconds(12));
    }
}
