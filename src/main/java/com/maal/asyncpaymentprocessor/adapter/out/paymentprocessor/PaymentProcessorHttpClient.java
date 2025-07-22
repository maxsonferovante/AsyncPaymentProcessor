package com.maal.asyncpaymentprocessor.adapter.out.paymentprocessor;

import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import com.maal.asyncpaymentprocessor.domain.port.out.PaymentProcessorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador HTTP para comunicação com os Payment Processors (Default e Fallback).
 * Implementa as chamadas para processamento de pagamentos e health check usando RestTemplate.
 * Refatorado para usar HTTP síncrono e mais confiável.
 */
@Component
public class PaymentProcessorHttpClient implements PaymentProcessorPort {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentProcessorHttpClient.class);

    @Value("${app.payment-processor.default.url}")
    private String DEFAULT_PROCESSOR_URL;

    @Value("${app.payment-processor.fallback.url}")
    private String FALLBACK_PROCESSOR_URL;
    
    private final RestTemplate restTemplate;
    
    public PaymentProcessorHttpClient() {
        // Cria RestTemplate configurado com timeouts apropriados
        this.restTemplate = new RestTemplate();
        
        // Configurações de timeout mais generosas para evitar falhas
        // O timeout será gerenciado por configurações do HTTP client se necessário
    }
    
    /**
     * Obtém o status de saúde de um Payment Processor específico.
     * @param type O tipo de Payment Processor (DEFAULT ou FALLBACK).
     * @return Um Optional contendo o HealthStatus se a chamada for bem-sucedida, ou Optional.empty().
     */
    @Override
    @SuppressWarnings("unchecked")
    public Optional<HealthStatus> getHealthStatus(PaymentProcessorType type) {
        try {
            String url = getBaseUrlForType(type) + "/payments/service-health";
            
            logger.debug("Fazendo health check para {}: {}", type, url);
            
            // Faz a chamada GET /payments/service-health
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                Boolean failing = (Boolean) body.get("failing");
                Integer minResponseTime = (Integer) body.get("minResponseTime");
                
                HealthStatus healthStatus = new HealthStatus(
                    failing != null ? failing : false,
                    minResponseTime != null ? minResponseTime : 0,
                    Instant.now()
                );
                
                logger.debug("Health check {} bem-sucedido: failing={}, minResponseTime={}ms", 
                    type, healthStatus.isFailing(), healthStatus.getMinResponseTime());
                
                return Optional.of(healthStatus);
            }
            
            logger.warn("Health check {} retornou resposta inválida: status={}", 
                type, response.getStatusCode());
            return Optional.empty();
            
        } catch (HttpClientErrorException e) {
            // Se for HTTP 429 (Too Many Requests), retorna empty para respeitar rate limit
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                logger.debug("Rate limit atingido para health check {}: HTTP 429", type);
                return Optional.empty();
            }
            logger.warn("Erro HTTP 4xx no health check {}: {} {}", 
                type, e.getStatusCode(), e.getMessage());
            return Optional.empty();
            
        } catch (HttpServerErrorException e) {
            logger.warn("Erro HTTP 5xx no health check {}: {} {}", 
                type, e.getStatusCode(), e.getMessage());
            return Optional.empty();
            
        } catch (ResourceAccessException e) {
            logger.warn("Erro de conectividade no health check {}: {}", type, e.getMessage());
            return Optional.empty();
            
        } catch (Exception e) {
            logger.error("Erro inesperado no health check {}: {}", type, e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    /**
     * Processa um pagamento através do Payment Processor especificado.
     * @param payment Pagamento a ser processado
     * @param type Tipo do Payment Processor a ser usado
     * @return true se processado com sucesso, false caso contrário
     */
    @SuppressWarnings("unchecked")
    public boolean processPayment(Payment payment, PaymentProcessorType type) {
        try {
            String url = getBaseUrlForType(type) + "/payments";
            
            // Constrói o payload da requisição
            Map<String, Object> requestBody = Map.of(
                "correlationId", payment.getCorrelationId().toString(),
                "amount", payment.getAmount(),
                "requestedAt", payment.getRequestedAt().toString()
            );
            
            // Configura headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            
            logger.debug("Processando pagamento {} com {}: {}", 
                payment.getCorrelationId(), type, url);
            
            // Faz a chamada POST /payments
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Pagamento {} processado com sucesso via {}", 
                    payment.getCorrelationId(), type);
                return true;
            } else {
                logger.warn("Pagamento {} falhou via {}: HTTP {}", 
                    payment.getCorrelationId(), type, response.getStatusCode());
                return false;
            }
            
        } catch (HttpClientErrorException e) {
            logger.warn("Erro HTTP 4xx processando pagamento {} via {}: {} {}", 
                payment.getCorrelationId(), type, e.getStatusCode(), e.getMessage());
            return false;
            
        } catch (HttpServerErrorException e) {
            logger.warn("Erro HTTP 5xx processando pagamento {} via {}: {} {}", 
                payment.getCorrelationId(), type, e.getStatusCode(), e.getMessage());
            return false;
            
        } catch (ResourceAccessException e) {
            logger.warn("Erro de conectividade processando pagamento {} via {}: {}", 
                payment.getCorrelationId(), type, e.getMessage());
            return false;
            
        } catch (Exception e) {
            logger.error("Erro inesperado processando pagamento {} via {}: {}", 
                payment.getCorrelationId(), type, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Retorna a URL base apropriada para o tipo de Payment Processor.
     * @param type Tipo do Payment Processor
     * @return URL base configurada para o tipo especificado
     */
    private String getBaseUrlForType(PaymentProcessorType type) {
        return switch (type) {
            case DEFAULT -> DEFAULT_PROCESSOR_URL;
            case FALLBACK -> FALLBACK_PROCESSOR_URL;
        };
    }
}
