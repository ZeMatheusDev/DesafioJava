package com.desafio.activation.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    @Query("""
            select r from ActivationRequest r
            where r.clientId = :clientId
              and (:status is null or r.status = :status)
              and (:customerId is null or r.customerId = :customerId)
            """)
    Page<ActivationRequest> search(@Param("clientId") String clientId,
                                   @Param("status") RequestStatus status,
                                   @Param("customerId") String customerId,
                                   Pageable pageable);

    /**
     * Reserva solicitações prontas para envio. O SKIP LOCKED permite várias instâncias
     * consumindo a mesma "fila" sem processar a mesma linha duas vezes.
     */
    @Query(value = """
            select * from activation_request
            where status = 'PENDING' and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<ActivationRequest> lockDueForProcessing(@Param("now") Instant now, @Param("limit") int limit);

    /** Solicitações presas em PROCESSING (ex.: a instância caiu durante a chamada ao provedor). */
    @Query(value = """
            select * from activation_request
            where status = 'PROCESSING' and updated_at < :cutoff
            order by updated_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<ActivationRequest> lockStuckInProcessing(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
