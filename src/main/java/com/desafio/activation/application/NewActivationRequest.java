package com.desafio.activation.application;

public record NewActivationRequest(String externalId, String customerId, String productCode) {
}
