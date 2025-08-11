package com.maal.asyncpaymentprocessor.adapter.in.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.port.out.PaymentQueuePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;


@Component
public class RedisPaymentQueuePublisher implements PaymentQueuePublisher {

    private static final Logger logger = LoggerFactory.getLogger(RedisPaymentQueuePublisher.class);
    
    @Value("${rinha.queue.payments-main}")
    private String paymentQueueKey;
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPaymentQueuePublisher(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }


    @Override
    public void publish(Payment payment) {
        try {
            // Serializa o pagamento para JSON
            String paymentJson = objectMapper.writeValueAsString(payment);
            
            // Publica na fila principal usando LPUSH (adiciona no início da lista)
            // Isso garante que retries vão para o final da fila, mantendo ordem FIFO
            redisTemplate.opsForList().leftPush(paymentQueueKey, paymentJson);
                
        } catch (JsonProcessingException e) {
            logger.error("Erro ao serializar pagamento para JSON: correlationId={}, erro={}", 
                payment.getCorrelationId(), e.getMessage(), e);
            throw new RuntimeException("Falha ao publicar pagamento na fila", e);
            
        } catch (Exception e) {
            logger.error("Erro ao publicar pagamento na fila Redis: correlationId={}, erro={}", 
                payment.getCorrelationId(), e.getMessage(), e);
            throw new RuntimeException("Falha ao publicar pagamento na fila", e);
        }
    }
}