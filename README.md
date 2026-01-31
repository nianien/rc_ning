# API通知系统 (Notification Service)

可靠的HTTP通知投递服务，接收企业内部业务系统的外部API通知请求，确保稳定可靠地送达目标地址。

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+

### 一键启动（Mock模式）

```bash
# 编译并启动
./run.sh build
./run.sh start

# 访问服务
# API文档: http://localhost:8080/swagger-ui.html
# 健康检查: http://localhost:8080/v1/health
```

### 测试API

```bash
# 运行测试脚本
./test-api.sh

# 或手动测试
curl -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "test",
    "targetUrl": "http://localhost:8080/mock/always-success",
    "body": {"event": "test"}
  }'
```

## 项目结构

```
rc_ning/
├── notification-common/      # 共享模块（实体、DTO、Service）
├── notification-api/         # API服务（微服务模式）
├── notification-worker/      # Worker服务（微服务模式）
├── notification-standalone/  # 单体应用（推荐本地测试）
├── tech_design.md           # 技术设计文档
├── USER_MANUAL.md           # 使用手册
├── run.sh                   # 启动脚本
└── test-api.sh              # 测试脚本
```

## 核心功能

| 功能 | 说明 |
|------|------|
| 异步投递 | 接收请求立即返回，后台异步处理 |
| 可靠投递 | At-Least-Once语义，确保消息送达 |
| 自动重试 | 指数退避策略（1s, 2s, 4s, 8s, 16s） |
| 状态查询 | 支持查询任务状态和投递日志 |
| 手动重试 | 支持对失败任务手动触发重试 |

## API接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /v1/notifications | 提交通知请求 |
| GET | /v1/notifications/{taskId} | 查询任务状态 |
| GET | /v1/notifications/{taskId}/logs | 查询投递日志 |
| POST | /v1/notifications/{taskId}/retry | 手动重试 |
| GET | /v1/health | 健康检查 |
| GET | /v1/stats | 统计信息 |

## 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.2 | 现代Java微服务框架 |
| 数据库 | H2/PostgreSQL | Mock模式用H2，生产用PostgreSQL |
| 队列 | Redis | 基于Redis List实现简单队列 |
| API文档 | SpringDoc OpenAPI | Swagger UI |

## 设计决策

详见 [tech_design.md](tech_design.md)

### 系统边界

**解决的问题：**
- 异步解耦：业务系统不被外部API阻塞
- 可靠投递：At-Least-Once语义
- 失败重试：指数退避，最多5次
- 可观测性：状态查询、投递日志

**不解决的问题：**
- 幂等性：由外部API自行保证
- 事务性：不支持多通知原子投递
- 实时性：不承诺毫秒级延迟

### 重试策略

```
失败 → 1秒后重试 → 2秒后重试 → 4秒后重试 → 8秒后重试 → 16秒后重试 → 最终失败
```

## 详细文档

- [技术设计文档](tech_design.md) - 架构设计、技术选型、取舍说明
- [使用手册](USER_MANUAL.md) - 详细的API说明和部署指南

## 许可证

MIT License
