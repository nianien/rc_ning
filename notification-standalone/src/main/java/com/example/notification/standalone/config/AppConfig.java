package com.example.notification.standalone.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.List;

/**
 * 应用配置
 */
@Slf4j
@Configuration
public class AppConfig {

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${notification.delivery.connect-timeout-ms:5000}")
    private int connectTimeout;

    @Value("${notification.delivery.read-timeout-ms:30000}")
    private int readTimeout;

    private RedisServer redisServer;

    /**
     * RestTemplate配置
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    /**
     * OpenAPI配置
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("API通知服务")
                .description("可靠的HTTP通知投递服务")
                .version("1.0.0"))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("本地开发环境")
            ));
    }

    /**
     * 启动嵌入式Redis
     */
    @PostConstruct
    public void startEmbeddedRedis() throws IOException {
        try {
            redisServer = new RedisServer(redisPort);
            redisServer.start();
            log.info("嵌入式Redis已启动，端口: {}", redisPort);
        } catch (Exception e) {
            log.warn("嵌入式Redis启动失败（可能已有Redis运行）: {}", e.getMessage());
        }
    }

    /**
     * 停止嵌入式Redis
     */
    @PreDestroy
    public void stopEmbeddedRedis() {
        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
            log.info("嵌入式Redis已停止");
        }
    }
}
