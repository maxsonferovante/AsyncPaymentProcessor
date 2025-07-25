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

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker responsável por consumir pagamentos da fila Redis e processá-los de forma assíncrona.
 * OTIMIZADO: Usa técnicas avançadas de batch reading e pipeline operations baseadas.
 */
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

    /**
     * Executa o worker que consome mensagens da fila Redis.
     * OTIMIZADO: Usa estratégia híbrida baseada no artigo Medium para máximo throughput.
     * Frequência configurável via propriedade rinha.worker.execution-delay.
     */
    // Troca fixedDelay -> fixedRate para iniciar ciclos em intervalos fixos
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
    
    /**
     * Processa um lote de pagamentos usando estratégia otimizada com processamento em streaming.
     * OTIMIZADO: Elimina duplo loop - processa mensagens conforme são lidas para menor latência.
     */
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
    
    /**
     * Lê e processa mensagens em streaming - OTIMIZAÇÃO: elimina duplo loop.
     * IMPLEMENTA: Processamento imediato para menor latência e menor uso de memória.
     * 
     * @param maxMessages Número máximo de mensagens para ler e processar
     * @return Número de mensagens processadas
     */
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
    /**
     * Submete uma mensagem de pagamento para processamento assíncrono.
     * HELPER: Extrai lógica de submissão para reutilização e clareza.
     * 
     * @param paymentJson JSON do pagamento a ser processado
     */
    private void submitPaymentForProcessing(String paymentJson) {
        activeTaskCount.incrementAndGet();
        virtualThreadExecutor.submit(() -> processPaymentMessage(paymentJson));
    }
    
    /**
     * Processa uma mensagem de pagamento individual.
     * Deserializa JSON e delega processamento para o Use Case.
     * @param paymentJson JSON do pagamento a ser processado
     */
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
