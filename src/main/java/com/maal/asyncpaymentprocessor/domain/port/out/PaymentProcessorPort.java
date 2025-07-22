package com.maal.asyncpaymentprocessor.domain.port.out;

import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;

import java.util.Optional;

public interface PaymentProcessorPort {
    /**
     * Obtém o status de saúde de um Payment Processor específico.
     * @param type O tipo de Payment Processor (DEFAULT ou FALLBACK).
     * @return Um Optional contendo o HealthStatus se a chamada for bem-sucedida, ou Optional.empty().
     */
    Optional<HealthStatus> getHealthStatus(PaymentProcessorType type);
}
