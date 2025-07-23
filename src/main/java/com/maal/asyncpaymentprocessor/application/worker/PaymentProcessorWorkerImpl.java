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
import java.util.List;
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
    
    // Configurações do worker - todas configuráveis via properties/environment
    @Value("${rinha.worker.blocking-timeout}")
    private int blockingTimeoutMs; // Timeout para operações blocking Redis (ms)
    
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
    private final AtomicLong totalTaskCount;
    private final AtomicLong batchCount; // Métrica para monitoramento

    public PaymentProcessorWorkerImpl(RedisTemplate<String, String> redisTemplate,
                                    ObjectMapper objectMapper,
                                    ProcessPaymentUseCaseImpl processPaymentUseCase) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.processPaymentUseCase = processPaymentUseCase;
        this.activeTaskCount = new AtomicInteger(0);
        this.completedTaskCount = new AtomicLong(0);
        this.totalTaskCount = new AtomicLong(0);
        this.batchCount = new AtomicLong(0);
        
        // Configura Virtual Thread Executor para processamento assíncrono
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Executa o worker que consome mensagens da fila Redis.
     * OTIMIZADO: Usa estratégia híbrida baseada no artigo Medium para máximo throughput.
     * Frequência configurável via propriedade rinha.worker.execution-delay.
     */
    @Scheduled(fixedDelayString = "${rinha.worker.execution-delay}")
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
            
            // Determina tamanho do lote baseado na capacidade disponível
            int currentBatchSize = Math.min(batchSize, availableSlots);
            
            // OTIMIZAÇÃO: Processa mensagens em streaming conforme são lidas
            int processedCount = readAndProcessStreamOptimized(currentBatchSize);
            
            if (processedCount > 0) {
                batchCount.incrementAndGet();
            }
            
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
    private int readAndProcessStreamOptimized(int maxMessages) {
        int processedCount = 0;
        
        try {
            // ESTRATÉGIA 1: Operação blocking para primeira mensagem
            String firstMessage = redisTemplate.opsForList().rightPop(paymentQueueKey, Duration.ofMillis(blockingTimeoutMs));
            
            if (firstMessage == null) {
                return 0;
            }
            
            // Processa primeira mensagem imediatamente
            submitPaymentForProcessing(firstMessage);
            processedCount++;
            
            // ESTRATÉGIA 2: Se temos uma mensagem, lê e processa rapidamente as demais
            // OTIMIZADO: Processa conforme lê para eliminar duplo loop
            for (int i = 1; i < maxMessages; i++) {
                String message = redisTemplate.opsForList().rightPop(paymentQueueKey);
                if (message == null) {
                    break;
                }
                // Processa mensagem imediatamente - não armazena em lista
                submitPaymentForProcessing(message);
                processedCount++;
            }
            
            return processedCount;
            
        } catch (org.springframework.dao.QueryTimeoutException e) {
            logger.error("Timeout ao ler mensagens da fila: {}", e.getMessage());
            return processedCount;            
        } catch (Exception e) {
            logger.error("Erro ao ler mensagens da fila: {}", e.getMessage());
            return processedCount;
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
        totalTaskCount.incrementAndGet();
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
            processPaymentUseCase.processPaymentAsync(payment);                 
                
        } catch (Exception e) {
            // Mantém apenas log de erro com informação mínima
            logger.error("Erro ao processar mensagem de pagamento: {}", e.getMessage());
        } finally {
            // Decrementa contador de tarefas ativas e incrementa contador de concluídas
            activeTaskCount.decrementAndGet();
            completedTaskCount.incrementAndGet();
        }
    }
    
    /**
     * Obtém status atual do worker para monitoramento.
     * IMPLEMENTA: Métricas avançadas baseadas nas recomendações do artigo.
     * @return Status atual com métricas de desempenho detalhadas
     */
    public WorkerStatus getWorkerStatus() {
        return new WorkerStatus(
            activeTaskCount.get(),
            completedTaskCount.get(),
            totalTaskCount.get(),
            batchCount.get()
        );
    }
    
    /**
     * Classe interna para representar status do worker.
     * EXPANDIDA: Com métricas adicionais para monitoramento baseado no artigo.
     */
    public static class WorkerStatus {
        private final int activeThreads;
        private final long completedTasks;
        private final long totalTasks;
        private final long totalBatches; // Nova métrica
        
        public WorkerStatus(int activeThreads, long completedTasks, long totalTasks, long totalBatches) {
            this.activeThreads = activeThreads;
            this.completedTasks = completedTasks;
            this.totalTasks = totalTasks;
            this.totalBatches = totalBatches;
        }
        
        public int getActiveThreads() {
            return activeThreads;
        }
        
        public long getCompletedTasks() {
            return completedTasks;
        }
        
        public long getTotalTasks() {
            return totalTasks;
        }
        
        public long getTotalBatches() {
            return totalBatches;
        }
        
        // Métrica derivada para análise de performance
        public double getAverageTasksPerBatch() {
            return totalBatches > 0 ? (double) totalTasks / totalBatches : 0.0;
        }
    }
}
