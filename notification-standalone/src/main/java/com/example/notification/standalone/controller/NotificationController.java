package com.example.notification.standalone.controller;

import com.example.notification.common.dto.NotificationRequest;
import com.example.notification.common.dto.NotificationResponse;
import com.example.notification.common.dto.TaskStatusResponse;
import com.example.notification.common.entity.NotificationLog;
import com.example.notification.common.enums.TaskStatus;
import com.example.notification.common.repository.NotificationTaskRepository;
import com.example.notification.common.service.NotificationLogService;
import com.example.notification.common.service.NotificationQueueService;
import com.example.notification.common.service.NotificationTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知服务API控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "通知服务", description = "接收和管理外部HTTP通知请求")
public class NotificationController {

    private final NotificationTaskService taskService;
    private final NotificationLogService logService;
    private final NotificationTaskRepository taskRepository;
    private final NotificationQueueService queueService;

    /**
     * 提交通知请求
     */
    @PostMapping("/notifications")
    @Operation(summary = "提交通知请求", description = "接收通知请求并异步投递到目标地址")
    public ResponseEntity<NotificationResponse> createNotification(
            @Valid @RequestBody NotificationRequest request) {

        log.info("收到通知请求: sourceSystem={}, targetUrl={}",
            request.getSourceSystem(), request.getTargetUrl());

        NotificationResponse response = taskService.createTask(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 查询通知状态
     */
    @GetMapping("/notifications/{taskId}")
    @Operation(summary = "查询通知状态", description = "根据任务ID查询通知的当前状态")
    public ResponseEntity<TaskStatusResponse> getNotificationStatus(
            @Parameter(description = "任务ID") @PathVariable String taskId) {

        return taskService.getTaskStatus(taskId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询通知的投递日志
     */
    @GetMapping("/notifications/{taskId}/logs")
    @Operation(summary = "查询投递日志", description = "获取通知的所有投递尝试记录")
    public ResponseEntity<List<NotificationLog>> getNotificationLogs(
            @Parameter(description = "任务ID") @PathVariable String taskId) {

        List<NotificationLog> logs = logService.getTaskLogs(taskId);
        if (logs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(logs);
    }

    /**
     * 手动重试失败的通知
     */
    @PostMapping("/notifications/{taskId}/retry")
    @Operation(summary = "手动重试", description = "重新投递失败的通知任务")
    public ResponseEntity<NotificationResponse> retryNotification(
            @Parameter(description = "任务ID") @PathVariable String taskId) {

        return taskService.retryTask(taskId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.badRequest().body(
                NotificationResponse.builder()
                    .taskId(taskId)
                    .message("任务不存在或状态不是失败")
                    .build()
            ));
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查服务是否正常运行")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", LocalDateTime.now());
        result.put("service", "notification-standalone");
        return result;
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/stats")
    @Operation(summary = "统计信息", description = "获取任务队列和处理统计")
    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();

        // 队列统计
        result.put("queueSize", queueService.getQueueSize());

        // 任务状态统计
        Map<String, Long> taskStats = new HashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            taskStats.put(status.name(), taskRepository.countByStatus(status));
        }
        result.put("taskStats", taskStats);

        result.put("timestamp", LocalDateTime.now());
        return result;
    }
}
