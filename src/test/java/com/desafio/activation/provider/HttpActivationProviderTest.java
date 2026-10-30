package com.desafio.activation.provider;

import com.desafio.activation.application.ProviderResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Tabela de decisão de cada retorno do provedor (detalhada no README). */
class HttpActivationProviderTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    void successStatusesComplete(int status) {
        assertThat(classify(status, null)).isInstanceOf(ProviderResult.Success.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 422})
    void clientErrorsArePermanent(int status) {
        assertThat(classify(status, null)).isInstanceOf(ProviderResult.PermanentFailure.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 504})
    void serverErrorsAreTransient(int status) {
        assertThat(classify(status, null))
                .isEqualTo(new ProviderResult.TransientFailure("Provedor respondeu HTTP " + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void throttlingHonoursRetryAfter(int status) {
        assertThat(classify(status, "30"))
                .isEqualTo(new ProviderResult.TransientFailure("Provedor respondeu HTTP " + status,
                        Optional.of(Duration.ofSeconds(30))));
    }

    @Test
    void ignoresInvalidRetryAfter() {
        assertThat(HttpActivationProvider.parseRetryAfter("amanhã")).isEmpty();
        assertThat(HttpActivationProvider.parseRetryAfter(null)).isEmpty();
    }

    @Test
    void acceptsRetryAfterAsPastHttpDate() {
        assertThat(HttpActivationProvider.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).contains(Duration.ZERO);
    }

    private static ProviderResult classify(int status, String retryAfter) {
        return HttpActivationProvider.classify(HttpStatusCode.valueOf(status), retryAfter);
    }
}
