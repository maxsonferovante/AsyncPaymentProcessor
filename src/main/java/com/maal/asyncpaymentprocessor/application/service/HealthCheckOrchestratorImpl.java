package com.maal.asyncpaymentprocessor.application.service;

import com.maal.asyncpaymentprocessor.adapter.out.redis.RedisHealthCacheRepository;
import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import com.maal.asyncpaymentprocessor.domain.port.in.HealthCheckOrchestrator;
import com.maal.asyncpaymentprocessor.domain.port.out.PaymentProcessorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;

/**
 * Orquestrador responsável por monitorar a saúde dos Payment Processors.
 * Executa verificações periódicas e atualiza o cache Redis com os resultados.
 */
@Service
public class HealthCheckOrchestratorImpl implements HealthCheckOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckOrchestratorImpl.class);

    private final RedisLockRegistry redisLockRegistry;
    private final RedisHealthCacheRepository redisHealthCacheRepository;
    private final PaymentProcessorPort paymentProcessorPort;

    public HealthCheckOrchestratorImpl(
                                        RedisLockRegistry redisLockRegistry,
                                        RedisHealthCacheRepository redisHealthCacheRepository,
                                       PaymentProcessorPort paymentProcessorPort) {
        this.redisLockRegistry = redisLockRegistry;
        this.redisHealthCacheRepository = redisHealthCacheRepository;
        this.paymentProcessorPort = paymentProcessorPort;
    }

    /**
     * Agenda verificações de health check a cada 4998ms, sob um lock distribuído.
     * Apenas a instância líder (que adquire o lock) executa a verificação.
     */
    @Scheduled(fixedDelay = 4998) // Mantido em 4.998 segundos para evitar 429
    @Override
    public void scheduleHealthChecks() {
        // Chave única para o lock desta tarefa específica
        Lock lock = redisLockRegistry.obtain("global-health-check-leader-task");
        boolean locked = false;
        try {
            // Tenta adquirir o lock imediatamente. Se outra instância já o tem, retorna false.
            locked = lock.tryLock();
            if (locked) {
                logger.info("Instância líder adquiriu o lock para health check. Executando verificação.");

                // --- Lógica existente de verificação de health check ---
                CompletableFuture<Void> defaultCheck = CompletableFuture.runAsync(() ->
                        checkHealthAndUpdateCache(PaymentProcessorType.DEFAULT)
                );

                CompletableFuture<Void> fallbackCheck = CompletableFuture.runAsync(() ->
                        checkHealthAndUpdateCache(PaymentProcessorType.FALLBACK)
                );

                CompletableFuture.allOf(defaultCheck, fallbackCheck)
                        .get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS); // Timeout de 5 segundos para as chamadas

            } else {
                logger.info("Instância não líder, lock não adquirido para health check. Pulando execução.");
            }
        } catch (Exception e) {
            logger.error("Erro inesperado durante tentativa de adquirir lock ou executar health check: {}", e.getMessage(), e);
            // Se ocorrer um erro, não remove o cache, pois pode ser um erro temporário de comunicação
            // apenas abandona a execução desta instância
        } finally {
            if (locked) {
                try {
                    lock.unlock(); // É crucial liberar o lock após a execução (ou falha)
                    logger.debug("Lock de health check liberado.");
                } catch (Exception e) {
                    logger.error("Erro ao liberar lock de health check: {}", e.getMessage(), e);
                }
            }
        }
    }
    /**
     * Verifica a saúde de um Payment Processor específico e atualiza o cache.
     * @param processorType Tipo do Payment Processor a ser verificado
     */
    private void checkHealthAndUpdateCache(PaymentProcessorType processorType) {
        try {
            // Chama o endpoint de health check do Payment Processor
            Optional<HealthStatus> healthStatusOpt = paymentProcessorPort.getHealthStatus(processorType);
            
            if (healthStatusOpt.isPresent()) {
                HealthStatus healthStatus = healthStatusOpt.get();
                redisHealthCacheRepository.saveHealthStatus(processorType, healthStatus);
            } else {
                logger.warn("Não foi possível obter health status do Payment Processor: {}", processorType);
                // Remove entry inválida do cache se existir
                redisHealthCacheRepository.removeHealthStatus(processorType);
            }
            
        } catch (Exception e) {
            logger.error("Erro ao verificar health status do Payment Processor {}: {}", 
                processorType, e.getMessage());
                
            // Remove entry problemática do cache apenas se não for erro de serialização
            if (!(e.getMessage() != null && e.getMessage().contains("serializar HealthStatus"))) {
                redisHealthCacheRepository.removeHealthStatus(processorType);
            }
        }
    }
    
    /**
     * Método utilitário para verificar se um Payment Processor está saudável segundo o cache.
     * @param processorType Tipo do Payment Processor
     * @return true se está saudável, false caso contrário
     */
    public boolean isProcessorHealthy(PaymentProcessorType processorType) {
        Optional<HealthStatus> healthStatusOpt = redisHealthCacheRepository.getHealthStatus(processorType);
        
        if (healthStatusOpt.isEmpty()) {
            return false; // Se não há informação no cache, considera como não saudável
        }
        
        HealthStatus healthStatus = healthStatusOpt.get();
        return !healthStatus.isFailing(); // Retorna true se NÃO está falhando
    }
    
    /**
     * Obtém o tempo mínimo de resposta de um Payment Processor do cache.
     * @param processorType Tipo do Payment Processor
     * @return Tempo mínimo de resposta em ms, ou 0 se não disponível
     */
    public int getMinResponseTime(PaymentProcessorType processorType) {
        Optional<HealthStatus> healthStatusOpt = redisHealthCacheRepository.getHealthStatus(processorType);
        
        return healthStatusOpt
            .map(HealthStatus::getMinResponseTime)
            .orElse(0);
    }
}
