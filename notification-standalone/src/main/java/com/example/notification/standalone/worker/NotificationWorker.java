package com.example.notification.standalone.worker;

import com.example.notification.common.entity.NotificationTask;
import com.example.notification.common.repository.NotificationTaskRepository;
import com.example.notification.common.service.NotificationLogService;
import com.example.notification.common.service.NotificationQueueService;
import com.example.notification.common.service.NotificationTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通知Worker服务
 *
 * 从队列消费任务并执行HTTP投递
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationWorker {

    private final NotificationQueueService queueService;
    private final NotificationTaskService taskService;
    private final NotificationTaskRepository taskRepository;
    private final NotificationLogService logService;
    private final RestTemplate restTemplate;

    @Value("${notification.worker.concurrency:4}")
    private int workerConcurrency;

    @Value("${notification.worker.poll-timeout-seconds:5}")
    private int pollTimeoutSeconds;

    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void start() {
        running.set(true);
        executorService = Executors.newFixedThreadPool(workerConcurrency);

        for (int i = 0; i < workerConcurrency; i++) {
            final int workerId = i;
            executorService.submit(() -> runWorker(workerId));
        }

        log.info("Worker已启动，并发数: {}", workerConcurrency);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Worker已停止");
    }

    private void runWorker(int workerId) {
        log.debug("Worker-{} 启动", workerId);

        while (running.get()) {
            try {
                String taskId = queueService.popTask(pollTimeoutSeconds);
                if (taskId != null) {
                    processTask(workerId, taskId);
                }
            } catch (Exception e) {
                log.error("Worker-{} 异常", workerId, e);
                sleep(1000);
            }
        }
    }

    private void processTask(int workerId, String taskId) {
        log.debug("Worker-{} 处理: {}", workerId, taskId);

        Optional<NotificationTask> taskOpt = taskRepository.findByTaskId(taskId);
        if (taskOpt.isEmpty()) {
            log.warn("任务不存在: {}", taskId);
            return;
        }

        NotificationTask task = taskOpt.get();

        if (!taskService.tryLockTask(task)) {
            log.debug("任务已被处理: {}", taskId);
            return;
        }

        task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) return;

        deliver(task);
    }

    private void deliver(NotificationTask task) {
        log.info("投递: taskId={}, url={}, attempt={}",
            task.getTaskId(), task.getTargetUrl(), task.getRetryCount() + 1);

        LocalDateTime startTime = LocalDateTime.now();
        long latencyMs;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (task.getHeaders() != null) {
                task.getHeaders().forEach(headers::set);
            }

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(task.getBody(), headers);
            ResponseEntity<String> response = executeRequest(task, requestEntity);

            latencyMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

            if (response.getStatusCode().is2xxSuccessful()) {
                taskService.markSuccess(task, response.getStatusCode().value());
                logService.logAttempt(task.getTaskId(), task.getRetryCount() + 1,
                    response.getStatusCode().value(), response.getBody(), null, latencyMs, true);
            } else {
                handleFailure(task, response.getStatusCode().value(),
                    "HTTP " + response.getStatusCode().value(), latencyMs);
            }

        } catch (HttpStatusCodeException e) {
            latencyMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            handleFailure(task, e.getStatusCode().value(),
                "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), latencyMs);

        } catch (ResourceAccessException e) {
            latencyMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            handleFailure(task, null, "网络错误: " + e.getMessage(), latencyMs);

        } catch (Exception e) {
            latencyMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            handleFailure(task, null, "异常: " + e.getMessage(), latencyMs);
        }
    }

    private ResponseEntity<String> executeRequest(NotificationTask task, HttpEntity<Map<String, Object>> requestEntity) {
        String method = task.getHttpMethod().toUpperCase();
        return switch (method) {
            case "POST" -> restTemplate.postForEntity(task.getTargetUrl(), requestEntity, String.class);
            case "PUT" -> restTemplate.exchange(task.getTargetUrl(), HttpMethod.PUT, requestEntity, String.class);
            case "PATCH" -> restTemplate.exchange(task.getTargetUrl(), HttpMethod.PATCH, requestEntity, String.class);
            default -> throw new IllegalArgumentException("不支持的HTTP方法: " + method);
        };
    }

    private void handleFailure(NotificationTask task, Integer httpStatus, String error, long latencyMs) {
        logService.logAttempt(task.getTaskId(), task.getRetryCount() + 1,
            httpStatus, null, error, latencyMs, false);
        taskService.markFailed(task, error, httpStatus);
    }

    @Scheduled(fixedRate = 60000)
    public void recoverStuckTasks() {
        try {
            int recovered = taskService.recoverStuckTasks();
            if (recovered > 0) {
                log.info("恢复了 {} 个卡住的任务", recovered);
            }
        } catch (Exception e) {
            log.error("恢复任务异常", e);
        }
    }

    @Scheduled(fixedRate = 10000)
    public void scanPendingRetryTasks() {
        try {
            var tasks = taskService.fetchPendingTasks(100);
            for (var task : tasks) {
                queueService.pushTask(task.getTaskId());
            }
        } catch (Exception e) {
            log.error("扫描任务异常", e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
