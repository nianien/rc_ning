package com.example.notification.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 通知服务Worker应用入口
 *
 * 负责从队列消费任务并执行HTTP投递
 */
@SpringBootApplication(scanBasePackages = "com.example.notification")
@EntityScan("com.example.notification.common.entity")
@EnableJpaRepositories("com.example.notification.common.repository")
@EnableAsync
@EnableScheduling
public class NotificationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationWorkerApplication.class, args);
    }
}
