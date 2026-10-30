package com.desafio.activation.application;

import java.util.UUID;

/** Provedor externo que efetivamente ativa o produto. */
public interface ActivationProvider {

    /**
     * Nunca lança exceção por erro de integração: todo retorno, inclusive timeout, vira um {@link ProviderResult}.
     */
    ProviderResult activate(ActivationCommand command);

    /**
     * @param requestId enviado como chave de idempotência; é o mesmo em todas as tentativas
     */
    record ActivationCommand(UUID requestId, String externalId, String customerId, String productCode,
                             String correlationId) {
    }
}
