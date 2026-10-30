package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationRequestRepository;
import com.desafio.activation.domain.RequestStatus;
import com.desafio.activation.observability.CorrelationId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ActivationRequestService {

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Cria a solicitação ou, se o externalId já existir, trata a chamada como retry.
     * <p>
     * A consulta prévia só evita o INSERT no caso comum. Quem garante a unicidade é a constraint
     * única do banco: duas requisições simultâneas podem passar pela consulta, mas só um INSERT
     * é aceito. O perdedor recebe a violação e cai no mesmo caminho do retry.
     * <p>
     * Sem {@code @Transactional} de propósito: no PostgreSQL uma violação aborta a transação
     * corrente, então a releitura após o conflito precisa rodar numa transação nova.
     */
    public CreationResult create(NewActivationRequest command, String clientId) {
        var existing = repository.findByExternalId(command.externalId());
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), command, clientId);
        }

        var request = ActivationRequest.create(command.externalId(), command.customerId(), command.productCode(),
                clientId, CorrelationId.current(), now());
        try {
            repository.saveAndFlush(request);
            return new CreationResult(request, true);
        } catch (DataIntegrityViolationException e) {
            var winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
            return resolveExisting(winner, command, clientId);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id, String clientId) {
        // Solicitação de outro consumidor responde 404, sem revelar que o id existe.
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new RequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> search(String clientId, RequestStatus status, String customerId, Pageable pageable) {
        return repository.search(clientId, status, customerId, pageable);
    }

    private CreationResult resolveExisting(ActivationRequest existing, NewActivationRequest command, String clientId) {
        boolean sameRequest = existing.isOwnedBy(clientId)
                && existing.hasSameContent(command.customerId(), command.productCode());
        if (!sameRequest) {
            throw new DuplicateRequestException(command.externalId());
        }
        return new CreationResult(existing, false);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
