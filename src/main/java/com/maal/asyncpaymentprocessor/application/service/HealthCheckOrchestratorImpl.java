package com.maal.asyncpaymentprocessor.application.service;

import com.maal.asyncpaymentprocessor.adapter.out.redis.RedisHealthCacheRepository;
import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import com.maal.asyncpaymentprocessor.domain.port.in.HealthCheckOrchestrator;
import com.maal.asyncpaymentprocessor.domain.port.out.PaymentProcessorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Orquestrador responsável por monitorar a saúde dos Payment Processors.
 * Executa verificações periódicas e atualiza o cache Redis com os resultados.
 */
@Service
public class HealthCheckOrchestratorImpl implements HealthCheckOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckOrchestratorImpl.class);

    private final RedisHealthCacheRepository redisHealthCacheRepository;
    private final PaymentProcessorPort paymentProcessorPort;

    public HealthCheckOrchestratorImpl(RedisHealthCacheRepository redisHealthCacheRepository,
                                       PaymentProcessorPort paymentProcessorPort) {
        this.redisHealthCacheRepository = redisHealthCacheRepository;
        this.paymentProcessorPort = paymentProcessorPort;
    }

    /**
     * Agenda verificações de health check a cada 4 segundos.
     * Conforme especificado na documentação da Rinha, o limite é de 1 chamada a cada 4 segundos.
     */
    @Scheduled(fixedDelay = 4900) // 4 segundos entre execuções
    @Override
    public void scheduleHealthChecks() {
        // Executa as verificações de health check de forma assíncrona para ambos os processadores
        CompletableFuture<Void> defaultCheck = CompletableFuture.runAsync(() -> 
            checkHealthAndUpdateCache(PaymentProcessorType.DEFAULT)
        );
        
        CompletableFuture<Void> fallbackCheck = CompletableFuture.runAsync(() -> 
            checkHealthAndUpdateCache(PaymentProcessorType.FALLBACK)
        );
        
        // Aguarda ambas as verificações completarem (sem bloquear a thread principal por muito tempo)
        try {
            CompletableFuture.allOf(defaultCheck, fallbackCheck)
                .get(java.util.concurrent.TimeUnit.SECONDS.toMillis(4), 
                     java.util.concurrent.TimeUnit.MILLISECONDS); // Timeout de 4 segundos
        } catch (Exception e) {
            logger.warn("Timeout ou erro durante as verificações de health check: {}", e.getMessage());
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
                
                // Atualiza o cache Redis com o status de saúde
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
