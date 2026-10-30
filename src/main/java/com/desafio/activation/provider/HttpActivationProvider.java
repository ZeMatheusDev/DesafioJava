package com.desafio.activation.provider;

import com.desafio.activation.application.ActivationProvider;
import com.desafio.activation.application.ProviderResult;
import com.desafio.activation.observability.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Cliente do POST /provider/v1/activate.
 * <p>
 * O id da solicitação vai no header Idempotency-Key. Depois de um timeout não dá para saber se o
 * provedor ativou ou não; ao repetir com a mesma chave, o provedor reconhece a repetição e não
 * ativa duas vezes.
 */
@Component
public class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public HttpActivationProvider(RestClient.Builder builder, ProviderProperties properties, MeterRegistry meterRegistry) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ProviderResult activate(ActivationCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ProviderResult result = call(command);
        sample.stop(Timer.builder("activation.provider.requests")
                .description("Chamadas ao provedor de ativação")
                .tag("outcome", result.getClass().getSimpleName())
                .register(meterRegistry));
        return result;
    }

    private ProviderResult call(ActivationCommand command) {
        try {
            return restClient.post()
                    .uri(ACTIVATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY_HEADER, command.requestId().toString())
                    .headers(headers -> {
                        if (command.correlationId() != null) {
                            headers.set(CorrelationId.HEADER, command.correlationId());
                        }
                    })
                    .body(new ProviderActivationRequest(command.requestId().toString(), command.externalId(),
                            command.customerId(), command.productCode()))
                    .exchange((request, response) -> classify(response.getStatusCode(),
                            response.getHeaders().getFirst("Retry-After")), true);
        } catch (ResourceAccessException e) {
            return new ProviderResult.TransientFailure(e.getCause() instanceof SocketTimeoutException
                    ? "Timeout na chamada ao provedor"
                    : "Falha de comunicação com o provedor");
        }
    }

    static ProviderResult classify(HttpStatusCode status, String retryAfterHeader) {
        if (status.is2xxSuccessful()) {
            return new ProviderResult.Success();
        }
        String reason = "Provedor respondeu HTTP " + status.value();
        if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()
                || status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return new ProviderResult.TransientFailure(reason, parseRetryAfter(retryAfterHeader));
        }
        if (status.is5xxServerError() || status.value() == HttpStatus.REQUEST_TIMEOUT.value()) {
            return new ProviderResult.TransientFailure(reason);
        }
        return new ProviderResult.PermanentFailure(reason);
    }

    /** Retry-After aceita segundos ("120") ou data HTTP ("Wed, 21 Oct 2026 07:28:00 GMT"). */
    static Optional<Duration> parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
        } catch (NumberFormatException notSeconds) {
            try {
                var date = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration untilDate = Duration.between(ZonedDateTime.now(date.getZone()), date);
                return Optional.of(untilDate.isNegative() ? Duration.ZERO : untilDate);
            } catch (DateTimeParseException invalid) {
                return Optional.empty();
            }
        }
    }

    record ProviderActivationRequest(String requestId, String externalId, String customerId, String productCode) {
    }
}
