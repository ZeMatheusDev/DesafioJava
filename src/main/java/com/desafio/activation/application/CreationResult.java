package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;

/**
 * @param created {@code false} quando a chamada foi um retry de uma solicitação já registrada
 */
public record CreationResult(ActivationRequest request, boolean created) {
}
