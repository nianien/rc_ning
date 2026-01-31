package com.example.notification.standalone;

import com.example.notification.common.dto.NotificationRequest;
import com.example.notification.common.dto.NotificationResponse;
import com.example.notification.common.enums.TaskStatus;
import com.example.notification.common.service.NotificationTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通知服务集成测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationServiceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NotificationTaskService taskService;

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void testHealthCheck() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
            getBaseUrl() + "/v1/health", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    void testCreateNotification() {
        NotificationRequest request = NotificationRequest.builder()
            .sourceSystem("test-service")
            .targetUrl(getBaseUrl() + "/mock/always-success")
            .body(Map.of("test", "data"))
            .maxRetries(3)
            .build();

        ResponseEntity<NotificationResponse> response = restTemplate.postForEntity(
            getBaseUrl() + "/v1/notifications",
            request,
            NotificationResponse.class
        );

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getTaskId());
        assertEquals(TaskStatus.PENDING, response.getBody().getStatus());
    }

    @Test
    void testGetNotificationStatus() throws InterruptedException {
        // 创建任务
        NotificationRequest request = NotificationRequest.builder()
            .sourceSystem("test-service")
            .targetUrl(getBaseUrl() + "/mock/always-success")
            .body(Map.of("test", "status-check"))
            .build();

        ResponseEntity<NotificationResponse> createResponse = restTemplate.postForEntity(
            getBaseUrl() + "/v1/notifications",
            request,
            NotificationResponse.class
        );

        String taskId = createResponse.getBody().getTaskId();

        // 等待处理
        Thread.sleep(3000);

        // 查询状态
        ResponseEntity<Map> statusResponse = restTemplate.getForEntity(
            getBaseUrl() + "/v1/notifications/" + taskId,
            Map.class
        );

        assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
        assertNotNull(statusResponse.getBody());
        assertEquals(taskId, statusResponse.getBody().get("taskId"));
    }

    @Test
    void testNotificationNotFound() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
            getBaseUrl() + "/v1/notifications/non-existent-task-id",
            Map.class
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void testStats() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
            getBaseUrl() + "/v1/stats", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("queueSize"));
        assertNotNull(response.getBody().get("taskStats"));
    }

    @Test
    void testMockAlwaysSuccess() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            getBaseUrl() + "/mock/always-success",
            Map.of("test", "data"),
            Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("success"));
    }

    @Test
    void testMockAlwaysFail() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            getBaseUrl() + "/mock/always-fail",
            Map.of("test", "data"),
            Map.class
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("success"));
    }
}
