package com.example.notification.worker.service;

import com.example.notification.common.entity.NotificationTask;
import com.example.notification.common.repository.NotificationTaskRepository;
import com.example.notification.common.service.NotificationQueueService;
import com.example.notification.common.service.NotificationTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通知Worker服务
 *
 * 从队列消费任务并异步执行投递
 * 支持多线程并发处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationWorker {

    private final NotificationQueueService queueService;
    private final NotificationTaskService taskService;
    private final NotificationTaskRepository taskRepository;
    private final NotificationDeliveryService deliveryService;

    /**
     * Worker线程数
     */
    @Value("${notification.worker.concurrency:4}")
    private int workerConcurrency;

    /**
     * 队列拉取超时（秒）
     */
    @Value("${notification.worker.poll-timeout-seconds:5}")
    private int pollTimeoutSeconds;

    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void start() {
        running.set(true);
        executorService = Executors.newFixedThreadPool(workerConcurrency);

        // 启动多个Worker线程
        for (int i = 0; i < workerConcurrency; i++) {
            final int workerId = i;
            executorService.submit(() -> runWorker(workerId));
        }

        log.info("通知Worker已启动，并发数: {}", workerConcurrency);
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

        log.info("通知Worker已停止");
    }

    /**
     * Worker主循环
     */
    private void runWorker(int workerId) {
        log.info("Worker-{} 启动", workerId);

        while (running.get()) {
            try {
                // 从队列获取任务
                String taskId = queueService.popTask(pollTimeoutSeconds);

                if (taskId != null) {
                    processTask(workerId, taskId);
                }

            } catch (Exception e) {
                log.error("Worker-{} 处理异常", workerId, e);
                // 短暂休眠避免快速失败
                sleep(1000);
            }
        }

        log.info("Worker-{} 停止", workerId);
    }

    /**
     * 处理单个任务
     */
    private void processTask(int workerId, String taskId) {
        log.debug("Worker-{} 处理任务: {}", workerId, taskId);

        // 从数据库加载任务
        Optional<NotificationTask> taskOpt = taskRepository.findByTaskId(taskId);

        if (taskOpt.isEmpty()) {
            log.warn("任务不存在: {}", taskId);
            return;
        }

        NotificationTask task = taskOpt.get();

        // 尝试锁定任务（CAS操作，防止并发处理）
        if (!taskService.tryLockTask(task)) {
            log.debug("任务已被其他Worker处理: {}", taskId);
            return;
        }

        // 重新加载任务（获取最新状态）
        task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) {
            return;
        }

        // 执行投递
        deliveryService.deliver(task);
    }

    /**
     * 定时任务：恢复卡住的任务
     * 每分钟执行一次，处理那些处于PROCESSING状态超过5分钟的任务
     */
    @Scheduled(fixedRate = 60000)
    public void recoverStuckTasks() {
        try {
            int recovered = taskService.recoverStuckTasks();
            if (recovered > 0) {
                log.info("恢复了 {} 个卡住的任务", recovered);
            }
        } catch (Exception e) {
            log.error("恢复卡住任务时发生异常", e);
        }
    }

    /**
     * 定时任务：扫描待重试的任务
     * 每10秒执行一次，将到达重试时间的任务重新入队
     */
    @Scheduled(fixedRate = 10000)
    public void scanPendingRetryTasks() {
        try {
            var tasks = taskService.fetchPendingTasks(100);
            for (var task : tasks) {
                queueService.pushTask(task.getTaskId());
            }
            if (!tasks.isEmpty()) {
                log.debug("扫描到 {} 个待处理任务", tasks.size());
            }
        } catch (Exception e) {
            log.error("扫描待重试任务时发生异常", e);
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
