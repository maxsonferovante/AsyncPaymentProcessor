package com.maal.asyncpaymentprocessor.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Repositório responsável por gerenciar o cache de status de saúde dos Payment Processors no Redis.
 * Implementa estratégias de cache com TTL para otimizar consultas de health check.
 * Configurado para funcionar corretamente com GraalVM Native Image.
 */
@Repository
public class RedisHealthCacheRepository {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisHealthCacheRepository.class);
    private static final String HEALTH_CACHE_KEY_PREFIX = "payment_processor_health:";
    private static final Duration CACHE_TTL = Duration.ofMillis(4900); // Cache válido por 4 segundos
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    public RedisHealthCacheRepository(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Armazena o status de saúde de um Payment Processor no cache.
     * Implementa fallback manual em caso de falha na serialização Jackson.
     * 
     * @param type Tipo do Payment Processor (DEFAULT ou FALLBACK)
     * @param healthStatus Status de saúde a ser armazenado
     */
    public void saveHealthStatus(PaymentProcessorType type, HealthStatus healthStatus) {
        try {
            String key = buildCacheKey(type);
            String healthStatusJson = objectMapper.writeValueAsString(healthStatus);
            
            // Salva no Redis com TTL de 4 segundos
            redisTemplate.opsForValue().set(key, healthStatusJson, CACHE_TTL);
                
        } catch (JsonProcessingException e) {
            logger.warn("Falha na serialização Jackson, tentando serialização manual: type={}, erro={}", 
                type, e.getMessage());
            
            // Fallback: serialização manual para compatibilidade com GraalVM Native Image
            try {
                String manualJson = createManualJsonForHealthStatus(healthStatus);
                String key = buildCacheKey(type);
                redisTemplate.opsForValue().set(key, manualJson, CACHE_TTL);
                
            } catch (Exception fallbackException) {
                logger.error("Falha total ao salvar HealthStatus no cache: type={}, erro={}", 
                    type, fallbackException.getMessage());
                // Não propaga a exceção para não quebrar o fluxo principal
            }
        } catch (Exception e) {
            logger.error("Erro inesperado ao salvar HealthStatus no cache: type={}, erro={}", 
                type, e.getMessage());
            // Não propaga a exceção para não quebrar o fluxo principal
        }
    }
    
    /**
     * Recupera o status de saúde de um Payment Processor do cache.
     * Implementa fallback para deserialização manual em caso de falha Jackson.
     * 
     * @param type Tipo do Payment Processor (DEFAULT ou FALLBACK)
     * @return Optional contendo o HealthStatus se encontrado e válido, vazio caso contrário
     */
    public Optional<HealthStatus> getHealthStatus(PaymentProcessorType type) {
        try {
            String key = buildCacheKey(type);
            String healthStatusJson = redisTemplate.opsForValue().get(key);
            
            if (healthStatusJson == null) {
                return Optional.empty();
            }
            
            try {
                // Tenta deserialização Jackson primeiro
                HealthStatus healthStatus = objectMapper.readValue(healthStatusJson, HealthStatus.class);
                return Optional.of(healthStatus);
                
            } catch (JsonProcessingException e) {
                logger.warn("Falha na deserialização Jackson, tentando parsing manual: type={}, erro={}", 
                    type, e.getMessage());
                
                // Fallback: parsing manual para compatibilidade com GraalVM Native Image
                Optional<HealthStatus> manualParsed = parseManualJsonForHealthStatus(healthStatusJson);
                if (manualParsed.isPresent()) {
                    return manualParsed;
                }
                
                // Se o parsing manual falhou, remove entrada corrompida
                removeHealthStatus(type);
                return Optional.empty();
            }
            
        } catch (Exception e) {
            logger.error("Erro inesperado ao recuperar HealthStatus do cache: type={}, erro={}", 
                type, e.getMessage());
            
            // Em caso de erro inesperado, remove entrada problemática
            removeHealthStatus(type);
            return Optional.empty();
        }
    }
    
    /**
     * Remove o status de saúde de um Payment Processor do cache.
     * @param type Tipo do Payment Processor
     */
    public void removeHealthStatus(PaymentProcessorType type) {
        try {
            String key = buildCacheKey(type);
            redisTemplate.delete(key);
            logger.debug("HealthStatus removido do cache: type={}", type);
        } catch (Exception e) {
            logger.error("Erro ao remover HealthStatus do cache: type={}, erro={}", type, e.getMessage());
        }
    }
    
    /**
     * Verifica se existe um status de saúde válido no cache para o Payment Processor.
     * @param type Tipo do Payment Processor
     * @return true se existe entrada válida no cache, false caso contrário
     */
    public boolean hasValidHealthStatus(PaymentProcessorType type) {
        try {
            String key = buildCacheKey(type);
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.error("Erro ao verificar existência de HealthStatus no cache: type={}, erro={}", 
                type, e.getMessage());
            return false;
        }
    }
    
    /**
     * Constrói a chave do cache para um tipo específico de Payment Processor.
     * @param type Tipo do Payment Processor
     * @return Chave formatada para o Redis
     */
    private String buildCacheKey(PaymentProcessorType type) {
        return HEALTH_CACHE_KEY_PREFIX + type.name().toLowerCase();
    }
    
    /**
     * Cria JSON manualmente para HealthStatus como fallback para problemas de serialização.
     * Usado quando Jackson falha em ambientes GraalVM Native Image.
     * 
     * @param healthStatus Status de saúde a ser serializado
     * @return String JSON representando o HealthStatus
     */
    private String createManualJsonForHealthStatus(HealthStatus healthStatus) {
        return String.format(
            "{\"failing\":%s,\"minResponseTime\":%d,\"lastCheckedAt\":\"%s\"}",
            healthStatus.isFailing(),
            healthStatus.getMinResponseTime(),
            healthStatus.getLastCheckedAt().toString()
        );
    }
    
    /**
     * Faz parsing manual de JSON para HealthStatus como fallback para problemas de deserialização.
     * Usado quando Jackson falha em ambientes GraalVM Native Image.
     * 
     * @param json String JSON a ser deserializada
     * @return Optional contendo HealthStatus se parsing bem-sucedido
     */
    private Optional<HealthStatus> parseManualJsonForHealthStatus(String json) {
        try {
            // Parsing simples e robusto para o formato JSON esperado
            boolean failing = json.contains("\"failing\":true");
            
            // Extrai minResponseTime usando regex simples
            int minResponseTime = 0;
            String[] parts = json.split("\"minResponseTime\":");
            if (parts.length > 1) {
                String numberPart = parts[1].split(",")[0].trim();
                minResponseTime = Integer.parseInt(numberPart);
            }
            
            // Usa timestamp atual como fallback para lastCheckedAt
            Instant lastCheckedAt = Instant.now();
            
            return Optional.of(new HealthStatus(failing, minResponseTime, lastCheckedAt));
            
        } catch (Exception e) {
            logger.warn("Falha no parsing manual de HealthStatus JSON: json={}, erro={}", json, e.getMessage());
            return Optional.empty();
        }
    }
}
