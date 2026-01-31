package com.example.notification.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 通知服务API应用入口
 *
 * 提供REST API接收业务系统的通知请求
 */
@SpringBootApplication(scanBasePackages = "com.example.notification")
@EntityScan("com.example.notification.common.entity")
@EnableJpaRepositories("com.example.notification.common.repository")
@EnableAsync
@EnableScheduling
public class NotificationApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApiApplication.class, args);
    }
}
