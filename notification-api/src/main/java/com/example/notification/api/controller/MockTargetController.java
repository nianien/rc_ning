package com.example.notification.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock目标API控制器
 *
 * 仅在mock模式下启用，用于本地测试
 * 模拟外部系统的各种响应场景：成功、失败、超时等
 */
@Slf4j
@RestController
@RequestMapping("/mock")
@Profile("mock")
@Tag(name = "Mock目标API", description = "模拟外部系统接口（仅用于测试）")
public class MockTargetController {

    private final Random random = new Random();
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    /**
     * 模拟失败率（0-100）
     */
    @Value("${mock.failure-rate:20}")
    private int failureRate;

    /**
     * 模拟延迟（毫秒）
     */
    @Value("${mock.delay-ms:100}")
    private int delayMs;

    /**
     * 模拟外部API - 总是成功
     */
    @PostMapping("/always-success")
    @Operation(summary = "总是成功", description = "模拟总是返回成功的外部API")
    public ResponseEntity<Map<String, Object>> alwaysSuccess(@RequestBody Map<String, Object> body) {
        log.info("Mock API收到请求 [always-success]: {}", body);
        simulateDelay();

        Map<String, Object> response = createResponse(true, "处理成功");
        return ResponseEntity.ok(response);
    }

    /**
     * 模拟外部API - 总是失败
     */
    @PostMapping("/always-fail")
    @Operation(summary = "总是失败", description = "模拟总是返回500错误的外部API")
    public ResponseEntity<Map<String, Object>> alwaysFail(@RequestBody Map<String, Object> body) {
        log.info("Mock API收到请求 [always-fail]: {}", body);
        simulateDelay();

        Map<String, Object> response = createResponse(false, "服务内部错误");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 模拟外部API - 随机成功/失败
     */
    @PostMapping("/random")
    @Operation(summary = "随机响应", description = "模拟随机成功或失败的外部API")
    public ResponseEntity<Map<String, Object>> randomResponse(@RequestBody Map<String, Object> body) {
        int count = requestCounter.incrementAndGet();
        log.info("Mock API收到请求 [random] #{}: {}", count, body);
        simulateDelay();

        boolean success = random.nextInt(100) >= failureRate;

        if (success) {
            Map<String, Object> response = createResponse(true, "处理成功");
            return ResponseEntity.ok(response);
        } else {
            // 随机返回不同的错误
            HttpStatus errorStatus = randomErrorStatus();
            Map<String, Object> response = createResponse(false, "错误: " + errorStatus.getReasonPhrase());
            return ResponseEntity.status(errorStatus).body(response);
        }
    }

    /**
     * 模拟外部API - 前N次失败，之后成功
     */
    @PostMapping("/fail-then-success/{failCount}")
    @Operation(summary = "失败N次后成功", description = "前N次请求返回500，之后返回200")
    public ResponseEntity<Map<String, Object>> failThenSuccess(
            @PathVariable int failCount,
            @RequestBody Map<String, Object> body) {

        int count = requestCounter.incrementAndGet();
        log.info("Mock API收到请求 [fail-then-success] #{}: {}", count, body);
        simulateDelay();

        if (count <= failCount) {
            Map<String, Object> response = createResponse(false, "模拟失败 " + count + "/" + failCount);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        } else {
            Map<String, Object> response = createResponse(true, "第" + count + "次成功");
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 模拟外部API - 超时
     */
    @PostMapping("/timeout")
    @Operation(summary = "超时", description = "模拟请求超时的外部API")
    public ResponseEntity<Map<String, Object>> timeout(@RequestBody Map<String, Object> body) throws InterruptedException {
        log.info("Mock API收到请求 [timeout]: {}", body);

        // 延迟60秒，超过默认超时时间
        Thread.sleep(60000);

        return ResponseEntity.ok(createResponse(true, "不会返回"));
    }

    /**
     * 重置计数器
     */
    @PostMapping("/reset")
    @Operation(summary = "重置计数器", description = "重置请求计数器")
    public Map<String, Object> reset() {
        requestCounter.set(0);
        Map<String, Object> result = new HashMap<>();
        result.put("message", "计数器已重置");
        result.put("timestamp", LocalDateTime.now());
        return result;
    }

    private void simulateDelay() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Map<String, Object> createResponse(boolean success, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", message);
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("requestCount", requestCounter.get());
        return response;
    }

    private HttpStatus randomErrorStatus() {
        HttpStatus[] errors = {
            HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.BAD_GATEWAY,
            HttpStatus.SERVICE_UNAVAILABLE,
            HttpStatus.GATEWAY_TIMEOUT
        };
        return errors[random.nextInt(errors.length)];
    }
}
