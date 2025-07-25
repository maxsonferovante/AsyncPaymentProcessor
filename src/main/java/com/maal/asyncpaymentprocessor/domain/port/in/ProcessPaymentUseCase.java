package com.maal.asyncpaymentprocessor.domain.port.in;


import com.maal.asyncpaymentprocessor.domain.model.Payment;

/**
 * Port (interface) para caso de uso de processamento de pagamentos
 * Define o contrato que deve ser implementado pela camada de aplicação
 */
public interface ProcessPaymentUseCase {
    
    /**
     * Processa um novo pagamento recebido de forma assíncrona.
     */
    boolean processPaymentAsync(Payment payment);

} 