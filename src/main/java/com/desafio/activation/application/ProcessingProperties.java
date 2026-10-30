package com.desafio.activation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param maxAttempts  total de chamadas ao provedor antes de marcar a solicitação como FAILED
 * @param stuckTimeout tempo em PROCESSING a partir do qual a solicitação é considerada abandonada
 */
@ConfigurationProperties("activation.processing")
public record ProcessingProperties(
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("2s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("2m") Duration stuckTimeout) {
}
