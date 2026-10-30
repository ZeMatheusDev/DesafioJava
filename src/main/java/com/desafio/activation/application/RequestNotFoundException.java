package com.desafio.activation.application;

import java.util.UUID;

public class RequestNotFoundException extends RuntimeException {

    public RequestNotFoundException(UUID id) {
        super("Solicitação %s não encontrada.".formatted(id));
    }
}
