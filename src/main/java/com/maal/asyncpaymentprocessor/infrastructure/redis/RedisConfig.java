package com.maal.asyncpaymentprocessor.infrastructure.redis;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

/**
 * Configuração do Redis para cache de health status e filas de pagamentos.
 * Configura RedisTemplate e ObjectMapper otimizados para performance e 
 * compatibilidade com GraalVM Native Image.
 */
@Configuration
@EnableScheduling // Habilita scheduling para health check e worker
public class RedisConfig {

    /**
     * Configura RedisTemplate para operações com Redis.
     * Usa serialização String para chaves e valores para máxima compatibilidade.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Usa StringRedisSerializer para chaves e valores
        // Mais eficiente e legível para debugging
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        
        // Habilita transações se necessário
        template.setEnableTransactionSupport(true);
        
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisLockRegistry redisLockRegistry(RedisConnectionFactory connectionFactory) {
        // O segundo parâmetro é o "key prefix" para os locks no Redis
        // O terceiro parâmetro é o "expire after" em milissegundos.
        // Deve ser maior que o tempo de execução da tarefa agendada (4.9s)
        // e suficiente para permitir failover em caso de falha do líder.
        // Ex: 5 segundos (task) + 7 segundos (margem) = 12 segundos
        return new RedisLockRegistry(connectionFactory, "healthcheck-leader-lock-registry", Duration.ofSeconds(12).toMillis());
    }
}
