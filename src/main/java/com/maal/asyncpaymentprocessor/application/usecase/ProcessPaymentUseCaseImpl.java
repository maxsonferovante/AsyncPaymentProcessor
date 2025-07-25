package com.maal.asyncpaymentprocessor.application.usecase;

import com.maal.asyncpaymentprocessor.adapter.out.paymentprocessor.PaymentProcessorHttpClient;
import com.maal.asyncpaymentprocessor.adapter.out.redis.RedisHealthCacheRepository;
import com.maal.asyncpaymentprocessor.application.service.PaymentHistoryService;
import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import com.maal.asyncpaymentprocessor.domain.model.PaymentStatus;
import com.maal.asyncpaymentprocessor.domain.port.in.ProcessPaymentUseCase;
import com.maal.asyncpaymentprocessor.domain.port.out.PaymentQueuePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Implementação do caso de uso de processamento de pagamentos.
 * NOVA ABORDAGEM: Sem persistência local - apenas processamento via Redis e Payment Processors.
 * Contém a lógica principal de escolha de processador, retries e circuit breaker.
 */
@Service
public class ProcessPaymentUseCaseImpl implements ProcessPaymentUseCase {
    private static final Logger logger = LoggerFactory.getLogger(ProcessPaymentUseCaseImpl.class);
    private final PaymentProcessorHttpClient paymentProcessorClient;
    private final RedisHealthCacheRepository healthCacheRepository;
    private final PaymentQueuePublisher paymentQueuePublisher;
    private final PaymentHistoryService paymentHistoryService;

    public ProcessPaymentUseCaseImpl(PaymentProcessorHttpClient paymentProcessorClient,
                                   RedisHealthCacheRepository healthCacheRepository,
                                   PaymentQueuePublisher paymentQueuePublisher,
                                   PaymentHistoryService paymentHistoryService
                                     ) {
        this.paymentProcessorClient = paymentProcessorClient;
        this.healthCacheRepository = healthCacheRepository;
        this.paymentQueuePublisher = paymentQueuePublisher;
        this.paymentHistoryService = paymentHistoryService;
    }

    /**
     * Processa um pagamento de forma assíncrona (chamado pelo worker).
     * Implementa a lógica de escolha de processador, retries e circuit breaker.
     * NOVA ABORDAGEM: Sem persistência local - apenas processamento nos Payment Processors + contadores agregados Redis.
     * @param payment Pagamento a ser processado
     */
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
    
    /**
     * Tenta processar um pagamento seguindo a estratégia de escolha inteligente:
     * 1. Prioriza Payment Processor Default (menor taxa)
     * 2. Se Default falhar, tenta Fallback
     * 3. Implementa retries para casos transitórios
     */
    private boolean attemptPaymentProcessing(Payment payment) {
        // Estratégia 1: Tenta Payment Processor Default primeiro (menor taxa)
        if (tryProcessWithProcessor(payment, PaymentProcessorType.DEFAULT)) {
            return true;
        }
        
        // Estratégia 2: Se Default falhou, tenta Fallback
        return tryProcessWithProcessor(payment, PaymentProcessorType.FALLBACK);
    }
    
    /**
     * Tenta processar um pagamento com um processador específico.
     * Inclui verificação de health check e lógica de retry.
     */
    private boolean tryProcessWithProcessor(Payment payment, PaymentProcessorType processorType) {
        // Verifica se o processador está saudável segundo o cache
        if (!isProcessorHealthy(processorType)) {
            return false;
        }        
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
    
    /**
     * Verifica se um processador está saudável baseado no cache Redis.
     * OTIMIZADO: Se não há informação, permite tentar para não bloquear processamento.
     * @param processorType Tipo do processador
     * @return true se estiver saudável ou sem informação, false apenas se confirmadamente falhando
     */
    private boolean isProcessorHealthy(PaymentProcessorType processorType) {
        try {
            Optional<HealthStatus> healthStatus = healthCacheRepository.getHealthStatus(processorType);
            // Se não há informação, assume que está falhando, mas não bloqueia processamento
            return healthStatus.filter(status -> !status.isFailing()).isPresent();
        } catch (Exception e) {
            logger.warn("Erro ao verificar health status para {}: {}", processorType, e.getMessage());
            // Em caso de erro, assume falha, mas não bloqueia processamento
            return false;
        }
    }
    
    /**
     * Trata falha de processamento
     * @param payment Pagamento que falhou
     */
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
