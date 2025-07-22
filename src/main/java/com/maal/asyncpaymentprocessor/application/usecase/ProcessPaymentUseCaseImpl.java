package com.maal.asyncpaymentprocessor.application.usecase;

import com.maal.asyncpaymentprocessor.adapter.out.paymentprocessor.PaymentProcessorHttpClient;
import com.maal.asyncpaymentprocessor.adapter.out.redis.RedisHealthCacheRepository;
import com.maal.asyncpaymentprocessor.application.service.PaymentCounterService;
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
    
    // Configurações de retry e circuit breaker
    private static final int MAX_RETRY_ATTEMPTS = 2;
   
    private final PaymentProcessorHttpClient paymentProcessorClient;
    private final RedisHealthCacheRepository healthCacheRepository;
    private final PaymentQueuePublisher paymentQueuePublisher;
    private final PaymentCounterService paymentCounterService;

    public ProcessPaymentUseCaseImpl(PaymentProcessorHttpClient paymentProcessorClient,
                                   RedisHealthCacheRepository healthCacheRepository,
                                   PaymentQueuePublisher paymentQueuePublisher,
                                   PaymentCounterService paymentCounterService) {
        this.paymentProcessorClient = paymentProcessorClient;
        this.healthCacheRepository = healthCacheRepository;
        this.paymentQueuePublisher = paymentQueuePublisher;
        this.paymentCounterService = paymentCounterService;
    }

    @Override
    public void receivePayment(Payment payment) {
        try {
            // Define status inicial como PENDING
            payment.setStatus(PaymentStatus.PENDING);
            
            // Publica na fila para processamento assíncrono (SEM PERSISTÊNCIA LOCAL)
            paymentQueuePublisher.publish(payment);
                
        } catch (Exception e) {
            logger.error("Erro ao processar recebimento do pagamento: correlationId={}, erro={}", 
                payment.getCorrelationId(), e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Processa um pagamento de forma assíncrona (chamado pelo worker).
     * Implementa a lógica de escolha de processador, retries e circuit breaker.
     * NOVA ABORDAGEM: Sem persistência local - apenas processamento nos Payment Processors + contadores agregados Redis.
     * @param payment Pagamento a ser processado
     */
    public void processPaymentAsync(Payment payment) {
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
                    paymentCounterService.incrementCounters(usedProcessor, payment.getAmount());
                    logger.debug("Contadores incrementados para pagamento processado com sucesso: correlationId={}, processor={}", 
                        payment.getCorrelationId(), usedProcessor);
                }
            } else {
                // Falha - marca para retry
                handlePaymentFailure(payment);
            }
            
        } catch (Exception _) {
            handlePaymentFailure(payment);
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
        
        // Tenta processar com retries
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                boolean success = paymentProcessorClient.processPayment(payment, processorType);
                
                if (success) {
                    payment.setPaymentProcessorType(processorType);
                    return true;
                }
                                                
                                    
            } catch (Exception e) {
                logger.warn("Erro na tentativa {}/{} com {}: correlationId={}, erro={}", 
                    attempt, MAX_RETRY_ATTEMPTS, processorType, payment.getCorrelationId(), e.getMessage());
            }
        }
        
        return false;
    }
    
    /**
     * Verifica se um processador está saudável baseado no cache Redis.
     * @param processorType Tipo do processador
     * @return true se estiver saudável, false caso contrário
     */
    private boolean isProcessorHealthy(PaymentProcessorType processorType) {
        try {
            Optional<HealthStatus> healthStatus = healthCacheRepository.getHealthStatus(processorType);
            
            if (healthStatus.isPresent()) {
                boolean healthy = !healthStatus.get().isFailing();
                return healthy;
            } else {
                // Se não há informação de health no cache, assume o fallback
                return false;
            }
            
        } catch (Exception e) {
            logger.warn("Erro ao verificar health status para {}: {}", processorType, e.getMessage());
            // Em caso de erro, assume o fallback para não bloquear processamento
            return false;
        }
    }
    
    /**
     * Trata falha de processamento, decidindo entre retry ou falha definitiva.
     * @param payment Pagamento que falhou
     */
    private void handlePaymentFailure(Payment payment) {
        try {
            payment.setRetryCount(payment.getRetryCount() + 1);
            
            // Se ainda pode tentar retry
            if (payment.getRetryCount() < MAX_RETRY_ATTEMPTS) {
                payment.setStatus(PaymentStatus.RETRY);
                paymentQueuePublisher.publish(payment);
            } else {
                // Esgotou tentativas - falha definitiva
                payment.setStatus(PaymentStatus.FAILED);
            }
            
        } catch (Exception e) {
            logger.error("Erro ao tratar falha do pagamento: correlationId={}, erro={}", 
                payment.getCorrelationId(), e.getMessage(), e);
        }
    }
}
