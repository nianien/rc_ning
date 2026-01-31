package com.example.notification.common.service;

import com.example.notification.common.entity.NotificationLog;
import com.example.notification.common.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通知日志服务
 *
 * 记录每次投递尝试的详细信息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private final NotificationLogRepository logRepository;

    /**
     * 记录投递日志
     */
    @Transactional
    public void logAttempt(String taskId, int attemptNumber, Integer httpStatus,
                          String responseBody, String errorMessage, long latencyMs, boolean success) {
        NotificationLog logEntry = NotificationLog.builder()
            .taskId(taskId)
            .attemptNumber(attemptNumber)
            .httpStatus(httpStatus)
            .responseBody(truncate(responseBody, 2000))
            .errorMessage(truncate(errorMessage, 1000))
            .latencyMs(latencyMs)
            .success(success)
            .build();

        logRepository.save(logEntry);

        if (success) {
            log.debug("投递成功: taskId={}, attempt={}, httpStatus={}, latency={}ms",
                taskId, attemptNumber, httpStatus, latencyMs);
        } else {
            log.warn("投递失败: taskId={}, attempt={}, httpStatus={}, error={}",
                taskId, attemptNumber, httpStatus, errorMessage);
        }
    }

    /**
     * 查询任务的投递日志
     */
    public List<NotificationLog> getTaskLogs(String taskId) {
        return logRepository.findByTaskIdOrderByAttemptNumberAsc(taskId);
    }

    /**
     * 查询最近的投递日志
     */
    public List<NotificationLog> getRecentLogs(String taskId, int limit) {
        return logRepository.findRecentLogs(taskId, PageRequest.of(0, limit));
    }

    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }
}
