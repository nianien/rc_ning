package com.example.notification.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI文档配置
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("API通知服务")
                .description("可靠的HTTP通知投递服务 - 接收业务系统的外部API通知请求，确保稳定可靠地送达目标地址")
                .version("1.0.0")
                .contact(new Contact()
                    .name("开发团队")
                    .email("dev@example.com")))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("本地开发环境"),
                new Server().url("http://localhost:8080").description("Mock测试环境")
            ));
    }
}
