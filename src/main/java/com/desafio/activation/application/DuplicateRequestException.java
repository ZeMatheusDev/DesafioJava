package com.desafio.activation.application;

public class DuplicateRequestException extends RuntimeException {

    public DuplicateRequestException(String externalId) {
        super("Já existe uma solicitação com o externalId '%s' e dados diferentes.".formatted(externalId));
    }
}
