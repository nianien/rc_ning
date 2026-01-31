package com.example.notification.common.entity;

import com.example.notification.common.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知任务实体
 *
 * 存储所有待投递的通知任务，包括目标地址、请求内容、状态等信息
 */
@Entity
@Table(name = "notification_tasks", indexes = {
    @Index(name = "idx_status_next_retry", columnList = "status, nextRetryAt"),
    @Index(name = "idx_task_id", columnList = "taskId", unique = true),
    @Index(name = "idx_source_system", columnList = "sourceSystem"),
    @Index(name = "idx_created_at", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 任务唯一标识（UUID）
     */
    @Column(nullable = false, unique = true, length = 64)
    private String taskId;

    /**
     * 来源业务系统标识
     */
    @Column(nullable = false, length = 100)
    private String sourceSystem;

    /**
     * 目标API地址
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String targetUrl;

    /**
     * HTTP方法（POST/PUT/PATCH）
     */
    @Column(length = 10)
    @Builder.Default
    private String httpMethod = "POST";

    /**
     * 自定义请求头（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "TEXT")
    private Map<String, String> headers;

    /**
     * 请求体（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> body;

    /**
     * 任务状态
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    /**
     * 已重试次数
     */
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 最大重试次数
     */
    @Builder.Default
    private Integer maxRetries = 5;

    /**
     * 下次重试时间
     */
    private LocalDateTime nextRetryAt;

    /**
     * 最后一次错误信息
     */
    @Column(columnDefinition = "TEXT")
    private String lastError;

    /**
     * 最后一次HTTP响应状态码
     */
    private Integer lastHttpStatus;

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 完成时间（成功或最终失败）
     */
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 是否可以重试
     */
    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    /**
     * 计算下次重试的退避时间（指数退避：1s, 2s, 4s, 8s, 16s...）
     */
    public long calculateBackoffSeconds() {
        return (long) Math.pow(2, retryCount);
    }
}
