package com.maal.asyncpaymentprocessor.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Configura e disponibiliza um {@link java.net.http.HttpClient} singleton como Bean do Spring.
 * O HttpClient padrão do JDK já possui connection pooling interno e, configurado desta forma,
 * oferece melhor performance e menor overhead em comparação a clientes HTTP bloqueantes tradicionais.
 */
@Configuration
public class HttpClientConfig {

    /**
     * Cria um {@link HttpClient} com timeout de conexão e versão HTTP 1.1.
     * Esse bean é reutilizado em toda a aplicação, garantindo menor custo de criação de conexões.
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
} 