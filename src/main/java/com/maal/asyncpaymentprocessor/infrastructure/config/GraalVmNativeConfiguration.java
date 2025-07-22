package com.maal.asyncpaymentprocessor.infrastructure.config;

import com.maal.asyncpaymentprocessor.domain.model.HealthStatus;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.model.PaymentProcessorType;
import com.maal.asyncpaymentprocessor.domain.model.PaymentStatus;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Configuração específica para GraalVM Native Image.
 * Registra hints de reflexão para classes de domínio que são serializadas/deserializadas.
 * 
 * Esta configuração resolve problemas de serialização Jackson em native image,
 * garantindo que o processamento de health check funcione corretamente.
 */
@Configuration
@ImportRuntimeHints(GraalVmNativeConfiguration.class)
public class GraalVmNativeConfiguration implements RuntimeHintsRegistrar {

    /**
     * Registra hints de reflexão para o runtime do GraalVM Native Image.
     * Inclui todas as classes de domínio que precisam de serialização/deserialização.
     * 
     * @param hints Registro de hints para o runtime
     * @param classLoader ClassLoader atual
     */
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        
        // Registra HealthStatus com todas as categorias de acesso necessárias
        hints.reflection()
            .registerType(HealthStatus.class, 
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.DECLARED_FIELDS,
                MemberCategory.DECLARED_CLASSES);
        
        // Registra Payment para compatibilidade com serialização de filas
        hints.reflection()
            .registerType(Payment.class, 
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.DECLARED_FIELDS,
                MemberCategory.DECLARED_CLASSES);
        
        // Registra enums para serialização
        hints.reflection()
            .registerType(PaymentProcessorType.class, 
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.DECLARED_FIELDS);
        
        hints.reflection()
            .registerType(PaymentStatus.class, 
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.DECLARED_FIELDS);
        
        // Registra classes do Java Time para serialização correta de timestamps
        hints.reflection()
            .registerType(TypeReference.of("java.time.Instant"), 
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        
        // Registra classes específicas do Jackson para serialização
        hints.reflection()
            .registerType(TypeReference.of("com.fasterxml.jackson.databind.ObjectMapper"), 
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }

} 