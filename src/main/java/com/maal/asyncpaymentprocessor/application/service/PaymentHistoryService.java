package com.maal.asyncpaymentprocessor.application.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


@Service
public class PaymentHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentHistoryService.class);

    // Chaves para as listas de pagamentos no Redis
    private static final String DEFAULT_PAYMENTS_LIST_KEY = "payments:history:default";
    private static final String FALLBACK_PAYMENTS_LIST_KEY = "payments:history:fallback";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public PaymentHistoryService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void recordPayment(Payment payment) {
        if (payment.getPaymentProcessorType() == null) {
            logger.error("Tentativa de gravar pagamento com tipo de processador nulo. CorrelationId: {}", payment.getCorrelationId());
            return;
        }

        try {
            String paymentJson = objectMapper.writeValueAsString(payment);
            String key = getKeyForProcessor(payment.getPaymentProcessorType());
            redisTemplate.opsForList().leftPush(key, paymentJson);
            logger.debug("Pagamento gravado com sucesso para o processador {}. CorrelationId: {}", payment.getPaymentProcessorType(), payment.getCorrelationId());
        } catch (Exception e) {
            logger.error("Falha ao gravar pagamento no Redis. CorrelationId: {}, Error: {}",
                    payment.getCorrelationId(), e.getMessage(), e);
        }
    }
    private String getKeyForProcessor(PaymentProcessorType processorType) {
        return processorType == PaymentProcessorType.DEFAULT ? DEFAULT_PAYMENTS_LIST_KEY : FALLBACK_PAYMENTS_LIST_KEY;
    }
}
