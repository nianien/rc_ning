package com.example.notification.worker.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import redis.embedded.RedisServer;

import java.io.IOException;

/**
 * 嵌入式Redis配置
 *
 * 仅在mock模式下启用
 */
@Slf4j
@Configuration
@Profile("mock")
public class EmbeddedRedisConfig {

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    private RedisServer redisServer;

    @PostConstruct
    public void startRedis() throws IOException {
        try {
            redisServer = new RedisServer(redisPort);
            redisServer.start();
            log.info("嵌入式Redis已启动，端口: {}", redisPort);
        } catch (Exception e) {
            log.warn("嵌入式Redis启动失败（可能已有Redis运行）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stopRedis() {
        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
            log.info("嵌入式Redis已停止");
        }
    }
}
