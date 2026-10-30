package com.desafio.activation.application;

import com.desafio.activation.application.ActivationProvider.ActivationCommand;
import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationRequestRepository;
import com.desafio.activation.domain.InvalidStatusTransitionException;
import com.desafio.activation.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Envia as solicitações PENDING ao provedor. A tabela de solicitações funciona como outbox: a
 * solicitação é gravada na mesma transação do POST e este processador a busca depois, então
 * nada se perde se a aplicação cair entre o commit e a chamada.
 * <p>
 * Cada solicitação passa por três passos, e a chamada HTTP fica fora de transação:
 * <ol>
 *   <li>transação curta: reserva o lote (SKIP LOCKED) e marca PROCESSING;</li>
 *   <li>chamada ao provedor, sem transação nem lock aberto;</li>
 *   <li>transação curta: grava o resultado.</li>
 * </ol>
 */
@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationRequestRepository repository;
    private final ActivationProvider provider;
    private final RetryBackoff backoff;
    private final ProcessingProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ActivationProcessor(ActivationRequestRepository repository, ActivationProvider provider,
                               RetryBackoff backoff, ProcessingProperties properties,
                               TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.provider = provider;
        this.backoff = backoff;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** @return quantidade de solicitações enviadas ao provedor */
    public int processDueRequests() {
        List<ActivationCommand> claimed = transaction.execute(status -> claimDueRequests());
        claimed.forEach(this::send);
        return claimed.size();
    }

    /**
     * Devolve à fila solicitações paradas em PROCESSING além do tempo limite (a instância caiu ou
     * perdeu a resposta). Reenviar é seguro porque o provedor recebe a mesma chave de idempotência.
     */
    public int recoverStuckRequests() {
        Integer recovered = transaction.execute(status -> {
            Instant now = now();
            var stuck = repository.lockStuckInProcessing(now.minus(properties.stuckTimeout()), properties.batchSize());
            for (ActivationRequest request : stuck) {
                log.warn("Solicitação {} presa em PROCESSING desde {}; reagendando", request.getId(), request.getUpdatedAt());
                handleTransientFailure(request, new ProviderResult.TransientFailure("Processamento interrompido"), now);
            }
            return stuck.size();
        });
        return recovered;
    }

    private List<ActivationCommand> claimDueRequests() {
        Instant now = now();
        return repository.lockDueForProcessing(now, properties.batchSize()).stream()
                .map(request -> {
                    request.startProcessing(now);
                    return new ActivationCommand(request.getId(), request.getExternalId(), request.getCustomerId(),
                            request.getProductCode(), request.getCorrelationId());
                })
                .toList();
    }

    private void send(ActivationCommand command) {
        MDC.put(CorrelationId.MDC_KEY, command.correlationId());
        try {
            ProviderResult result = provider.activate(command);
            transaction.executeWithoutResult(status -> applyResult(command, result));
        } catch (ObjectOptimisticLockingFailureException | InvalidStatusTransitionException e) {
            // Outra instância (ex.: a recuperação de presas) alterou a solicitação no meio do caminho.
            log.warn("Resultado da solicitação {} descartado: {}", command.requestId(), e.getMessage());
        } catch (RuntimeException e) {
            // A solicitação fica em PROCESSING e será recuperada por recoverStuckRequests.
            log.error("Erro inesperado ao processar a solicitação {}", command.requestId(), e);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private void applyResult(ActivationCommand command, ProviderResult result) {
        Instant now = now();
        ActivationRequest request = repository.findById(command.requestId()).orElseThrow();
        switch (result) {
            case ProviderResult.Success success -> {
                request.complete(now);
                log.info("Solicitação {} ativada (tentativa {})", request.getId(), request.getAttempts());
            }
            case ProviderResult.PermanentFailure failure -> {
                request.fail(failure.reason(), now);
                log.warn("Solicitação {} recusada pelo provedor: {}", request.getId(), failure.reason());
            }
            case ProviderResult.TransientFailure failure -> handleTransientFailure(request, failure, now);
        }
    }

    private void handleTransientFailure(ActivationRequest request, ProviderResult.TransientFailure failure, Instant now) {
        if (request.getAttempts() >= properties.maxAttempts()) {
            request.fail("Limite de %d tentativas atingido. Último erro: %s"
                    .formatted(properties.maxAttempts(), failure.reason()), now);
            log.error("Solicitação {} falhou após {} tentativas: {}", request.getId(), request.getAttempts(), failure.reason());
            return;
        }
        Instant retryAt = now.plus(backoff.delayFor(request.getAttempts(), failure.retryAfter()));
        request.scheduleRetry(retryAt, failure.reason(), now);
        log.warn("Solicitação {} com falha temporária ({}); nova tentativa em {}", request.getId(), failure.reason(), retryAt);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
