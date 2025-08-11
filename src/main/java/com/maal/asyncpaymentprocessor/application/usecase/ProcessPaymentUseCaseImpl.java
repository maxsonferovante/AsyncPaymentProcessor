package com.maal.asyncpaymentprocessor.application.usecase;

import com.maal.asyncpaymentprocessor.adapter.out.paymentprocessor.PaymentProcessorHttpClient;
import com.maal.asyncpaymentprocessor.application.service.PaymentHistoryService;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import com.maal.asyncpaymentprocessor.domain.model.PaymentStatus;
import com.maal.asyncpaymentprocessor.domain.port.in.ProcessPaymentUseCase;
import com.maal.asyncpaymentprocessor.domain.port.out.PaymentQueuePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@Service
public class ProcessPaymentUseCaseImpl implements ProcessPaymentUseCase {
    private static final Logger logger = LoggerFactory.getLogger(ProcessPaymentUseCaseImpl.class);
    private final PaymentProcessorHttpClient paymentProcessorClient;
    private final PaymentQueuePublisher paymentQueuePublisher;
    private final PaymentHistoryService paymentHistoryService;

    public ProcessPaymentUseCaseImpl(PaymentProcessorHttpClient paymentProcessorClient,
                                   PaymentQueuePublisher paymentQueuePublisher,
                                   PaymentHistoryService paymentHistoryService
                                     ) {
        this.paymentProcessorClient = paymentProcessorClient;
        this.paymentQueuePublisher = paymentQueuePublisher;
        this.paymentHistoryService = paymentHistoryService;
    }


    public boolean processPaymentAsync(Payment payment) {
        try {
            payment.setStatus(PaymentStatus.PROCESSING);
            
            // Tenta processar o pagamento usando estratégia inteligente
            boolean processed = attemptPaymentProcessing(payment);
            
            if (processed) {
                // Sucesso - pagamento processado no Payment Processor
                payment.setStatus(PaymentStatus.SUCCESS);                
                // Incrementa contadores agregados no Redis para consultas futuras da API
                PaymentProcessorType usedProcessor = payment.getPaymentProcessorType();
                if (usedProcessor != null) {
                    paymentHistoryService.recordPayment(payment);
                }
            } else {
                // Falha - marca para retry
                handlePaymentFailure(payment);
            }
            return processed;
        } catch (Exception _) {
            handlePaymentFailure(payment);
            return false;
        }
    }
    

    private boolean attemptPaymentProcessing(Payment payment) {

        // tenta intercalando entre os processadores por 15 vezes
        for (int retry = 0; retry < 15; retry++) {
            if (tryProcessWithProcessor(payment, PaymentProcessorType.DEFAULT)) {
                return true;
            }
            if (tryProcessWithProcessor(payment, PaymentProcessorType.FALLBACK)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryProcessWithProcessor(Payment payment, PaymentProcessorType processorType) {
        try {
            boolean success = paymentProcessorClient.processPayment(payment, processorType);
            
            if (success) {
                payment.setPaymentProcessorType(processorType);
            }
            return success;                                                                                    
        } catch (Exception e) {
            logger.warn("Erro ao processar pagamento com {}: correlationId={}, erro={}", 
                processorType, payment.getCorrelationId(), e.getMessage());
        }
        return false;
    }

    private void handlePaymentFailure(Payment payment) {

        try {
            payment.setRetryCount(payment.getRetryCount() + 1);
            payment.setStatus(PaymentStatus.RETRY);
            paymentQueuePublisher.publish(payment);
        } catch (Exception e) {
            logger.warn("Erro ao processar pagamento: {} - {}", payment.getCorrelationId(), e.getMessage());
            payment.setStatus(PaymentStatus.RETRY);
            paymentQueuePublisher.publish(payment);
        }
    }
}
