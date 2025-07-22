package com.maal.asyncpaymentprocessor.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.aot.hint.annotation.Reflective;

import java.time.Instant;
import java.util.Objects;

/**
 * Representa o status de saúde de um Payment Processor.
 * Esta classe contém informações sobre a disponibilidade e performance
 * dos serviços de processamento de pagamento.
 * 
 * Configurada especificamente para compatibilidade com GraalVM Native Image.
 */
@Reflective // Habilita reflexão para GraalVM Native Image
public class HealthStatus {
    
    @JsonProperty("failing")
    private final boolean failing;
    
    @JsonProperty("minResponseTime") 
    private final int minResponseTime;
    
    @JsonProperty("lastCheckedAt")
    private final Instant lastCheckedAt; // Para controle de cache e validade

    /**
     * Construtor principal para criar um HealthStatus.
     * Configurado com anotações Jackson para serialização/deserialização.
     * 
     * @param failing Indica se o processador está falhando
     * @param minResponseTime Tempo mínimo de resposta em milissegundos
     * @param lastCheckedAt Timestamp da última verificação
     */
    @JsonCreator
    public HealthStatus(@JsonProperty("failing") boolean failing, 
                       @JsonProperty("minResponseTime") int minResponseTime, 
                       @JsonProperty("lastCheckedAt") Instant lastCheckedAt) {
        this.failing = failing;
        this.minResponseTime = minResponseTime;
        this.lastCheckedAt = Objects.requireNonNull(lastCheckedAt, "lastCheckedAt cannot be null");
    }

    /**
     * Verifica se o processador está falhando.
     * @return true se o processador está com falhas, false caso contrário
     */
    @JsonProperty("failing")
    public boolean isFailing() {
        return failing;
    }

    /**
     * Obtém o tempo mínimo de resposta do processador.
     * @return Tempo mínimo de resposta em milissegundos
     */
    @JsonProperty("minResponseTime")
    public int getMinResponseTime() {
        return minResponseTime;
    }

    /**
     * Obtém o timestamp da última verificação.
     * @return Instant da última verificação
     */
    @JsonProperty("lastCheckedAt")
    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HealthStatus that = (HealthStatus) o;
        return failing == that.failing &&
                minResponseTime == that.minResponseTime &&
                lastCheckedAt.equals(that.lastCheckedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(failing, minResponseTime, lastCheckedAt);
    }

    @Override
    public String toString() {
        return "HealthStatus{" +
                "failing=" + failing +
                ", minResponseTime=" + minResponseTime +
                ", lastCheckedAt=" + lastCheckedAt +
                '}';
    }
}