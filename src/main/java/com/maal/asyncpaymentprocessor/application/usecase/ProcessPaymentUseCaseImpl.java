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
    
    // Configurações de retry otimizadas para recursos limitados
    private static final int MAX_RETRY_ATTEMPTS = 1; // Reduzido para evitar sobrecarga
   
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
                    paymentHistoryService.recordPayment(payment);
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
            
            if (healthStatus.isPresent()) {
                boolean healthy = !healthStatus.get().isFailing();
                return healthy;
            } else {
                // MUDANÇA CRÍTICA: Se não há informação de health no cache, assume healthy
                // para não bloquear todo o processamento em recursos limitados
                logger.debug("Sem informação de health para {}, assumindo healthy", processorType);
                return true;
            }
            
        } catch (Exception e) {
            logger.warn("Erro ao verificar health status para {}: {}", processorType, e.getMessage());
            // Em caso de erro, assume healthy para não bloquear processamento
            return true;
        }
    }
    
    /**
     * Trata falha de processamento - SIMPLIFICADO para recursos limitados.
     * OTIMIZAÇÃO: Evita republishing excessivo que causa loops e sobrecarga.
     * @param payment Pagamento que falhou
     */
    private void handlePaymentFailure(Payment payment) {
        try {
            payment.setRetryCount(payment.getRetryCount() + 1);
            
            // OTIMIZAÇÃO: Reduz retries para evitar sobrecarga em recursos limitados
            // Se já tentou mais que o limite, marca como FAILED e para
            if (payment.getRetryCount() >= MAX_RETRY_ATTEMPTS) {
                payment.setStatus(PaymentStatus.FAILED);
                logger.debug("Pagamento marcado como FAILED após {} tentativas: correlationId={}", 
                    payment.getRetryCount(), payment.getCorrelationId());
            } else {
                // Apenas um retry, evita loops infinitos
                payment.setStatus(PaymentStatus.RETRY);
                paymentQueuePublisher.publish(payment);
                logger.debug("Pagamento republicado para retry {}/{}: correlationId={}", 
                    payment.getRetryCount(), MAX_RETRY_ATTEMPTS, payment.getCorrelationId());
            }
            
        } catch (Exception e) {
            // Se falha ao republicar, marca como RETRY para não travar
            payment.setStatus(PaymentStatus.RETRY);
            logger.error("Erro ao tratar falha do pagamento, marcando como RETRY: correlationId={}, erro={}", 
                payment.getCorrelationId(), e.getMessage(), e);
        }
    }
}
