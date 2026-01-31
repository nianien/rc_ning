package com.example.notification.common.dto;

import com.example.notification.common.enums.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 任务状态查询响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusResponse {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 来源系统
     */
    private String sourceSystem;

    /**
     * 目标URL
     */
    private String targetUrl;

    /**
     * 当前状态
     */
    private TaskStatus status;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetries;

    /**
     * 最后一次HTTP状态码
     */
    private Integer lastHttpStatus;

    /**
     * 最后一次错误信息
     */
    private String lastError;

    /**
     * 下次重试时间
     */
    private LocalDateTime nextRetryAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;
}
