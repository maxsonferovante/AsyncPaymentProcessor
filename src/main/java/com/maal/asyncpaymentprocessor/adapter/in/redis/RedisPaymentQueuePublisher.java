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

/**
 * Adaptador Redis para publicação de pagamentos em fila.
 * SIMPLIFICADO: Usa apenas uma fila principal - pagamentos com falha vão para o final da mesma fila.
 * Serializa pagamentos em JSON e os publica na fila Redis para processamento assíncrono.
 */
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

    /**
     * Publica um pagamento na fila principal do Redis.
     * NOVA ABORDAGEM: Todos os pagamentos (novos e retries) vão para a mesma fila.
     * Em caso de retry, o pagamento vai para o FINAL da fila (LPUSH).
     * @param payment Pagamento a ser publicado
     */
    @Override
    public void publish(Payment payment) {
        try {
            // Serializa o pagamento para JSON
            String paymentJson = objectMapper.writeValueAsString(payment);
            
            // Publica na fila principal usando LPUSH (adiciona no início da lista)
            // Isso garante que retries vão para o final da fila, mantendo ordem FIFO
            redisTemplate.opsForList().leftPush(paymentQueueKey, paymentJson);
            
            logger.debug("Pagamento publicado na fila principal {}: correlationId={}, status={}, retryCount={}", 
                paymentQueueKey, payment.getCorrelationId(), payment.getStatus(), payment.getRetryCount());
                
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
    
    /**
     * Método utilitário para obter estatísticas da fila.
     * SIMPLIFICADO: Apenas uma fila para monitorar.
     * Útil para monitoramento e métricas.
     * @return Estatísticas da fila Redis
     */
    public QueueStats getQueueStats() {
        try {
            Long mainQueueSize = redisTemplate.opsForList().size(paymentQueueKey);
            
            return new QueueStats(
                mainQueueSize != null ? mainQueueSize : 0L,
                0L, // Não há mais fila de retry
                mainQueueSize != null ? mainQueueSize : 0L
            );
            
        } catch (Exception e) {
            logger.error("Erro ao obter estatísticas da fila: {}", e.getMessage(), e);
            return new QueueStats(0L, 0L, 0L);
        }
    }
    
    /**
     * Classe interna para estatísticas das filas.
     * SIMPLIFICADA: Apenas uma fila principal.
     */
    public static class QueueStats {
        private final long mainQueueSize;
        private final long retryQueueSize; // Mantido para compatibilidade, sempre 0
        private final long totalQueueSize;
        
        public QueueStats(long mainQueueSize, long retryQueueSize, long totalQueueSize) {
            this.mainQueueSize = mainQueueSize;
            this.retryQueueSize = retryQueueSize;
            this.totalQueueSize = totalQueueSize;
        }
        
        public long getMainQueueSize() {
            return mainQueueSize;
        }
        
        public long getRetryQueueSize() {
            return retryQueueSize;
        }
        
        public long getTotalQueueSize() {
            return totalQueueSize;
        }
    }
}