# API 通知系统技术设计文档

> **版本**: v2.0
> **技术栈**: Java 17 + Spring Boot 3.x
> **最后更新**: 2026-01-31

---

## 目录

1. [需求理解与问题定义](#1-需求理解与问题定义)
2. [业务场景分析](#2-业务场景分析)
3. [系统边界定义](#3-系统边界定义)
4. [协议设计](#4-协议设计)
5. [系统架构设计](#5-系统架构设计)
6. [核心模块设计](#6-核心模块设计)
7. [可靠性设计](#7-可靠性设计)
8. [扩展性设计](#8-扩展性设计)
9. [演进路线](#9-演进路线)
10. [部署方案](#10-部署方案)
11. [设计决策与取舍](#11-设计决策与取舍)

---

## 1. 需求理解与问题定义

### 1.1 核心问题

企业内部多个业务系统需要在关键事件发生时，通知外部供应商系统。当前面临的挑战：

| 挑战 | 描述 | 影响 |
|------|------|------|
| **耦合问题** | 业务系统直接调用外部API | 外部系统故障影响核心业务 |
| **可靠性问题** | 外部系统不稳定（超时、5xx） | 通知丢失，业务数据不一致 |
| **异构问题** | 不同供应商API格式各异 | 重复开发，维护成本高 |
| **性能问题** | 同步调用阻塞业务流程 | 用户体验差，系统吞吐下降 |

### 1.2 设计目标

```
┌─────────────────────────────────────────────────────────────┐
│  设计目标优先级                                               │
├─────────────────────────────────────────────────────────────┤
│  1. 可靠性 > 实时性 > 吞吐量                                  │
│  2. 简单性 > 功能完备性                                       │
│  3. 可扩展 > 一步到位                                         │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 核心指标要求

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 可用性 | ≥ 99.9% | 全年停机 < 8.76小时 |
| 投递成功率 | ≥ 99.5% | 排除外部系统永久性故障 |
| 正常投递延迟 | P95 < 5s | 首次投递，无重试场景 |
| 消息丢失率 | 0% | At-Least-Once语义 |

---

## 2. 业务场景分析

### 2.1 适用场景（Supported）

#### 场景一：广告归因通知
```
触发条件：用户通过广告渠道引流并完成注册
目标系统：Google Ads、Meta Ads、TikTok Ads
特点：
  - 对实时性要求不高（分钟级可接受）
  - 需要支持多种认证方式（OAuth、API Key）
  - 单向通知，不关心响应内容
```

#### 场景二：CRM数据同步
```
触发条件：用户付费、订阅、升级等商业事件
目标系统：Salesforce、HubSpot、Zoho CRM
特点：
  - 需要较高可靠性（影响销售跟进）
  - Body格式复杂，字段映射多
  - 可能需要批量聚合
```

#### 场景三：库存/ERP同步
```
触发条件：商品购买、库存变动
目标系统：SAP、Oracle ERP、自研WMS
特点：
  - 对数据一致性要求高
  - 通常为内网系统，网络稳定
  - 可能需要确认响应状态
```

#### 场景四：Webhook通知
```
触发条件：用户配置的自定义事件
目标系统：用户指定的任意HTTP端点
特点：
  - 目标地址动态配置
  - 格式灵活多变
  - 需要签名验证机制
```

### 2.2 边界场景（Limited Support）

| 场景 | 限制 | 解决方案 |
|------|------|----------|
| **高频小消息** | 单任务粒度，无聚合 | V2版本支持批量聚合 |
| **大文件传输** | Body限制10MB | 使用文件服务+URL引用 |
| **双向交互** | 仅单向通知 | 提供回调查询接口 |
| **严格顺序** | 不保证全局顺序 | 业务层实现版本号机制 |

### 2.3 不适用场景（Out of Scope）

| 场景 | 原因 | 替代方案 |
|------|------|----------|
| **实时推送（<100ms）** | 队列机制引入延迟 | WebSocket/SSE直连 |
| **分布式事务** | 复杂度过高 | Saga模式/TCC |
| **流式数据传输** | 非设计目标 | Kafka/Flink |
| **RPC调用** | 需要同步响应 | gRPC/Dubbo |
| **消息广播（1:N）** | 当前仅1:1 | 消息队列+订阅模式 |

### 2.4 场景升级路径

```
当前不支持 → 升级思路
─────────────────────────────────────────────────────────
实时推送    → 引入WebSocket网关，通知系统作为后备通道
分布式事务  → 集成Seata，通知作为Saga的一个步骤
消息广播    → 引入Topic概念，支持多订阅者
严格顺序    → 按业务Key分区，保证分区内有序
```

---

## 3. 系统边界定义

### 3.1 系统职责（In Scope）

```
┌─────────────────────────────────────────────────────────────┐
│                      通知系统核心职责                         │
├─────────────────────────────────────────────────────────────┤
│  ✅ 异步解耦    接收请求立即返回，后台异步处理                  │
│  ✅ 可靠投递    At-Least-Once语义，确保送达                    │
│  ✅ 失败重试    指数退避，可配置重试策略                        │
│  ✅ 协议适配    支持多种HTTP方法、Header、Body格式             │
│  ✅ 状态追踪    记录投递状态、日志，支持查询                    │
│  ✅ 可观测性    指标采集、日志结构化、链路追踪                  │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 明确不做（Out of Scope）

| 功能 | 不做原因 | 替代方案 |
|------|----------|----------|
| **幂等性保证** | 依赖业务语义，无法通用 | 业务方在Body中携带唯一ID |
| **事务性投递** | 分布式事务复杂度高 | 业务层补偿或Saga |
| **毫秒级实时** | 与可靠性冲突 | 直接HTTP调用 |
| **内容路由** | 增加系统复杂度 | 业务方指定目标 |
| **回调通知** | 增加耦合 | 提供状态查询API |
| **消息转换** | 职责不清 | 业务方组装好Body |

### 3.3 系统定位

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│    通知系统定位：可靠的HTTP请求代理层                          │
│                                                             │
│    ┌──────────┐      ┌──────────┐      ┌──────────┐        │
│    │ 业务系统  │ ──→  │ 通知系统  │ ──→  │ 外部API  │        │
│    └──────────┘      └──────────┘      └──────────┘        │
│         │                 │                 │               │
│    组装请求内容      可靠投递+重试      实现幂等性           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 协议设计

### 4.1 统一请求协议

设计原则：**通用性** + **可扩展性** + **向后兼容**

```json
{
  // ===== 基础字段（必填）=====
  "sourceSystem": "payment-service",     // 来源系统标识
  "targetUrl": "https://api.example.com/webhook",  // 目标地址
  "body": {                              // 请求体（透传）
    "event": "payment.success",
    "data": { "orderId": "12345", "amount": 99.99 }
  },

  // ===== 扩展字段（选填）=====
  "httpMethod": "POST",                  // HTTP方法，默认POST
  "headers": {                           // 自定义请求头
    "Authorization": "Bearer sk_xxx",
    "X-Request-Id": "req_abc123"
  },
  "options": {                           // 投递选项
    "maxRetries": 5,                     // 最大重试次数
    "timeoutSeconds": 30,                // 请求超时
    "retryOn4xx": false                  // 是否对4xx重试
  },
  "metadata": {                          // 元数据（不传递给目标）
    "traceId": "trace_xyz",              // 链路追踪ID
    "priority": "normal",                // 优先级（预留）
    "tags": ["crm", "salesforce"]        // 标签（用于检索）
  }
}
```

### 4.2 字段说明

#### 必填字段

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| sourceSystem | string | 1-100字符 | 来源系统标识，用于监控和权限控制 |
| targetUrl | string | 有效URL | 目标HTTP(S)地址 |
| body | object | ≤10MB | 请求体，JSON格式 |

#### 选填字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| httpMethod | string | POST | 支持POST/PUT/PATCH |
| headers | map | {} | 自定义请求头 |
| options.maxRetries | int | 5 | 最大重试次数(1-10) |
| options.timeoutSeconds | int | 30 | 请求超时(5-120) |
| options.retryOn4xx | bool | false | 4xx是否重试 |
| metadata.traceId | string | auto | 链路追踪ID |
| metadata.priority | string | normal | 优先级(预留) |
| metadata.tags | array | [] | 自定义标签 |

### 4.3 响应协议

#### 提交响应
```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "message": "通知已提交，正在处理中",
  "createdAt": "2026-01-31T10:00:00Z"
}
```

#### 状态查询响应
```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "sourceSystem": "payment-service",
  "targetUrl": "https://api.example.com/webhook",
  "status": "SUCCESS",
  "retryCount": 2,
  "maxRetries": 5,
  "lastHttpStatus": 200,
  "lastError": null,
  "createdAt": "2026-01-31T10:00:00Z",
  "completedAt": "2026-01-31T10:00:05Z",
  "metadata": {
    "traceId": "trace_xyz",
    "tags": ["crm"]
  }
}
```

### 4.4 状态机定义

```
┌─────────────────────────────────────────────────────────────┐
│                        任务状态流转                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ┌─────────┐                                               │
│   │ PENDING │ ← 初始状态 / 重试等待                          │
│   └────┬────┘                                               │
│        │ Worker获取                                         │
│        ▼                                                    │
│   ┌──────────┐                                              │
│   │PROCESSING│ ← 投递中                                     │
│   └────┬─────┘                                              │
│        │                                                    │
│        ├──── 2xx ────→ ┌─────────┐                          │
│        │               │ SUCCESS │ ← 最终状态               │
│        │               └─────────┘                          │
│        │                                                    │
│        └──── 非2xx/超时/异常                                 │
│                   │                                         │
│                   ├── retries < max ──→ PENDING (延迟重试)  │
│                   │                                         │
│                   └── retries ≥ max ──→ ┌────────┐          │
│                                         │ FAILED │ ← 最终   │
│                                         └────────┘          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 4.5 协议扩展点

预留的扩展能力（当前不实现，但协议已支持）：

| 扩展点 | 字段 | 未来用途 |
|--------|------|----------|
| 优先级队列 | metadata.priority | high/normal/low三级队列 |
| 批量聚合 | options.batchKey | 相同key的任务聚合投递 |
| 延迟投递 | options.delaySeconds | 延迟N秒后投递 |
| 条件重试 | options.retryCondition | 自定义重试条件表达式 |
| 签名验证 | options.signatureKey | Webhook签名 |

---

## 5. 系统架构设计

### 5.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                           业务系统层                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐    │
│  │  用户系统   │  │  订单系统   │  │  支付系统   │  │  库存系统   │    │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘    │
│        │ SDK/HTTP      │ SDK/HTTP      │ SDK/HTTP      │ SDK/HTTP   │
└────────┼───────────────┼───────────────┼───────────────┼────────────┘
         │               │               │               │
         └───────────────┴───────┬───────┴───────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        接入层 (API Gateway)                          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  • 认证鉴权 (JWT/API Key)                                    │   │
│  │  • 限流熔断 (Rate Limiting)                                  │   │
│  │  • 请求路由                                                  │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────┬───────────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        通知系统核心层                                 │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    API Server (Spring Boot)                  │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │   │
│  │  │ POST /notify │  │ GET /status  │  │ POST /retry  │       │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘       │   │
│  └────────────────────────────┬────────────────────────────────┘   │
│                               │                                     │
│              ┌────────────────┼────────────────┐                    │
│              ▼                ▼                ▼                    │
│  ┌──────────────────┐  ┌───────────┐  ┌─────────────────┐          │
│  │   PostgreSQL     │  │   Redis   │  │  Worker Pool    │          │
│  │  (任务持久化)     │  │  (消息队列) │  │  (异步投递)     │          │
│  │                  │  │           │  │                 │          │
│  │ • notification_  │  │ • queue   │  │ • HTTP Client   │          │
│  │   tasks          │  │ • lock    │  │ • Retry Logic   │          │
│  │ • notification_  │  │ • cache   │  │ • Circuit Break │          │
│  │   logs           │  │           │  │                 │          │
│  └──────────────────┘  └───────────┘  └────────┬────────┘          │
│                                                 │                   │
└─────────────────────────────────────────────────┼───────────────────┘
                                                  │
                                                  ▼ HTTP/HTTPS
┌─────────────────────────────────────────────────────────────────────┐
│                           外部系统层                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐    │
│  │ Google Ads │  │ Salesforce │  │  ERP/SAP   │  │  Custom    │    │
│  │            │  │   CRM      │  │            │  │  Webhook   │    │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 模块划分

```
notification-service/
├── notification-common/          # 共享模块
│   ├── entity/                   # 实体类
│   ├── dto/                      # 数据传输对象
│   ├── enums/                    # 枚举定义
│   ├── repository/               # 数据访问层
│   └── service/                  # 业务服务
│
├── notification-api/             # API服务（可独立部署）
│   ├── controller/               # REST控制器
│   ├── config/                   # 配置类
│   └── exception/                # 异常处理
│
├── notification-worker/          # Worker服务（可独立部署）
│   ├── service/                  # 投递服务
│   ├── config/                   # 配置类
│   └── scheduler/                # 定时任务
│
└── notification-standalone/      # 单体应用（开发测试用）
    └── ...                       # 合并API + Worker
```

### 5.3 技术选型

| 组件 | 技术选择 | 选择理由 | 替代方案 |
|------|----------|----------|----------|
| **开发框架** | Spring Boot 3.2 | 生态成熟，团队熟悉 | Quarkus, Micronaut |
| **数据库** | PostgreSQL 15 | JSONB支持，行级锁 | MySQL 8.0 |
| **消息队列** | Redis 7 | 部署简单，性能足够 | RabbitMQ, Kafka |
| **HTTP客户端** | RestTemplate | Spring原生，简单 | WebClient, OkHttp |
| **连接池** | HikariCP | 高性能，Spring默认 | Druid |
| **API文档** | SpringDoc | OpenAPI 3.0标准 | Swagger 2.x |

---

## 6. 核心模块设计

### 6.1 数据模型

#### 任务表 (notification_tasks)

```sql
CREATE TABLE notification_tasks (
    id              BIGSERIAL PRIMARY KEY,
    task_id         VARCHAR(64) UNIQUE NOT NULL,    -- 业务主键
    source_system   VARCHAR(100) NOT NULL,          -- 来源系统
    target_url      TEXT NOT NULL,                  -- 目标地址
    http_method     VARCHAR(10) DEFAULT 'POST',     -- HTTP方法
    headers         JSONB,                          -- 请求头
    body            JSONB NOT NULL,                 -- 请求体

    -- 状态相关
    status          VARCHAR(20) NOT NULL,           -- PENDING/PROCESSING/SUCCESS/FAILED
    retry_count     INT DEFAULT 0,                  -- 已重试次数
    max_retries     INT DEFAULT 5,                  -- 最大重试次数
    next_retry_at   TIMESTAMP,                      -- 下次重试时间

    -- 结果相关
    last_http_status INT,                           -- 最后HTTP状态码
    last_error      TEXT,                           -- 最后错误信息

    -- 元数据
    metadata        JSONB,                          -- 扩展元数据

    -- 时间戳
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP,

    -- 索引
    CONSTRAINT idx_task_id UNIQUE (task_id)
);

-- 查询优化索引
CREATE INDEX idx_status_retry ON notification_tasks(status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_source_system ON notification_tasks(source_system);
CREATE INDEX idx_created_at ON notification_tasks(created_at);
```

#### 日志表 (notification_logs)

```sql
CREATE TABLE notification_logs (
    id              BIGSERIAL PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL,           -- 关联任务
    attempt_number  INT NOT NULL,                   -- 第几次尝试
    http_status     INT,                            -- HTTP状态码
    response_body   TEXT,                           -- 响应体（截断）
    error_message   TEXT,                           -- 错误信息
    latency_ms      BIGINT,                         -- 响应延迟
    success         BOOLEAN NOT NULL,               -- 是否成功
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    -- 索引
    CONSTRAINT idx_log_task_id FOREIGN KEY (task_id)
        REFERENCES notification_tasks(task_id)
);

CREATE INDEX idx_log_task ON notification_logs(task_id);
CREATE INDEX idx_log_created ON notification_logs(created_at);
```

### 6.2 核心流程

#### 任务提交流程

```java
@Service
@RequiredArgsConstructor
public class NotificationTaskService {

    private final NotificationTaskRepository taskRepository;
    private final NotificationQueueService queueService;

    @Transactional
    public NotificationResponse createTask(NotificationRequest request) {
        // 1. 生成任务ID
        String taskId = UUID.randomUUID().toString();

        // 2. 构建任务实体
        NotificationTask task = NotificationTask.builder()
            .taskId(taskId)
            .sourceSystem(request.getSourceSystem())
            .targetUrl(request.getTargetUrl())
            .httpMethod(request.getHttpMethod())
            .headers(request.getHeaders())
            .body(request.getBody())
            .status(TaskStatus.PENDING)
            .maxRetries(request.getOptions().getMaxRetries())
            .metadata(request.getMetadata())
            .build();

        // 3. 持久化（先写库，保证不丢失）
        taskRepository.save(task);

        // 4. 入队（异步处理）
        queueService.pushTask(taskId);

        // 5. 返回结果
        return NotificationResponse.builder()
            .taskId(taskId)
            .status(TaskStatus.PENDING)
            .message("通知已提交")
            .build();
    }
}
```

#### 任务投递流程

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryService {

    private final RestTemplate restTemplate;
    private final NotificationTaskService taskService;
    private final NotificationLogService logService;

    public void deliver(NotificationTask task) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 构建请求
            HttpHeaders headers = buildHeaders(task);
            HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(task.getBody(), headers);

            // 2. 执行HTTP调用
            ResponseEntity<String> response = restTemplate.exchange(
                task.getTargetUrl(),
                HttpMethod.valueOf(task.getHttpMethod()),
                request,
                String.class
            );

            long latency = System.currentTimeMillis() - startTime;

            // 3. 处理响应
            if (response.getStatusCode().is2xxSuccessful()) {
                handleSuccess(task, response, latency);
            } else {
                handleFailure(task, response.getStatusCode().value(),
                    response.getBody(), latency);
            }

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            handleException(task, e, latency);
        }
    }

    private void handleSuccess(NotificationTask task,
                              ResponseEntity<String> response,
                              long latency) {
        // 记录日志
        logService.logAttempt(task, response.getStatusCode().value(),
            response.getBody(), null, latency, true);

        // 更新状态为成功
        taskService.markSuccess(task, response.getStatusCode().value());

        log.info("任务投递成功: taskId={}, httpStatus={}, latency={}ms",
            task.getTaskId(), response.getStatusCode().value(), latency);
    }

    private void handleFailure(NotificationTask task,
                              int httpStatus,
                              String errorBody,
                              long latency) {
        String error = String.format("HTTP %d: %s", httpStatus, errorBody);

        // 记录日志
        logService.logAttempt(task, httpStatus, errorBody, error, latency, false);

        // 判断是否需要重试
        if (shouldRetry(task, httpStatus)) {
            scheduleRetry(task, error, httpStatus);
        } else {
            taskService.markFailed(task, error, httpStatus);
        }
    }

    private boolean shouldRetry(NotificationTask task, int httpStatus) {
        // 重试次数未耗尽
        if (task.getRetryCount() >= task.getMaxRetries()) {
            return false;
        }

        // 5xx 服务端错误 - 重试
        if (httpStatus >= 500) {
            return true;
        }

        // 4xx 客户端错误 - 默认不重试
        if (httpStatus >= 400 && httpStatus < 500) {
            return task.getOptions().isRetryOn4xx();
        }

        return false;
    }

    private void scheduleRetry(NotificationTask task, String error, int httpStatus) {
        // 指数退避: 2^retryCount 秒
        long backoffSeconds = (long) Math.pow(2, task.getRetryCount());
        LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(backoffSeconds);

        taskService.markForRetry(task, error, httpStatus, nextRetry);

        log.warn("任务将重试: taskId={}, retryCount={}/{}, nextRetry={}",
            task.getTaskId(), task.getRetryCount() + 1,
            task.getMaxRetries(), nextRetry);
    }
}
```

### 6.3 Worker设计

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationWorker {

    private final NotificationQueueService queueService;
    private final NotificationTaskRepository taskRepository;
    private final NotificationDeliveryService deliveryService;

    @Value("${notification.worker.concurrency:4}")
    private int concurrency;

    private ExecutorService executorService;
    private volatile boolean running = false;

    @PostConstruct
    public void start() {
        running = true;
        executorService = Executors.newFixedThreadPool(concurrency);

        for (int i = 0; i < concurrency; i++) {
            final int workerId = i;
            executorService.submit(() -> workerLoop(workerId));
        }

        log.info("Worker启动完成，并发数: {}", concurrency);
    }

    private void workerLoop(int workerId) {
        while (running) {
            try {
                // 从队列获取任务（阻塞5秒）
                String taskId = queueService.popTask(5);

                if (taskId != null) {
                    processTask(workerId, taskId);
                }
            } catch (Exception e) {
                log.error("Worker-{} 异常", workerId, e);
                sleep(1000); // 异常后短暂休眠
            }
        }
    }

    private void processTask(int workerId, String taskId) {
        // 1. 加载任务
        NotificationTask task = taskRepository.findByTaskId(taskId)
            .orElse(null);

        if (task == null) {
            log.warn("任务不存在: {}", taskId);
            return;
        }

        // 2. CAS锁定任务（防止重复处理）
        if (!taskService.tryLock(task)) {
            log.debug("任务已被其他Worker处理: {}", taskId);
            return;
        }

        // 3. 执行投递
        log.debug("Worker-{} 处理任务: {}", workerId, taskId);
        deliveryService.deliver(task);
    }

    /**
     * 定时扫描待重试任务
     */
    @Scheduled(fixedRate = 10000) // 10秒
    public void scanPendingTasks() {
        List<NotificationTask> tasks = taskRepository.findPendingTasks(
            TaskStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, 100));

        for (NotificationTask task : tasks) {
            queueService.pushTask(task.getTaskId());
        }
    }

    /**
     * 定时恢复卡住的任务
     */
    @Scheduled(fixedRate = 60000) // 1分钟
    public void recoverStuckTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<NotificationTask> stuckTasks = taskRepository.findStuckTasks(threshold);

        for (NotificationTask task : stuckTasks) {
            log.warn("恢复卡住任务: {}", task.getTaskId());
            task.setStatus(TaskStatus.PENDING);
            taskRepository.save(task);
            queueService.pushTask(task.getTaskId());
        }
    }
}
```

---

## 7. 可靠性设计

### 7.1 投递语义

**选择：At-Least-Once（至少一次）**

| 语义 | 说明 | 适用性 |
|------|------|--------|
| At-Most-Once | 最多投递一次，可能丢失 | ❌ 不满足可靠性要求 |
| **At-Least-Once** | 至少投递一次，可能重复 | ✅ 推荐，业务方实现幂等 |
| Exactly-Once | 恰好一次，无丢失无重复 | ❌ 需要两阶段提交，复杂 |

**实现机制：**
```
1. 先写库后入队 → 防止消息丢失
2. 失败自动重试 → 确保送达
3. Worker ACK机制 → 处理完成才确认
4. 定时扫描恢复 → 兜底保障
```

### 7.2 失败处理策略

```java
/**
 * 失败处理决策树
 */
public class FailureHandler {

    public FailureAction decide(int httpStatus, int retryCount, int maxRetries) {

        // 1. 网络超时/连接失败 → 重试
        if (httpStatus == 0) {
            return retryCount < maxRetries ? RETRY : FAIL;
        }

        // 2. 2xx 成功
        if (httpStatus >= 200 && httpStatus < 300) {
            return SUCCESS;
        }

        // 3. 4xx 客户端错误
        if (httpStatus >= 400 && httpStatus < 500) {
            switch (httpStatus) {
                case 408: // Request Timeout
                case 429: // Too Many Requests
                    return retryCount < maxRetries ? RETRY : FAIL;
                default:
                    return FAIL; // 数据错误，不重试
            }
        }

        // 4. 5xx 服务端错误 → 重试
        if (httpStatus >= 500) {
            return retryCount < maxRetries ? RETRY : FAIL;
        }

        return FAIL;
    }
}
```

### 7.3 重试策略

```
重试间隔（指数退避）:
  第1次重试: 2^1 = 2秒后
  第2次重试: 2^2 = 4秒后
  第3次重试: 2^3 = 8秒后
  第4次重试: 2^4 = 16秒后
  第5次重试: 2^5 = 32秒后

总计等待: 2+4+8+16+32 = 62秒（约1分钟）
```

### 7.4 消息可靠性保障

```
┌─────────────────────────────────────────────────────────────┐
│                     消息可靠性保障机制                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. 持久化优先                                               │
│     请求 → 写入DB（成功）→ 入队 → 返回                        │
│             ↓                                               │
│           (失败) → 直接返回错误                               │
│                                                             │
│  2. 队列可靠                                                 │
│     Redis AOF持久化 + 定时DB扫描兜底                          │
│                                                             │
│  3. Worker可靠                                              │
│     处理完成后才从队列移除                                     │
│     崩溃后任务自动恢复                                        │
│                                                             │
│  4. 状态驱动                                                 │
│     DB为准，队列可丢失可重建                                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 8. 扩展性设计

### 8.1 水平扩展能力

| 组件 | 扩展方式 | 注意事项 |
|------|----------|----------|
| **API Server** | 无状态，直接增加实例 | 需要负载均衡 |
| **Worker** | 无状态，直接增加实例 | 共享同一DB和Redis |
| **数据库** | 读写分离 → 分库分表 | 按source_system分片 |
| **Redis** | 单机 → Cluster | 队列按任务ID哈希分片 |

### 8.2 解耦设计

```
┌─────────────────────────────────────────────────────────────┐
│                        解耦层次                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. 业务系统 与 通知系统 解耦                                  │
│     └─ 通过HTTP API交互，无代码依赖                           │
│                                                             │
│  2. API 与 Worker 解耦                                       │
│     └─ 通过队列（Redis）通信，可独立扩展                       │
│                                                             │
│  3. 存储 与 队列 解耦                                         │
│     └─ DB为准，队列可替换（Redis → RabbitMQ → Kafka）         │
│                                                             │
│  4. 投递逻辑 与 重试逻辑 解耦                                  │
│     └─ 重试策略可配置，支持自定义                              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 8.3 扩展点设计

#### 8.3.1 协议扩展接口

```java
/**
 * 请求转换器接口（预留）
 * 用于支持不同协议的请求构建
 */
public interface RequestTransformer {

    /**
     * 转换请求体
     */
    Map<String, Object> transformBody(NotificationTask task);

    /**
     * 构建请求头
     */
    HttpHeaders buildHeaders(NotificationTask task);

    /**
     * 生成签名
     */
    default String generateSignature(NotificationTask task) {
        return null;
    }
}

// 默认实现：透传
@Component
public class PassThroughTransformer implements RequestTransformer {
    // 直接透传，不做转换
}

// 扩展实现示例：Salesforce适配器
public class SalesforceTransformer implements RequestTransformer {
    // Salesforce特定格式转换
}
```

#### 8.3.2 重试策略扩展

```java
/**
 * 重试策略接口
 */
public interface RetryStrategy {

    /**
     * 计算下次重试延迟（秒）
     */
    long calculateDelay(int retryCount);

    /**
     * 判断是否应该重试
     */
    boolean shouldRetry(int httpStatus, int retryCount, int maxRetries);
}

// 默认：指数退避
@Component
public class ExponentialBackoffStrategy implements RetryStrategy {
    @Override
    public long calculateDelay(int retryCount) {
        return (long) Math.pow(2, retryCount);
    }
}

// 扩展：固定间隔
public class FixedIntervalStrategy implements RetryStrategy {
    private final long intervalSeconds;

    @Override
    public long calculateDelay(int retryCount) {
        return intervalSeconds;
    }
}
```

#### 8.3.3 队列扩展

```java
/**
 * 队列服务接口
 */
public interface QueueService {

    void pushTask(String taskId);

    void pushTask(String taskId, long delaySeconds);

    String popTask(long timeoutSeconds);

    long getQueueSize();
}

// 当前实现：Redis
@Component
@Profile("redis")
public class RedisQueueService implements QueueService { }

// 扩展实现：RabbitMQ
@Component
@Profile("rabbitmq")
public class RabbitMQQueueService implements QueueService { }

// 扩展实现：Kafka
@Component
@Profile("kafka")
public class KafkaQueueService implements QueueService { }
```

---

## 9. 演进路线

### 9.1 阶段规划

```
┌─────────────────────────────────────────────────────────────────────┐
│                          系统演进路线                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Phase 1: MVP (当前)                                                │
│  ├─ 目标: 快速上线，验证需求                                          │
│  ├─ 规模: 日通知量 < 100万                                           │
│  ├─ 架构: 单体应用 + Redis + PostgreSQL                              │
│  └─ 特性: 基础投递、重试、状态查询                                     │
│                                                                     │
│         │                                                           │
│         ▼ 触发条件: 日通知量 > 50万 或 需要独立扩展                     │
│                                                                     │
│  Phase 2: 微服务化                                                   │
│  ├─ 目标: 支持独立扩展，提升可用性                                     │
│  ├─ 规模: 日通知量 100万 - 1000万                                     │
│  ├─ 架构: API + Worker 分离部署                                       │
│  └─ 新增: 优先级队列、批量聚合、熔断降级                                │
│                                                                     │
│         │                                                           │
│         ▼ 触发条件: 日通知量 > 500万 或 Redis成为瓶颈                   │
│                                                                     │
│  Phase 3: 高可用                                                     │
│  ├─ 目标: 支持大规模，高可用                                          │
│  ├─ 规模: 日通知量 1000万 - 5000万                                    │
│  ├─ 架构: Redis Cluster + 数据库读写分离                              │
│  └─ 新增: 多租户隔离、动态限流、灰度发布                                │
│                                                                     │
│         │                                                           │
│         ▼ 触发条件: 日通知量 > 3000万 或 需要复杂消息路由                │
│                                                                     │
│  Phase 4: 企业级                                                     │
│  ├─ 目标: 企业级通知中台                                              │
│  ├─ 规模: 日通知量 > 5000万                                           │
│  ├─ 架构: 切换到Kafka + 分库分表                                      │
│  └─ 新增: 消息路由、事件溯源、多数据中心                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 9.2 各阶段技术选型

| 阶段 | 消息队列 | 数据库 | 部署方式 |
|------|----------|--------|----------|
| **Phase 1** | Redis List | PostgreSQL单机 | 单体应用 |
| **Phase 2** | Redis + 多队列 | PostgreSQL主从 | K8s微服务 |
| **Phase 3** | Redis Cluster | PostgreSQL + 分片 | K8s多副本 |
| **Phase 4** | Kafka | TiDB/CockroachDB | 多集群 |

### 9.3 功能演进

| 功能 | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|------|---------|---------|---------|---------|
| 基础投递 | ✅ | ✅ | ✅ | ✅ |
| 重试机制 | ✅ | ✅ | ✅ | ✅ |
| 状态查询 | ✅ | ✅ | ✅ | ✅ |
| 优先级队列 | ❌ | ✅ | ✅ | ✅ |
| 批量聚合 | ❌ | ✅ | ✅ | ✅ |
| 熔断降级 | ❌ | ✅ | ✅ | ✅ |
| 多租户 | ❌ | ❌ | ✅ | ✅ |
| 动态限流 | ❌ | ❌ | ✅ | ✅ |
| 消息路由 | ❌ | ❌ | ❌ | ✅ |
| 事件溯源 | ❌ | ❌ | ❌ | ✅ |

### 9.4 不支持场景的升级思路

| 场景 | 当前状态 | 升级方案 | 预计阶段 |
|------|----------|----------|----------|
| **实时推送** | 不支持 | 引入WebSocket网关，通知系统作为降级方案 | Phase 2 |
| **分布式事务** | 不支持 | 集成Seata，通知作为Saga步骤 | Phase 3 |
| **消息广播** | 不支持 | 引入Topic订阅机制 | Phase 3 |
| **严格顺序** | 不支持 | 按业务Key分区，保证分区内有序 | Phase 2 |
| **大文件** | Body≤10MB | 集成文件服务，Body传URL引用 | Phase 2 |
| **复杂路由** | 不支持 | 引入规则引擎，支持条件路由 | Phase 4 |

---

## 10. 部署方案

### 10.1 本地开发（Mock模式）

```bash
# 一键启动，使用内嵌H2和Redis
./run.sh start

# 访问
# API文档: http://localhost:8080/swagger-ui.html
# 健康检查: http://localhost:8080/v1/health
```

### 10.2 Docker Compose（测试环境）

```yaml
version: '3.8'

services:
  notification-api:
    build: ./notification-api
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/notification
      SPRING_DATA_REDIS_HOST: redis
    depends_on:
      - postgres
      - redis

  notification-worker:
    build: ./notification-worker
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/notification
      SPRING_DATA_REDIS_HOST: redis
    depends_on:
      - postgres
      - redis
    deploy:
      replicas: 4

  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: notification
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: secret
    volumes:
      - pg_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data

volumes:
  pg_data:
  redis_data:
```

### 10.3 Kubernetes（生产环境）

```yaml
# API Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notification-api
spec:
  replicas: 3
  selector:
    matchLabels:
      app: notification-api
  template:
    spec:
      containers:
      - name: api
        image: notification-api:latest
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
```

### 10.4 资源规划

| 组件 | 配置 | 支持规模 |
|------|------|----------|
| API Server × 2 | 2C4G | 1000 QPS |
| Worker × 4 | 2C4G | 200万/天 |
| PostgreSQL | 4C8G | 1000万任务 |
| Redis | 2C4G | 10万QPS |

---

## 11. 设计决策与取舍

### 11.1 核心设计原则

```
1. 简单优先     避免过度设计，满足80%场景即可
2. 渐进演进     保持扩展性，根据需求迭代
3. 可靠优先     可靠性 > 实时性 > 吞吐量
4. 可观测       监控和日志比功能更重要
```

### 11.2 关键决策说明

| 决策 | 选择 | 理由 |
|------|------|------|
| **语言选型** | Java 17 | 生态成熟，团队熟悉，长期维护 |
| **消息队列** | Redis | 运维简单，性能够用，可平滑升级 |
| **投递语义** | At-Least-Once | 平衡可靠性和复杂度 |
| **重试策略** | 指数退避 | 业界标准，避免雪崩 |
| **幂等性** | 业务方负责 | 无法通用实现，属于业务语义 |

### 11.3 AI建议未采纳说明

| AI建议 | 未采纳原因 |
|--------|------------|
| 使用Kafka | 运维成本高，当前规模Redis足够 |
| 实现Exactly-Once | 需要两阶段提交，复杂度过高 |
| 支持消息转换 | 增加系统复杂度，应由业务方处理 |
| 自定义重试表达式 | 增加配置复杂度，固定策略够用 |

### 11.4 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| Redis宕机 | 新任务无法入队 | DB定时扫描兜底 |
| 外部API长期不可用 | 任务积压 | 熔断降级，人工介入 |
| 数据库满 | 服务不可用 | 定期清理历史数据 |
| Worker全部崩溃 | 任务停止处理 | 健康检查+自动重启 |

---

## 附录

### A. 快速启动

```bash
# 1. 编译
./run.sh build

# 2. 启动
./run.sh start

# 3. 测试
./test-api.sh
```

### B. API速查

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /v1/notifications | 提交通知 |
| GET | /v1/notifications/{id} | 查询状态 |
| GET | /v1/notifications/{id}/logs | 查询日志 |
| POST | /v1/notifications/{id}/retry | 手动重试 |
| GET | /v1/health | 健康检查 |
| GET | /v1/stats | 统计信息 |

### C. 配置参数

```yaml
notification:
  worker:
    concurrency: 4              # Worker并发数
    poll-timeout-seconds: 5     # 队列拉取超时
  delivery:
    connect-timeout-ms: 5000    # 连接超时
    read-timeout-ms: 30000      # 读取超时
  retry:
    max-retries: 5              # 最大重试次数
    base-delay-seconds: 2       # 基础延迟
```

---

**文档结束**
