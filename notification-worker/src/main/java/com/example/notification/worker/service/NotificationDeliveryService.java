package com.example.notification.worker.service;

import com.example.notification.common.entity.NotificationTask;
import com.example.notification.common.service.NotificationLogService;
import com.example.notification.common.service.NotificationTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知投递服务
 *
 * 执行实际的HTTP请求投递
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

    private final NotificationTaskService taskService;
    private final NotificationLogService logService;
    private final RestTemplate restTemplate;

    @Value("${notification.delivery.timeout-seconds:30}")
    private int timeoutSeconds;

    /**
     * 投递单个通知任务
     */
    public void deliver(NotificationTask task) {
        log.info("开始投递任务: taskId={}, targetUrl={}, attempt={}",
            task.getTaskId(), task.getTargetUrl(), task.getRetryCount() + 1);

        LocalDateTime startTime = LocalDateTime.now();
        long latencyMs = 0;

        try {
            // 构建HTTP请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 添加自定义头
            if (task.getHeaders() != null) {
                task.getHeaders().forEach(headers::set);
            }

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(task.getBody(), headers);

            // 执行HTTP请求
            ResponseEntity<String> response = executeRequest(task, requestEntity);

            latencyMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

            // 判断是否成功（2xx状态码）
            if (response.getStatusCode().is2xxSuccessful()) {
                // 成功
                taskService.markSuccess(task, response.getStatusCode().value());
                logService.logAttempt(
                    task.getTaskId(),
                    task.getRetryCount() + 1,
                    response.getStatusCode().value(),
                    response.getBody(),
                    null,
                    latencyMs,
                    true
                );
            } else {
                // 非2xx响应，视为失败
                handleFailure(task, response.getStatusCode().value(),
                    "HTTP " + response.getStatusCode().value() + ": " + response.getBody(),
                    latencyMs);
            }

        } catch (HttpStatusCodeException e) {
            // HTTP错误响应
            latencyMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            handleFailure(task, e.getStatusCode().value(),
                "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                latencyMs);

        } catch (ResourceAccessException e) {
            // 连接超时或网络错误
            latencyMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            handleFailure(task, null, "网络错误: " + e.getMessage(), latencyMs);

        } catch (Exception e) {
            // 其他异常
            latencyMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            handleFailure(task, null, "系统异常: " + e.getMessage(), latencyMs);
        }
    }

    private ResponseEntity<String> executeRequest(NotificationTask task, HttpEntity<Map<String, Object>> requestEntity) {
        String method = task.getHttpMethod().toUpperCase();

        return switch (method) {
            case "POST" -> restTemplate.postForEntity(task.getTargetUrl(), requestEntity, String.class);
            case "PUT" -> restTemplate.exchange(task.getTargetUrl(), HttpMethod.PUT, requestEntity, String.class);
            case "PATCH" -> restTemplate.exchange(task.getTargetUrl(), HttpMethod.PATCH, requestEntity, String.class);
            default -> throw new IllegalArgumentException("不支持的HTTP方法: " + method);
        };
    }

    private void handleFailure(NotificationTask task, Integer httpStatus, String error, long latencyMs) {
        // 记录日志
        logService.logAttempt(
            task.getTaskId(),
            task.getRetryCount() + 1,
            httpStatus,
            null,
            error,
            latencyMs,
            false
        );

        // 更新任务状态
        taskService.markFailed(task, error, httpStatus);
    }
}
