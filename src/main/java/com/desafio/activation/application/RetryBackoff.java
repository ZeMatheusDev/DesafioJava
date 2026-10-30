package com.desafio.activation.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backoff exponencial com jitter ("equal jitter"): metade do intervalo é fixa e a outra metade é
 * aleatória, para que solicitações que falharam juntas não voltem todas no mesmo instante.
 */
@Component
public class RetryBackoff {

    private final Duration initial;
    private final Duration max;

    public RetryBackoff(ProcessingProperties properties) {
        this.initial = properties.initialBackoff();
        this.max = properties.maxBackoff();
    }

    /**
     * @param attempt número da tentativa que acabou de falhar (começa em 1)
     */
    public Duration delayFor(int attempt, Optional<Duration> retryAfter) {
        if (retryAfter.isPresent()) {
            return min(retryAfter.get(), max);
        }
        long exponentialMillis = initial.toMillis() << Math.min(attempt - 1, 20);
        long capped = Math.min(exponentialMillis, max.toMillis());
        long half = capped / 2;
        return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
