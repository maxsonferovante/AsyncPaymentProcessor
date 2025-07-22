package com.maal.asyncpaymentprocessor.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableScheduling;

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
    
    /**
     * Configura ObjectMapper para serialização JSON.
     * Otimizado para serializar objetos Payment e HealthStatus.
     * Configurado especificamente para compatibilidade com GraalVM Native Image.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Adiciona suporte para Java 8+ Time API (Instant, LocalDateTime, etc.)
        mapper.registerModule(new JavaTimeModule());
        
        // Configurações para melhor performance e compatibilidade
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        
        // Configurações específicas para GraalVM Native Image
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false);
        mapper.configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false);
        
        // Configurações para melhorar a performance
        mapper.configure(SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED, false);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        
        return mapper;
    }
}
