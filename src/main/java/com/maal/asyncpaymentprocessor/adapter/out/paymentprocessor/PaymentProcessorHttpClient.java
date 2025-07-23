package com.maal.asyncpaymentprocessor.adapter.out.paymentprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import com.maal.asyncpaymentprocessor.domain.port.out.PaymentProcessorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador HTTP para comunicação com os Payment Processors (Default e Fallback).
 * Implementa as chamadas para processamento de pagamentos e health check usando HttpClient (java.net.http).
 * Refatorado para usar HTTP síncrono e mais confiável.
 */
@Component
public class PaymentProcessorHttpClient implements PaymentProcessorPort {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentProcessorHttpClient.class);

    @Value("${app.payment-processor.default.url}")
    private String DEFAULT_PROCESSOR_URL;

    @Value("${app.payment-processor.fallback.url}")
    private String FALLBACK_PROCESSOR_URL;
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PaymentProcessorHttpClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
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

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(4000))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                logger.debug("Rate limit atingido para health check {}: HTTP 429", type);
                return Optional.empty();
            }

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
                Boolean failing = (Boolean) body.get("failing");
                Integer minResponseTime = (Integer) body.get("minResponseTime");

                HealthStatus healthStatus = new HealthStatus(
                        failing != null ? failing : false,
                        minResponseTime != null ? minResponseTime : 0,
                        Instant.now()
                );

                return Optional.of(healthStatus);
            }

            logger.warn("Health check {} retornou resposta inválida: status={}", type, response.statusCode());
            return Optional.empty();

        } catch (HttpTimeoutException e) {
            logger.warn("Timeout no health check {}: {}", type, e.getMessage());
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            logger.warn("Erro de I/O no health check {}: {}", type, e.getMessage());
            Thread.currentThread().interrupt();
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

            // Monta o corpo JSON
            Map<String, Object> requestBody = Map.of(
                    "correlationId", payment.getCorrelationId().toString(),
                    "amount", payment.getAmount(),
                    "requestedAt", payment.getRequestedAt().toString()
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(4000))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            return response.statusCode() >= 200 && response.statusCode() < 300;

        } catch (HttpTimeoutException e) {
            logger.error("Timeout processando pagamento {} via {}: {}", payment.getCorrelationId(), type, e.getMessage());
            return false;
        } catch (IOException | InterruptedException e) {
            logger.error("Erro de I/O processando pagamento {} via {}: {}", payment.getCorrelationId(), type, e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.error("Erro inesperado processando pagamento {} via {}: {}", payment.getCorrelationId(), type, e.getMessage(), e);
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
