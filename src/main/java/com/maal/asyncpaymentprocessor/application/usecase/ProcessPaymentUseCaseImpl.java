package com.maal.asyncpaymentprocessor.application.usecase;

import com.maal.asyncpaymentprocessor.adapter.out.paymentprocessor.PaymentProcessorHttpClient;
import com.maal.asyncpaymentprocessor.adapter.out.redis.RedisHealthCacheRepository;
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
    private static final int MAX_RETRY_ATTEMPTS = 3;
   
    private final PaymentProcessorHttpClient paymentProcessorClient;
    private final RedisHealthCacheRepository healthCacheRepository;
    private final PaymentQueuePublisher paymentQueuePublisher;

    public ProcessPaymentUseCaseImpl(PaymentProcessorHttpClient paymentProcessorClient,
                                   RedisHealthCacheRepository healthCacheRepository,
                                   PaymentQueuePublisher paymentQueuePublisher) {
        this.paymentProcessorClient = paymentProcessorClient;
        this.healthCacheRepository = healthCacheRepository;
        this.paymentQueuePublisher = paymentQueuePublisher;
    }

    @Override
    public void receivePayment(Payment payment) {
        logger.info("Recebendo pagamento para processamento: correlationId={}, amount={}", 
            payment.getCorrelationId(), payment.getAmount());
        
        try {
            // Define status inicial como PENDING
            payment.setStatus(PaymentStatus.PENDING);
            
            // Publica na fila para processamento assíncrono (SEM PERSISTÊNCIA LOCAL)
            paymentQueuePublisher.publish(payment);
            
            logger.info("Pagamento publicado na fila com sucesso: correlationId={}", 
                payment.getCorrelationId());
                
        } catch (Exception e) {
            logger.error("Erro ao processar recebimento do pagamento: correlationId={}, erro={}", 
                payment.getCorrelationId(), e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Processa um pagamento de forma assíncrona (chamado pelo worker).
     * Implementa a lógica de escolha de processador, retries e circuit breaker.
     * NOVA ABORDAGEM: Sem persistência local - apenas processamento nos Payment Processors.
     * @param payment Pagamento a ser processado
     */
    public void processPaymentAsync(Payment payment) {
        logger.info("Iniciando processamento assíncrono: correlationId={}", payment.getCorrelationId());
        
        try {
            payment.setStatus(PaymentStatus.PROCESSING);
            
            // Tenta processar o pagamento usando estratégia inteligente
            boolean processed = attemptPaymentProcessing(payment);
            
            if (processed) {
                // Sucesso - pagamento processado no Payment Processor
                payment.setStatus(PaymentStatus.SUCCESS);
                
                logger.info("Pagamento processado com sucesso: correlationId={}, finalProcessor={}", 
                    payment.getCorrelationId(), payment.getPaymentProcessorType());
            } else {
                // Falha - marca para retry
                handlePaymentFailure(payment);
            }
            
        } catch (Exception e) {
            logger.error("Erro durante processamento assíncrono: correlationId={}, erro={}", 
                payment.getCorrelationId(), e.getMessage(), e);
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
        if (tryProcessWithProcessor(payment, PaymentProcessorType.FALLBACK)) {
            return true;
        }
        
        // Se ambos falharam, retorna false para indicar falha total
        logger.warn("Falha ao processar pagamento com ambos os processadores: correlationId={}", 
            payment.getCorrelationId());
        return false;
    }
    
    /**
     * Tenta processar um pagamento com um processador específico.
     * Inclui verificação de health check e lógica de retry.
     */
    private boolean tryProcessWithProcessor(Payment payment, PaymentProcessorType processorType) {
        logger.debug("Tentando processar com {}: correlationId={}", 
            processorType, payment.getCorrelationId());
        
        // Verifica se o processador está saudável segundo o cache
        if (!isProcessorHealthy(processorType)) {
            logger.debug("Processador {} não está saudável, pulando: correlationId={}", 
                processorType, payment.getCorrelationId());
            return false;
        }
        
        // Tenta processar com retries
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                boolean success = paymentProcessorClient.processPayment(payment, processorType);
                
                if (success) {
                    payment.setPaymentProcessorType(processorType);
                    logger.info("Pagamento processado com sucesso na tentativa {}/{}: correlationId={}, processor={}", 
                        attempt, MAX_RETRY_ATTEMPTS, payment.getCorrelationId(), processorType);
                    return true;
                }
                
                logger.warn("Falha na tentativa {}/{} com {}: correlationId={}", 
                    attempt, MAX_RETRY_ATTEMPTS, processorType, payment.getCorrelationId());
                                                
                                    
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
                logger.debug("Health check do cache para {}: healthy={}", processorType, healthy);
                return healthy;
            } else {
                // Se não há informação de health no cache, assume o fallback
                logger.debug("Sem informação de health no cache para {}, assumindo saudável", processorType);
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
                
                logger.warn("Pagamento enviado para retry {}/{}: correlationId={}", 
                    payment.getRetryCount(), MAX_RETRY_ATTEMPTS, payment.getCorrelationId());
            } else {
                // Esgotou tentativas - falha definitiva
                payment.setStatus(PaymentStatus.FAILED);
                
                logger.error("Pagamento falhou definitivamente após {} tentativas: correlationId={}", 
                    MAX_RETRY_ATTEMPTS, payment.getCorrelationId());
            }
            
        } catch (Exception e) {
            logger.error("Erro ao tratar falha do pagamento: correlationId={}, erro={}", 
                payment.getCorrelationId(), e.getMessage(), e);
        }
    }
}
