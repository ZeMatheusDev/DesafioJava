package com.desafio.activation.domain;

import java.util.Set;

public enum RequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    /**
     * PROCESSING -> PENDING representa uma falha temporária: a solicitação volta para a fila
     * com o contador de tentativas incrementado. COMPLETED e FAILED são estados finais.
     */
    public boolean canTransitionTo(RequestStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<RequestStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> Set.of(PROCESSING);
            case PROCESSING -> Set.of(COMPLETED, FAILED, PENDING);
            case COMPLETED, FAILED -> Set.of();
        };
    }

    public boolean isFinal() {
        return allowedTargets().isEmpty();
    }
}
