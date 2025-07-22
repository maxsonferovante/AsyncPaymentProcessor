package com.maal.asyncpaymentprocessor.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Modelo de domínio para representar um pagamento.
 * Configurado com anotações Jackson para serialização/deserialização via Redis.
 */
@Setter
@Getter
public class Payment {
    @JsonProperty("correlationId")
    private final UUID correlationId;
    
    @JsonProperty("amount")
    private final BigDecimal amount;
    
    @JsonProperty("requestedAt")
    private final Instant requestedAt;
    
    @JsonProperty("paymentProcessorType")
    private PaymentProcessorType paymentProcessorType; // Pode ser null no início, setado após processamento
    
    @JsonProperty("status")
    private PaymentStatus status; // Status do processamento (e.g., PENDING, SUCCESS, FAILED, RETRY)
    
    @JsonProperty("retryCount")
    private int retryCount = 0; // Contador de tentativas de retry para evitar loops infinitos

    // Construtor principal - usado para novos pagamentos
    public Payment(UUID correlationId, BigDecimal amount, Instant requestedAt) {
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId cannot be null");
        this.amount = Objects.requireNonNull(amount, "amount cannot be null");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt cannot be null");
        this.status = PaymentStatus.PENDING; // Estado inicial
    }

    // Construtor para reconstrução completa (usado pelo Jackson para deserialização)
    @JsonCreator
    public Payment(@JsonProperty("correlationId") UUID correlationId,
                   @JsonProperty("amount") BigDecimal amount,
                   @JsonProperty("requestedAt") Instant requestedAt,
                   @JsonProperty("paymentProcessorType") PaymentProcessorType paymentProcessorType,
                   @JsonProperty("status") PaymentStatus status,
                   @JsonProperty("retryCount") Integer retryCount) {
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId cannot be null");
        this.amount = Objects.requireNonNull(amount, "amount cannot be null");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt cannot be null");
        this.paymentProcessorType = paymentProcessorType;
        this.status = status != null ? status : PaymentStatus.PENDING;
        this.retryCount = retryCount != null ? retryCount : 0;
    }

    // Construtor para reconstruir do banco de dados (mantido para compatibilidade)
    public Payment(UUID correlationId, BigDecimal amount, Instant requestedAt,
                   PaymentProcessorType paymentProcessorType, PaymentStatus status) {
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId cannot be null");
        this.amount = Objects.requireNonNull(amount, "amount cannot be null");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt cannot be null");
        this.paymentProcessorType = paymentProcessorType;
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.retryCount = 0; // Inicializa contador de retries
    }
}