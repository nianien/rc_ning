package com.example.notification.common.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 通知请求DTO
 *
 * 业务系统提交通知时使用的请求格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    /**
     * 来源系统标识
     * 用于区分不同的业务系统，便于监控和排查
     */
    @NotBlank(message = "来源系统不能为空")
    @Size(max = 100, message = "来源系统标识长度不能超过100")
    private String sourceSystem;

    /**
     * 目标API地址
     * 必须是有效的HTTP(S) URL
     */
    @NotBlank(message = "目标URL不能为空")
    @Pattern(regexp = "^https?://.*", message = "目标URL必须以http://或https://开头")
    private String targetUrl;

    /**
     * HTTP方法
     * 默认为POST，支持POST/PUT/PATCH
     */
    @Pattern(regexp = "^(POST|PUT|PATCH)$", message = "HTTP方法仅支持POST/PUT/PATCH")
    @Builder.Default
    private String httpMethod = "POST";

    /**
     * 自定义请求头
     * 例如：Authorization, Content-Type, X-Custom-Header等
     */
    private Map<String, String> headers;

    /**
     * 请求体
     * JSON格式的业务数据
     */
    @NotNull(message = "请求体不能为空")
    private Map<String, Object> body;

    /**
     * 最大重试次数
     * 默认5次，范围1-10
     */
    @Min(value = 1, message = "最大重试次数不能小于1")
    @Max(value = 10, message = "最大重试次数不能超过10")
    @Builder.Default
    private Integer maxRetries = 5;
}
