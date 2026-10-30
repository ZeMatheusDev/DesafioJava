package com.desafio.activation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "activation.processing.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ActivationScheduler {

    private final ActivationProcessor processor;

    public ActivationScheduler(ActivationProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${activation.processing.poll-interval-ms:1000}")
    public void processDueRequests() {
        processor.processDueRequests();
    }

    @Scheduled(fixedDelayString = "${activation.processing.recovery-interval-ms:60000}")
    public void recoverStuckRequests() {
        processor.recoverStuckRequests();
    }
}
