package com.example.notification.standalone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 通知服务单体应用
 *
 * 合并API和Worker功能，便于本地测试和开发
 * 使用内嵌H2数据库和Redis，无需任何外部依赖
 */
@SpringBootApplication(scanBasePackages = "com.example.notification")
@EntityScan("com.example.notification.common.entity")
@EnableJpaRepositories("com.example.notification.common.repository")
@EnableAsync
@EnableScheduling
public class NotificationStandaloneApplication {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println(" API通知服务 - 单体模式启动中...");
        System.out.println("=".repeat(60));

        SpringApplication.run(NotificationStandaloneApplication.class, args);

        System.out.println("=".repeat(60));
        System.out.println(" 服务启动成功！");
        System.out.println(" - API文档: http://localhost:8080/swagger-ui.html");
        System.out.println(" - 健康检查: http://localhost:8080/v1/health");
        System.out.println(" - H2控制台: http://localhost:8080/h2-console");
        System.out.println("=".repeat(60));
    }
}
