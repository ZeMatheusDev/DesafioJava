package com.desafio.activation.application;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffTest {

    private final RetryBackoff backoff = new RetryBackoff(
            new ProcessingProperties(20, 5, Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofMinutes(2)));

    @RepeatedTest(20)
    void growsExponentiallyWithJitter() {
        assertThat(backoff.delayFor(1, Optional.empty())).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(backoff.delayFor(2, Optional.empty())).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(backoff.delayFor(3, Optional.empty())).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @RepeatedTest(20)
    void neverExceedsMaximum() {
        assertThat(backoff.delayFor(30, Optional.empty())).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    @Test
    void respectsRetryAfterUpToMaximum() {
        assertThat(backoff.delayFor(1, Optional.of(Duration.ofSeconds(10)))).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.delayFor(1, Optional.of(Duration.ofHours(1)))).isEqualTo(Duration.ofSeconds(60));
    }
}
