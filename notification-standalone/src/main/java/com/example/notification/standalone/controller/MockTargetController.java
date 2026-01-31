package com.example.notification.standalone.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * 模拟外部系统的各种响应场景：成功、失败、超时等
 */
@Slf4j
@RestController
@RequestMapping("/mock")
@Tag(name = "Mock目标API", description = "模拟外部系统接口（用于测试）")
public class MockTargetController {

    private final Random random = new Random();
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    @Value("${mock.failure-rate:20}")
    private int failureRate;

    @Value("${mock.delay-ms:100}")
    private int delayMs;

    /**
     * 总是成功
     */
    @PostMapping("/always-success")
    @Operation(summary = "总是成功", description = "模拟总是返回成功的外部API")
    public ResponseEntity<Map<String, Object>> alwaysSuccess(@RequestBody Map<String, Object> body) {
        int count = requestCounter.incrementAndGet();
        log.info("Mock API [always-success] #{}: {}", count, body);
        simulateDelay();
        return ResponseEntity.ok(createResponse(true, "处理成功"));
    }

    /**
     * 总是失败
     */
    @PostMapping("/always-fail")
    @Operation(summary = "总是失败", description = "模拟总是返回500错误的外部API")
    public ResponseEntity<Map<String, Object>> alwaysFail(@RequestBody Map<String, Object> body) {
        int count = requestCounter.incrementAndGet();
        log.info("Mock API [always-fail] #{}: {}", count, body);
        simulateDelay();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(createResponse(false, "服务内部错误"));
    }

    /**
     * 随机成功/失败
     */
    @PostMapping("/random")
    @Operation(summary = "随机响应", description = "模拟随机成功或失败的外部API")
    public ResponseEntity<Map<String, Object>> randomResponse(@RequestBody Map<String, Object> body) {
        int count = requestCounter.incrementAndGet();
        log.info("Mock API [random] #{}: {}", count, body);
        simulateDelay();

        boolean success = random.nextInt(100) >= failureRate;
        if (success) {
            return ResponseEntity.ok(createResponse(true, "处理成功"));
        } else {
            HttpStatus errorStatus = randomErrorStatus();
            return ResponseEntity.status(errorStatus)
                .body(createResponse(false, "错误: " + errorStatus.getReasonPhrase()));
        }
    }

    /**
     * 前N次失败，之后成功
     */
    @PostMapping("/fail-then-success/{failCount}")
    @Operation(summary = "失败N次后成功", description = "前N次请求返回500，之后返回200")
    public ResponseEntity<Map<String, Object>> failThenSuccess(
            @PathVariable int failCount,
            @RequestBody Map<String, Object> body) {

        int count = requestCounter.incrementAndGet();
        log.info("Mock API [fail-then-success] #{}/{}: {}", count, failCount, body);
        simulateDelay();

        if (count <= failCount) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(createResponse(false, "模拟失败 " + count + "/" + failCount));
        } else {
            return ResponseEntity.ok(createResponse(true, "第" + count + "次成功"));
        }
    }

    /**
     * 超时
     */
    @PostMapping("/timeout")
    @Operation(summary = "超时", description = "模拟请求超时的外部API")
    public ResponseEntity<Map<String, Object>> timeout(@RequestBody Map<String, Object> body) throws InterruptedException {
        log.info("Mock API [timeout]: {}", body);
        Thread.sleep(60000);
        return ResponseEntity.ok(createResponse(true, "不会返回"));
    }

    /**
     * 重置计数器
     */
    @PostMapping("/reset")
    @Operation(summary = "重置计数器", description = "重置请求计数器")
    public Map<String, Object> reset() {
        int oldCount = requestCounter.getAndSet(0);
        Map<String, Object> result = new HashMap<>();
        result.put("message", "计数器已重置");
        result.put("previousCount", oldCount);
        result.put("timestamp", LocalDateTime.now());
        return result;
    }

    /**
     * 获取计数器状态
     */
    @GetMapping("/status")
    @Operation(summary = "获取状态", description = "获取Mock API的当前状态")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("requestCount", requestCounter.get());
        result.put("failureRate", failureRate);
        result.put("delayMs", delayMs);
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
