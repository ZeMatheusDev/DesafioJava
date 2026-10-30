package com.desafio.activation;

import com.desafio.activation.application.ActivationProcessor;
import com.desafio.activation.application.ActivationRequestService;
import com.desafio.activation.application.NewActivationRequest;
import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.RequestStatus;
import com.desafio.activation.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ActivationProcessingIntegrationTest extends IntegrationTest {

    private static final String ACTIVATE = "/provider/v1/activate";

    @Autowired
    private ActivationProcessor processor;

    @Autowired
    private ActivationRequestService service;

    @Test
    void successfulActivationCompletesRequest() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        UUID id = createRequest();

        assertThat(processor.processDueRequests()).isEqualTo(1);

        var request = load(id);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
        provider.verify(postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(id.toString()))
                .withRequestBody(equalToJson("""
                        {"requestId": "%s", "externalId": "EXT-1", "customerId": "CUST-1", "productCode": "INTERNET-500"}
                        """.formatted(id))));
    }

    @Test
    void rejectionByProviderFailsWithoutRetry() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(400)));
        UUID id = createRequest();

        processor.processDueRequests();
        clock.advance(Duration.ofHours(1));
        processor.processDueRequests();

        var request = load(id);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getFailureReason()).contains("HTTP 400");
        provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void serverErrorIsRetriedWithBackoffUntilLimit() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(500)));
        UUID id = createRequest();

        processor.processDueRequests();
        var afterFirst = load(id);
        assertThat(afterFirst.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getNextAttemptAt()).isAfter(START);

        // Antes do backoff vencer, nada é reenviado.
        assertThat(processor.processDueRequests()).isZero();

        // max-attempts=3 nos testes
        clock.advance(Duration.ofMinutes(10));
        processor.processDueRequests();
        clock.advance(Duration.ofMinutes(10));
        processor.processDueRequests();

        var request = load(id);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(3);
        assertThat(request.getFailureReason()).contains("Limite de 3 tentativas");
        provider.verify(3, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void transientFailureThenSuccessCompletes() {
        provider.stubFor(post(ACTIVATE).inScenario("instável")
                .willReturn(aResponse().withStatus(502)).willSetStateTo("recuperado"));
        provider.stubFor(post(ACTIVATE).inScenario("instável").whenScenarioStateIs("recuperado")
                .willReturn(aResponse().withStatus(200)));
        UUID id = createRequest();

        processor.processDueRequests();
        clock.advance(Duration.ofMinutes(10));
        processor.processDueRequests();

        assertThat(load(id).getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(load(id).getAttempts()).isEqualTo(2);
    }

    @Test
    void timeoutIsRetriedWithSameIdempotencyKey() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200).withFixedDelay(1_500)));
        UUID id = createRequest();

        processor.processDueRequests();

        var request = load(id);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getFailureReason()).isEqualTo("Timeout na chamada ao provedor");

        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        clock.advance(Duration.ofMinutes(10));
        processor.processDueRequests();

        assertThat(load(id).getStatus()).isEqualTo(RequestStatus.COMPLETED);
        provider.verify(2, postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("Idempotency-Key", equalTo(id.toString())));
    }

    @Test
    void retryAfterFromProviderDefinesNextAttempt() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "30")));
        UUID id = createRequest();

        processor.processDueRequests();

        assertThat(load(id).getNextAttemptAt()).isEqualTo(START.plusSeconds(30));
    }

    @Test
    void requestStuckInProcessingIsRecovered() {
        UUID id = createRequest();
        var stuck = load(id);
        stuck.startProcessing(START);
        repository.save(stuck);

        clock.advance(Duration.ofMinutes(1));
        assertThat(processor.recoverStuckRequests()).isZero();

        clock.advance(Duration.ofMinutes(5));
        assertThat(processor.recoverStuckRequests()).isEqualTo(1);
        assertThat(load(id).getStatus()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void propagatesCorrelationIdToProvider() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        MDC.put("correlationId", "corr-42");
        try {
            createRequest();
        } finally {
            MDC.clear();
        }

        processor.processDueRequests();

        provider.verify(postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("X-Correlation-Id", equalTo("corr-42")));
    }

    private UUID createRequest() {
        return service.create(new NewActivationRequest("EXT-1", "CUST-1", "INTERNET-500"), "sistema-a")
                .request().getId();
    }

    private ActivationRequest load(UUID id) {
        return repository.findById(id).orElseThrow();
    }
}
