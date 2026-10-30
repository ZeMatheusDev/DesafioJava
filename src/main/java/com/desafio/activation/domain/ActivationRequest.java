package com.desafio.activation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de ativação. O status só muda pelos métodos de domínio, que validam a transição;
 * não há setters públicos.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    /** Sistema consumidor (claim "sub" do token) que criou a solicitação. */
    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Lock otimista; nulo indica entidade nova para o Spring Data (persist em vez de merge). */
    @Version
    private Long version;

    protected ActivationRequest() {
        // JPA
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           String clientId, String correlationId, Instant now) {
        var request = new ActivationRequest();
        request.id = UUID.randomUUID();
        request.externalId = Objects.requireNonNull(externalId);
        request.customerId = Objects.requireNonNull(customerId);
        request.productCode = Objects.requireNonNull(productCode);
        request.clientId = Objects.requireNonNull(clientId);
        request.correlationId = correlationId;
        request.status = RequestStatus.PENDING;
        request.nextAttemptAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    public void startProcessing(Instant now) {
        transitionTo(RequestStatus.PROCESSING, now);
        attempts++;
        nextAttemptAt = null;
    }

    public void complete(Instant now) {
        transitionTo(RequestStatus.COMPLETED, now);
        failureReason = null;
    }

    public void fail(String reason, Instant now) {
        transitionTo(RequestStatus.FAILED, now);
        failureReason = reason;
    }

    /** Falha temporária: volta para PENDING e será reprocessada a partir de {@code retryAt}. */
    public void scheduleRetry(Instant retryAt, String reason, Instant now) {
        transitionTo(RequestStatus.PENDING, now);
        nextAttemptAt = retryAt;
        failureReason = reason;
    }

    public boolean hasSameContent(String customerId, String productCode) {
        return this.customerId.equals(customerId) && this.productCode.equals(productCode);
    }

    public boolean isOwnedBy(String clientId) {
        return this.clientId.equals(clientId);
    }

    private void transitionTo(RequestStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        status = target;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getClientId() {
        return clientId;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
