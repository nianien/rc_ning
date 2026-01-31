package com.example.notification.api.controller;

import com.example.notification.common.dto.NotificationRequest;
import com.example.notification.common.dto.NotificationResponse;
import com.example.notification.common.dto.TaskStatusResponse;
import com.example.notification.common.entity.NotificationLog;
import com.example.notification.common.service.NotificationLogService;
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

import java.util.List;

/**
 * 通知服务API控制器
 *
 * 提供通知的提交、查询、重试等接口
 */
@Slf4j
@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "通知服务", description = "接收和管理外部HTTP通知请求")
public class NotificationController {

    private final NotificationTaskService taskService;
    private final NotificationLogService logService;

    /**
     * 提交通知请求
     *
     * 接收业务系统的通知请求，立即返回任务ID，后台异步投递
     */
    @PostMapping
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
    @GetMapping("/{taskId}")
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
    @GetMapping("/{taskId}/logs")
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
    @PostMapping("/{taskId}/retry")
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
}
