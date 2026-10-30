package com.desafio.activation.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    @Test
    void newRequestStartsPendingAndDueImmediately() {
        var request = newRequest();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(request.getCreatedAt()).isEqualTo(request.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void successfulFlowCountsAttemptAndUpdatesTimestamp() {
        var request = newRequest();

        request.startProcessing(NOW.plusSeconds(1));
        request.complete(NOW.plusSeconds(2));

        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void temporaryFailureReturnsToPendingWithNextAttempt() {
        var request = newRequest();
        request.startProcessing(NOW);

        Instant retryAt = NOW.plus(Duration.ofSeconds(30));
        request.scheduleRetry(retryAt, "HTTP 500", NOW);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(request.getFailureReason()).isEqualTo("HTTP 500");
    }

    @Test
    void completedRequestCannotGoBackToProcessing() {
        var request = newRequest();
        request.startProcessing(NOW);
        request.complete(NOW);

        assertThatThrownBy(() -> request.startProcessing(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("COMPLETED -> PROCESSING");
    }

    @Test
    void failedRequestIsFinal() {
        var request = newRequest();
        request.startProcessing(NOW);
        request.fail("HTTP 400", NOW);

        assertThatThrownBy(() -> request.scheduleRetry(NOW, "x", NOW)).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.complete(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void pendingRequestCannotBeCompletedWithoutBeingProcessed() {
        var request = newRequest();

        assertThatThrownBy(() -> request.complete(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.fail("x", NOW)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void comparesContentAndOwner() {
        var request = newRequest();

        assertThat(request.hasSameContent("CUST-1", "INTERNET-500")).isTrue();
        assertThat(request.hasSameContent("CUST-1", "TV-HD")).isFalse();
        assertThat(request.isOwnedBy("client-a")).isTrue();
        assertThat(request.isOwnedBy("client-b")).isFalse();
    }

    private static ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "client-a", "corr-1", NOW);
    }
}
