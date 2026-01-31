# API通知服务使用手册

## 1. 项目概述

API通知服务是一个可靠的HTTP通知投递系统，用于接收企业内部业务系统的外部API通知请求，并确保稳定可靠地送达目标地址。

### 1.1 核心特性

- **异步解耦**：接收通知请求后立即返回，后台异步投递
- **可靠投递**：至少一次（At-Least-Once）投递语义
- **自动重试**：失败自动重试，指数退避策略（1s, 2s, 4s, 8s, 16s）
- **灵活配置**：支持自定义Headers、Body、重试次数
- **可观测性**：记录投递状态和日志，支持状态查询
- **本地Mock**：支持无外部依赖的本地测试模式

### 1.2 技术栈

- Java 17 + Spring Boot 3.2
- H2（Mock模式）/ PostgreSQL（生产模式）
- 嵌入式Redis（Mock模式）/ Redis（生产模式）
- Spring Data JPA + Spring Data Redis

## 2. 快速开始

### 2.1 环境要求

- JDK 17+
- Maven 3.8+
- （可选）Docker & Docker Compose

### 2.2 本地Mock模式启动

Mock模式使用内嵌的H2数据库和Redis，无需任何外部依赖。

```bash
# 1. 克隆项目
git clone <repo-url>
cd rc_ning

# 2. 编译项目
mvn clean package -DskipTests

# 3. 启动API服务（端口8080）
java -jar notification-api/target/notification-api-1.0.0-SNAPSHOT.jar

# 4. 新开终端，启动Worker服务（端口8081）
java -jar notification-worker/target/notification-worker-1.0.0-SNAPSHOT.jar
```

### 2.3 验证服务

```bash
# 健康检查
curl http://localhost:8080/v1/health

# 访问Swagger UI
open http://localhost:8080/swagger-ui.html
```

## 3. API接口说明

### 3.1 提交通知请求

**POST** `/v1/notifications`

接收通知请求，立即返回任务ID，后台异步投递。

**请求体：**
```json
{
  "sourceSystem": "payment-service",
  "targetUrl": "https://api.example.com/webhook",
  "httpMethod": "POST",
  "headers": {
    "Authorization": "Bearer sk_xxx",
    "X-Event-Type": "payment.success"
  },
  "body": {
    "user_id": "12345",
    "amount": 99.99,
    "currency": "USD"
  },
  "maxRetries": 5
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sourceSystem | string | 是 | 来源系统标识 |
| targetUrl | string | 是 | 目标API地址（http/https） |
| httpMethod | string | 否 | HTTP方法，默认POST |
| headers | object | 否 | 自定义请求头 |
| body | object | 是 | 请求体 |
| maxRetries | int | 否 | 最大重试次数，默认5 |

**响应：**
```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "message": "通知已提交，正在处理中"
}
```

### 3.2 查询通知状态

**GET** `/v1/notifications/{taskId}`

**响应：**
```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "sourceSystem": "payment-service",
  "targetUrl": "https://api.example.com/webhook",
  "status": "SUCCESS",
  "retryCount": 0,
  "maxRetries": 5,
  "lastHttpStatus": 200,
  "lastError": null,
  "nextRetryAt": null,
  "createdAt": "2024-01-31T10:00:00",
  "completedAt": "2024-01-31T10:00:01"
}
```

**状态说明：**

| 状态 | 说明 |
|------|------|
| PENDING | 待处理，等待投递 |
| PROCESSING | 处理中，正在投递 |
| SUCCESS | 成功（最终状态） |
| FAILED | 失败（最终状态，重试耗尽） |

### 3.3 查询投递日志

**GET** `/v1/notifications/{taskId}/logs`

返回该任务的所有投递尝试记录。

### 3.4 手动重试

**POST** `/v1/notifications/{taskId}/retry`

重新投递失败的任务（仅对状态为FAILED的任务有效）。

### 3.5 统计信息

**GET** `/v1/stats`

返回队列和任务状态统计。

## 4. Mock测试模式

### 4.1 Mock目标API

Mock模式下提供模拟外部API，用于测试：

| 端点 | 说明 |
|------|------|
| POST /mock/always-success | 总是返回200成功 |
| POST /mock/always-fail | 总是返回500失败 |
| POST /mock/random | 随机成功或失败（默认20%失败率） |
| POST /mock/fail-then-success/{n} | 前n次失败，之后成功 |
| POST /mock/timeout | 模拟超时 |
| POST /mock/reset | 重置请求计数器 |

### 4.2 测试示例

```bash
# 1. 测试成功场景
curl -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "test",
    "targetUrl": "http://localhost:8080/mock/always-success",
    "body": {"test": "data"}
  }'

