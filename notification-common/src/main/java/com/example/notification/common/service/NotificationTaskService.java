package com.example.notification.common.service;

import com.example.notification.common.dto.NotificationRequest;
import com.example.notification.common.dto.NotificationResponse;
import com.example.notification.common.dto.TaskStatusResponse;
import com.example.notification.common.entity.NotificationTask;
import com.example.notification.common.enums.TaskStatus;
import com.example.notification.common.repository.NotificationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知任务服务
 *
 * 处理任务的创建、查询、状态更新等业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationTaskService {

    private final NotificationTaskRepository taskRepository;
    private final NotificationQueueService queueService;

    /**
     * 创建通知任务
     *
     * 1. 生成唯一taskId
     * 2. 持久化到数据库（先写库保证不丢失）
     * 3. 推送到队列
     */
    @Transactional
    public NotificationResponse createTask(NotificationRequest request) {
        String taskId = UUID.randomUUID().toString();

        // 构建任务实体
        NotificationTask task = NotificationTask.builder()
            .taskId(taskId)
            .sourceSystem(request.getSourceSystem())
            .targetUrl(request.getTargetUrl())
            .httpMethod(request.getHttpMethod())
            .headers(request.getHeaders())
            .body(request.getBody())
            .status(TaskStatus.PENDING)
            .maxRetries(request.getMaxRetries())
            .retryCount(0)
            .build();

        // 持久化到数据库
        taskRepository.save(task);
        log.info("任务已创建: taskId={}, sourceSystem={}, targetUrl={}",
            taskId, request.getSourceSystem(), request.getTargetUrl());

        // 推送到队列
        queueService.pushTask(taskId);

        return NotificationResponse.builder()
            .taskId(taskId)
            .status(TaskStatus.PENDING)
            .message("通知已提交，正在处理中")
            .build();
    }

    /**
     * 查询任务状态
     */
    public Optional<TaskStatusResponse> getTaskStatus(String taskId) {
        return taskRepository.findByTaskId(taskId)
            .map(this::toStatusResponse);
    }

    /**
     * 手动重试失败的任务
     */
    @Transactional
    public Optional<NotificationResponse> retryTask(String taskId) {
        return taskRepository.findByTaskId(taskId)
            .filter(task -> task.getStatus() == TaskStatus.FAILED)
            .map(task -> {
                // 重置状态
                task.setStatus(TaskStatus.PENDING);
                task.setRetryCount(0);
                task.setNextRetryAt(null);
                task.setCompletedAt(null);
                taskRepository.save(task);

                // 重新入队
                queueService.pushTask(taskId);

                log.info("任务已重新入队: taskId={}", taskId);
                return NotificationResponse.builder()
                    .taskId(taskId)
                    .status(TaskStatus.PENDING)
                    .message("任务已重新加入队列")
                    .build();
            });
    }

    /**
     * 获取待处理的任务（供Worker使用）
     */
    public List<NotificationTask> fetchPendingTasks(int batchSize) {
        return taskRepository.findPendingTasks(
            TaskStatus.PENDING,
            LocalDateTime.now(),
            PageRequest.of(0, batchSize)
        );
    }

    /**
     * 尝试锁定任务（CAS操作，防止并发处理）
     */
    @Transactional
    public boolean tryLockTask(NotificationTask task) {
        int updated = taskRepository.updateStatusWithLock(
            task.getId(),
            TaskStatus.PENDING,
            TaskStatus.PROCESSING,
            LocalDateTime.now()
        );
        return updated > 0;
    }

    /**
     * 更新任务状态为成功
     */
    @Transactional
    public void markSuccess(NotificationTask task, int httpStatus) {
        task.setStatus(TaskStatus.SUCCESS);
        task.setLastHttpStatus(httpStatus);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
        log.info("任务投递成功: taskId={}, httpStatus={}", task.getTaskId(), httpStatus);
    }

    /**
     * 更新任务状态为失败
     *
     * 如果还有重试次数，设置下次重试时间并重新入队
     * 否则标记为最终失败
     */
    @Transactional
    public void markFailed(NotificationTask task, String error, Integer httpStatus) {
        task.setRetryCount(task.getRetryCount() + 1);
        task.setLastError(error);
        task.setLastHttpStatus(httpStatus);

        if (task.canRetry()) {
            // 计算下次重试时间（指数退避）
            long backoffSeconds = task.calculateBackoffSeconds();
            task.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds));
            task.setStatus(TaskStatus.PENDING);
            taskRepository.save(task);

            // 延迟入队（通过定时任务扫描处理）
            log.warn("任务投递失败，将在{}秒后重试: taskId={}, retryCount={}/{}, error={}",
                backoffSeconds, task.getTaskId(), task.getRetryCount(), task.getMaxRetries(), error);
        } else {
            // 重试次数耗尽，标记为最终失败
            task.setStatus(TaskStatus.FAILED);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
            log.error("任务投递最终失败: taskId={}, error={}", task.getTaskId(), error);
        }
    }

    /**
     * 恢复卡住的任务
     * 处理那些处于PROCESSING状态超过5分钟的任务
     */
    @Transactional
    public int recoverStuckTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<NotificationTask> stuckTasks = taskRepository.findStuckTasks(threshold);

        for (NotificationTask task : stuckTasks) {
            log.warn("恢复卡住的任务: taskId={}", task.getTaskId());
            task.setStatus(TaskStatus.PENDING);
            taskRepository.save(task);
            queueService.pushTask(task.getTaskId());
        }

        return stuckTasks.size();
    }

    private TaskStatusResponse toStatusResponse(NotificationTask task) {
        return TaskStatusResponse.builder()
            .taskId(task.getTaskId())
            .sourceSystem(task.getSourceSystem())
            .targetUrl(task.getTargetUrl())
            .status(task.getStatus())
            .retryCount(task.getRetryCount())
            .maxRetries(task.getMaxRetries())
            .lastHttpStatus(task.getLastHttpStatus())
            .lastError(task.getLastError())
            .nextRetryAt(task.getNextRetryAt())
            .createdAt(task.getCreatedAt())
            .completedAt(task.getCompletedAt())
            .build();
    }
}
