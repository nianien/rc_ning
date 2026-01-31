package com.example.notification.common.dto;

import com.example.notification.common.enums.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知响应DTO
 *
 * 提交通知后返回的响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    /**
     * 任务ID（用于后续查询状态）
     */
    private String taskId;

    /**
     * 任务状态
     */
    private TaskStatus status;

    /**
     * 响应消息
     */
    private String message;
}