# 2. 测试重试场景（前2次失败，第3次成功）
curl -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "test",
    "targetUrl": "http://localhost:8080/mock/fail-then-success/2",
    "body": {"test": "retry"},
    "maxRetries": 5
  }'

# 3. 查询任务状态
curl http://localhost:8080/v1/notifications/{taskId}

# 4. 查询统计信息
curl http://localhost:8080/v1/stats
```

## 5. 生产环境部署

### 5.1 使用Docker Compose

```bash
# 启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

### 5.2 环境变量配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| DB_USERNAME | 数据库用户名 | postgres |
| DB_PASSWORD | 数据库密码 | postgres |
| REDIS_HOST | Redis主机 | localhost |
| REDIS_PORT | Redis端口 | 6379 |
| REDIS_PASSWORD | Redis密码 | 空 |

### 5.3 切换到生产模式

```bash
java -jar notification-api-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod
java -jar notification-worker-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## 6. 架构说明

### 6.1 模块结构

```
notification-service/
├── notification-common/    # 共享模块（实体、DTO、Repository、Service）
├── notification-api/       # API服务（接收请求、提供查询）
└── notification-worker/    # Worker服务（异步投递）
```

### 6.2 数据流

```
业务系统
    ↓ POST /v1/notifications
API服务
    ↓ 1. 持久化到数据库
    ↓ 2. 推送taskId到Redis队列
    ↓ 返回taskId
Worker服务
    ↓ 1. 从Redis队列获取taskId
    ↓ 2. 从数据库加载任务详情
    ↓ 3. 执行HTTP请求
    ↓ 4. 更新状态，记录日志
外部API
```

### 6.3 重试机制

- **指数退避**：1s → 2s → 4s → 8s → 16s
- **最大重试**：默认5次，可配置1-10次
- **失败恢复**：定时扫描卡住的任务并重新入队

## 7. 监控与运维

### 7.1 健康检查端点

- `/v1/health` - 服务健康状态
- `/actuator/health` - Spring Boot Actuator健康检查
- `/actuator/metrics` - 指标数据

### 7.2 数据库控制台

Mock模式下可访问H2控制台：
- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:notification`
- 用户名: `sa`，密码: 空

### 7.3 日志级别调整

```bash
# 运行时调整日志级别
curl -X POST http://localhost:8080/actuator/loggers/com.example.notification \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'
```

## 8. 常见问题

### Q1: 如何保证消息不丢失？

系统采用"先持久化，后入队"策略：
1. 任务先写入数据库
2. 然后推送到Redis队列
3. Worker定时扫描数据库中的pending任务，确保队列丢失时可恢复

### Q2: 如何处理外部API长期不可用？

- 任务会按指数退避重试到最大次数
- 重试耗尽后标记为FAILED
- 可通过 `/v1/notifications/{taskId}/retry` 手动重试
- 建议配置告警监控FAILED状态任务数量

### Q3: 如何扩展Worker处理能力？

1. 增加单个Worker的并发数：修改 `notification.worker.concurrency`
2. 部署多个Worker实例：共享同一个数据库和Redis

### Q4: 如何测试不同的失败场景？

使用Mock API：
- `/mock/always-fail` - 测试持续失败
- `/mock/fail-then-success/3` - 测试重试成功
- `/mock/random` - 测试随机失败
- `/mock/timeout` - 测试超时处理

## 9. 版本历史

### v1.0.0
- 初始版本
- 支持基本的通知提交、查询、重试功能
- 支持Mock测试模式
- 支持Docker部署
