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
                    .timeout(Duration.ofMillis(10000))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return isSuccessfulResponse(response.statusCode(), response.body());

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
    
    private boolean isSuccessfulResponse(int statusCode, String responseBody) {
        return isSuccessful200Response(statusCode, responseBody) || isDuplicatePayment422Response(statusCode, responseBody);
    }

    private boolean isSuccessful200Response(int statusCode, String responseBody) {
        return statusCode == 200 && responseBody.contains("payment processed successfully");
    }

    private boolean isDuplicatePayment422Response(int statusCode, String responseBody) {
        return statusCode == 422 && responseBody.toLowerCase().contains("correlationid already exists");
    }
    private String getBaseUrlForType(PaymentProcessorType type) {
        return switch (type) {
            case DEFAULT -> DEFAULT_PROCESSOR_URL;
            case FALLBACK -> FALLBACK_PROCESSOR_URL;
        };
    }
}
