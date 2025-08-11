package com.maal.asyncpaymentprocessor.application.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maal.asyncpaymentprocessor.application.usecase.ProcessPaymentUseCaseImpl;
import com.maal.asyncpaymentprocessor.domain.model.Payment;
import com.maal.asyncpaymentprocessor.domain.port.in.PaymentProcessorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PaymentProcessorWorkerImpl implements PaymentProcessorWorker {

    private static final Logger logger = LoggerFactory.getLogger(PaymentProcessorWorkerImpl.class);
    
    // Configurações principais da fila
    @Value("${rinha.queue.payments-main}")
    private String paymentQueueKey;
    
    @Value("${rinha.worker.max-concurrent-payments}")
    private int maxConcurrentPayments; // Máximo de pagamentos processados concorrentemente
    
    @Value("${rinha.worker.batch-size}")
    private int batchSize; // Tamanho ideal do lote para leitura em batch
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ProcessPaymentUseCaseImpl processPaymentUseCase;
    private final ExecutorService virtualThreadExecutor;
    private final AtomicInteger activeTaskCount;
    private final AtomicLong completedTaskCount;

    public PaymentProcessorWorkerImpl(RedisTemplate<String, String> redisTemplate,
                                    ObjectMapper objectMapper,
                                    ProcessPaymentUseCaseImpl processPaymentUseCase) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.processPaymentUseCase = processPaymentUseCase;
        this.activeTaskCount = new AtomicInteger(0);
        this.completedTaskCount = new AtomicLong(0);
        
        // Configura Virtual Thread Executor para processamento assíncrono
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }


    @Scheduled(fixedRateString = "${rinha.worker.execution-delay}")
    @Override
    public void execute() {
        try {
            // Estratégia otimizada de processamento em lote
            processBatchPaymentsOptimized();
            
        } catch (Exception e) {
            logger.error("Erro durante execução do worker: {}", e.getMessage(), e);
        }
    }
    

    private void processBatchPaymentsOptimized() {
        try {
            int availableSlots = maxConcurrentPayments - activeTaskCount.get();            
            if (availableSlots <= 0) {
                return;
            }
            int currentBatchSize = Math.min(batchSize, availableSlots);
            readAndProcessStreamOptimized(currentBatchSize);
        } catch (Exception e) {
            logger.error("Erro ao processar lote otimizado: {}", e.getMessage(), e);
        }
    }

    private void readAndProcessStreamOptimized(int maxMessages) {
        
        try {
            // ESTRATÉGIA 1: Tenta obter primeira mensagem sem bloqueio
            String firstMessage = redisTemplate.opsForList().rightPop(paymentQueueKey);
            if (firstMessage == null) {
                return;
            }
            // Processa primeira mensagem imediatamente
            submitPaymentForProcessing(firstMessage);
            // ESTRATÉGIA 2: Se temos uma mensagem, lê e processa rapidamente as demais
            // OTIMIZADO: Processa conforme lê para eliminar duplo loop
            for (int i = 1; i < maxMessages; i++) {
                String message = redisTemplate.opsForList().rightPop(paymentQueueKey);
                if (message == null) {
                    // Fila vazia, sai do loop
                    break;
                }
                // Processa mensagem imediatamente - não armazena em lista
                submitPaymentForProcessing(message);
            }
        } catch (org.springframework.dao.QueryTimeoutException e) {
            logger.error("Timeout ao ler mensagens da fila: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Erro ao ler mensagens da fila: {}", e.getMessage());
        }
    }

    private void submitPaymentForProcessing(String paymentJson) {
        activeTaskCount.incrementAndGet();
        virtualThreadExecutor.submit(() -> processPaymentMessage(paymentJson));
    }
    

    private void processPaymentMessage(String paymentJson) {
        try {
            // Deserializa o pagamento
            Payment payment = objectMapper.readValue(paymentJson, Payment.class);
            
            // Processa o pagamento de forma assíncrona
            var processed = processPaymentUseCase.processPaymentAsync(payment);
                if(processed) {
                    completedTaskCount.incrementAndGet();
                }
        } catch (Exception e) {
            // Mantém apenas log de erro com informação mínima
            logger.error("Erro ao processar mensagem de pagamento: {}", e.getMessage());
        } finally {
            // Decrementa contador de tarefas ativas e incrementa contador de concluídas
            activeTaskCount.decrementAndGet();
        }
    }
}
