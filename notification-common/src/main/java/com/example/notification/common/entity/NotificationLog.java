package com.example.notification.common.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知投递日志实体
 *
 * 记录每次投递尝试的详细信息，用于审计和问题排查
 */
@Entity
@Table(name = "notification_logs", indexes = {
    @Index(name = "idx_log_task_id", columnList = "taskId"),
    @Index(name = "idx_log_created_at", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的任务ID
     */
    @Column(nullable = false, length = 64)
    private String taskId;

    /**
     * 尝试次数（第几次投递）
     */
    @Column(nullable = false)
    private Integer attemptNumber;

    /**
     * HTTP响应状态码
     */
    private Integer httpStatus;

    /**
     * 响应体（限制大小）
     */
    @Column(columnDefinition = "TEXT")
    private String responseBody;

    /**
     * 错误信息
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 请求延迟（毫秒）
     */
    private Long latencyMs;

    /**
     * 是否成功
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean success = false;

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
