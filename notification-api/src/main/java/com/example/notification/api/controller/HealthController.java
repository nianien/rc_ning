package com.example.notification.api.controller;

import com.example.notification.common.enums.TaskStatus;
import com.example.notification.common.repository.NotificationTaskRepository;
import com.example.notification.common.service.NotificationQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查和统计接口
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "系统管理", description = "健康检查和统计信息")
public class HealthController {

    private final NotificationTaskRepository taskRepository;
    private final NotificationQueueService queueService;

    /**
     * 健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查服务是否正常运行")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", LocalDateTime.now());
        result.put("service", "notification-api");
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
