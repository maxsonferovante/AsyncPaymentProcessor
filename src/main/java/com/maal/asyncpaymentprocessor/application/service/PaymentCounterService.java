
package com.maal.asyncpaymentprocessor.application.service;

import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Serviço otimizado para contadores de pagamentos processados.
 * Mantém apenas estatísticas agregadas simples para máxima performance.
 * Resolve problema de timeout ao evitar operações KEYS e loops custosos.
 */
@Service
public class PaymentCounterService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentCounterService.class);
    
    // Chave única para contadores agregados - estrutura simples e eficiente
    private static final String COUNTERS_KEY = "payment:counters";
    
    private final RedisTemplate<String, String> redisTemplate;
    
    public PaymentCounterService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * Incrementa contadores após processamento bem-sucedido de um pagamento.
     * Operação O(1) thread-safe usando operações atômicas Redis.
     * 
     * @param processor Processador que foi usado (DEFAULT ou FALLBACK)
     * @param amount Valor do pagamento processado
     */
    public void incrementCounters(PaymentProcessorType processor, BigDecimal amount) {
        try {
            String processorKey = processor.name().toLowerCase();
            
            // Versão simplificada sem pipeline para recursos limitados
            // Operações individuais são mais leves em memória
            String requestsField = processorKey + "_totalRequests";
            String amountField = processorKey + "_totalAmount";
            String lastActivityField = processorKey + "_lastActivityAt";
            
            // 1. Incrementa contador de requests - operação O(1)
            redisTemplate.opsForHash().increment(COUNTERS_KEY, requestsField, 1);
            
            // 2. Incrementa valor total - operação O(1)
            redisTemplate.opsForHash().increment(COUNTERS_KEY, amountField, amount.doubleValue());
            
            // 3. Atualiza timestamp da última atividade (opcional em recursos limitados)
            if (Math.random() < 0.1) { // Atualiza apenas 10% das vezes para economizar
                redisTemplate.opsForHash().put(COUNTERS_KEY, lastActivityField, Instant.now().toString());
            }
            
            logger.debug("Contadores incrementados para {}: amount={}", processor, amount);
            
        } catch (Exception e) {
            // Log do erro mas não falha o fluxo principal de processamento
            logger.error("Erro ao incrementar contadores para {}: {}", processor, e.getMessage(), e);
        }
    }
    
    /**
     * Obtém sumário completo de todos os processadores.
     * Operação O(1) extremamente eficiente - apenas um HGETALL.
     * 
     * @return Sumário no formato solicitado
     */
    public ProcessorCounters getSummary() {
        try {
            // Uma única operação Redis O(1) - muito eficiente
            Map<Object, Object> allCounters = redisTemplate.opsForHash().entries(COUNTERS_KEY);
            
            if (allCounters.isEmpty()) {
                // Retorna contadores zerados se não há dados
                return new ProcessorCounters();
            }
            
            // Extrai dados para processador DEFAULT
            ProcessorStats defaultStats = extractProcessorStats(allCounters, "default");
            
            // Extrai dados para processador FALLBACK  
            ProcessorStats fallbackStats = extractProcessorStats(allCounters, "fallback");
            
            logger.debug("Sumário obtido: DEFAULT={} requests, FALLBACK={} requests", 
                defaultStats.getTotalRequests(), fallbackStats.getTotalRequests());
            
            return new ProcessorCounters(defaultStats, fallbackStats);
            
        } catch (Exception e) {
            logger.error("Erro ao obter sumário de contadores: {}", e.getMessage(), e);
            
            // Retorna contadores zerados em caso de erro
            return new ProcessorCounters();
        }
    }
    
    /**
     * Extrai estatísticas de um processador específico dos dados Redis.
     * 
     * @param allCounters Todos os contadores do Redis
     * @param processorName Nome do processador (default/fallback)
     * @return Estatísticas do processador
     */
    private ProcessorStats extractProcessorStats(Map<Object, Object> allCounters, String processorName) {
        try {
            // Extrai campos específicos do processador
            String requestsField = processorName + "_totalRequests";
            String amountField = processorName + "_totalAmount";
            String lastActivityField = processorName + "_lastActivityAt";
            
            long totalRequests = getLongValue(allCounters, requestsField, 0L);
            double totalAmount = getDoubleValue(allCounters, amountField, 0.0);
            String lastActivityStr = getStringValue(allCounters, lastActivityField, Instant.now().toString());
            
            Instant lastActivity;
            try {
                lastActivity = Instant.parse(lastActivityStr);
            } catch (Exception e) {
                lastActivity = Instant.now();
            }
            
            return new ProcessorStats(totalRequests, BigDecimal.valueOf(totalAmount), lastActivity);
            
        } catch (Exception e) {
            logger.warn("Erro ao extrair stats para {}: {}", processorName, e.getMessage());
            return new ProcessorStats(0L, BigDecimal.ZERO, Instant.now());
        }
    }
    
    /**
     * Obtém valor long do mapa com fallback.
     */
    private long getLongValue(Map<Object, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * Obtém valor double do mapa com fallback.
     */
    private double getDoubleValue(Map<Object, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * Obtém valor string do mapa com fallback.
     */
    private String getStringValue(Map<Object, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    /**
     * Reseta todos os contadores. Útil para testes ou manutenção.
     */
    public void resetCounters() {
        try {
            redisTemplate.delete(COUNTERS_KEY);
            logger.info("Contadores resetados com sucesso");
        } catch (Exception e) {
            logger.error("Erro ao resetar contadores: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Classe para representar estatísticas de um processador específico.
     */
    public static class ProcessorStats {
        private final long totalRequests;
        private final BigDecimal totalAmount;
        private final Instant lastActivityAt;
        
        public ProcessorStats(long totalRequests, BigDecimal totalAmount, Instant lastActivityAt) {
            this.totalRequests = totalRequests;
            this.totalAmount = totalAmount;
            this.lastActivityAt = lastActivityAt;
        }
        
        public long getTotalRequests() { return totalRequests; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public Instant getLastActivityAt() { return lastActivityAt; }
        
        @Override
        public String toString() {
            return "ProcessorStats{" +
                    "totalRequests=" + totalRequests +
                    ", totalAmount=" + totalAmount +
                    ", lastActivityAt=" + lastActivityAt +
                    '}';
        }
    }
    
    /**
     * Classe para representar contadores de todos os processadores.
     * Formato exato solicitado pelo usuário.
     */
    public static class ProcessorCounters {
        private final ProcessorStats defaultProcessor;
        private final ProcessorStats fallbackProcessor;
        
        public ProcessorCounters() {
            this.defaultProcessor = new ProcessorStats(0L, BigDecimal.ZERO, Instant.now());
            this.fallbackProcessor = new ProcessorStats(0L, BigDecimal.ZERO, Instant.now());
        }
        
        public ProcessorCounters(ProcessorStats defaultProcessor, ProcessorStats fallbackProcessor) {
            this.defaultProcessor = defaultProcessor;
            this.fallbackProcessor = fallbackProcessor;
        }
        
        public ProcessorStats getDefaultProcessor() { return defaultProcessor; }
        public ProcessorStats getFallbackProcessor() { return fallbackProcessor; }
        
        /**
         * Retorna no formato JSON solicitado pelo usuário.
         */
        public Map<String, Map<String, Object>> toApiFormat() {
            return Map.of(
                "default", Map.of(
                    "totalRequests", defaultProcessor.getTotalRequests(),
                    "totalAmount", defaultProcessor.getTotalAmount()
                ),
                "fallback", Map.of(
                    "totalRequests", fallbackProcessor.getTotalRequests(),
                    "totalAmount", fallbackProcessor.getTotalAmount()
                )
            );
        }
        
        @Override
        public String toString() {
            return "ProcessorCounters{" +
                    "default=" + defaultProcessor +
                    ", fallback=" + fallbackProcessor +
                    '}';
        }
    }
}
