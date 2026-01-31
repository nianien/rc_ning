package com.example.notification.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通知队列服务
 *
 * 基于Redis List实现简单的消息队列
 * 使用LPUSH/RPOP实现FIFO队列
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationQueueService {

    private final StringRedisTemplate redisTemplate;

    /**
     * 队列名称
     */
    public static final String QUEUE_NAME = "notification:queue";

    /**
     * 推送任务到队列
     */
    public void pushTask(String taskId) {
        redisTemplate.opsForList().leftPush(QUEUE_NAME, taskId);
        log.debug("任务已入队: taskId={}", taskId);
    }

    /**
     * 从队列获取任务（阻塞式）
     *
     * @param timeout 超时时间（秒）
     * @return taskId，如果超时返回null
     */
    public String popTask(long timeout) {
        return redisTemplate.opsForList().rightPop(QUEUE_NAME, timeout, TimeUnit.SECONDS);
    }

    /**
     * 批量获取任务
     *
     * @param batchSize 批量大小
     * @return taskId列表
     */
    public List<String> popTasks(int batchSize) {
        return redisTemplate.opsForList().rightPop(QUEUE_NAME, batchSize);
    }

    /**
     * 获取队列长度
     */
    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(QUEUE_NAME);
        return size != null ? size : 0;
    }

    /**
     * 清空队列（仅用于测试）
     */
    public void clearQueue() {
        redisTemplate.delete(QUEUE_NAME);
    }
}
