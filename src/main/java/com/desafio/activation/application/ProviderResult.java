package com.desafio.activation.application;

import java.time.Duration;
import java.util.Optional;

public sealed interface ProviderResult {

    /** Produto ativado (2xx). */
    record Success() implements ProviderResult {
    }

    /** O provedor recusou a solicitação (4xx); repetir não muda o resultado. */
    record PermanentFailure(String reason) implements ProviderResult {
    }

    /**
     * Falha que pode passar (5xx, 429, timeout, erro de rede).
     *
     * @param retryAfter prazo pedido pelo provedor via Retry-After, quando informado
     */
    record TransientFailure(String reason, Optional<Duration> retryAfter) implements ProviderResult {

        public TransientFailure(String reason) {
            this(reason, Optional.empty());
        }
    }
}
