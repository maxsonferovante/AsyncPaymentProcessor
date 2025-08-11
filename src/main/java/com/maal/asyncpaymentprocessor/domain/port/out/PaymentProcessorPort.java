package com.maal.asyncpaymentprocessor.domain.port.out;

import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;

import java.util.Optional;

public interface PaymentProcessorPort {
    /**
     * Processa um pagamento usando o processador de pagamentos configurado.
     *
     * @param payment O pagamento a ser processado.
     * @param type O tipo de processador de pagamentos a ser usado.
     * @return true se o pagamento foi processado com sucesso, false caso contrário.
     */
    boolean processPayment(Payment payment, PaymentProcessorType type);


}
