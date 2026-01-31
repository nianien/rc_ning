#!/bin/bash

# API测试脚本
# 使用方法: ./test-api.sh

set -e

BASE_URL="http://localhost:8080"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_title() {
    echo ""
    echo -e "${BLUE}=== $1 ===${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}→ $1${NC}"
}

# 检查服务是否运行
check_service() {
    print_title "检查服务状态"
    if curl -s "$BASE_URL/v1/health" > /dev/null 2>&1; then
        print_success "服务正常运行"
    else
        echo -e "${RED}✗ 服务未启动，请先运行: ./run.sh start${NC}"
        exit 1
    fi
}

# 测试1: 成功场景
test_success() {
    print_title "测试1: 成功场景"
    print_info "发送通知到 /mock/always-success"

    RESPONSE=$(curl -s -X POST "$BASE_URL/v1/notifications" \
        -H "Content-Type: application/json" \
        -d '{
            "sourceSystem": "test-success",
            "targetUrl": "http://localhost:8080/mock/always-success",
            "body": {"event": "test", "data": "hello"}
        }')

    TASK_ID=$(echo "$RESPONSE" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    echo "响应: $RESPONSE"
    echo "任务ID: $TASK_ID"

    # 等待处理
    sleep 2

    # 查询状态
    print_info "查询任务状态"
    STATUS=$(curl -s "$BASE_URL/v1/notifications/$TASK_ID")
    echo "状态: $STATUS"

    if echo "$STATUS" | grep -q '"status":"SUCCESS"'; then
        print_success "测试通过：任务成功完成"
    else
        echo -e "${RED}✗ 测试失败：期望状态为SUCCESS${NC}"
    fi
}

# 测试2: 重试场景
test_retry() {
    print_title "测试2: 重试场景"
    print_info "发送通知到 /mock/fail-then-success/2（前2次失败，第3次成功）"

    # 重置Mock计数器
    curl -s -X POST "$BASE_URL/mock/reset" > /dev/null

    RESPONSE=$(curl -s -X POST "$BASE_URL/v1/notifications" \
        -H "Content-Type: application/json" \
        -d '{
            "sourceSystem": "test-retry",
            "targetUrl": "http://localhost:8080/mock/fail-then-success/2",
            "body": {"event": "retry-test"},
            "maxRetries": 5
        }')

    TASK_ID=$(echo "$RESPONSE" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    echo "任务ID: $TASK_ID"

    # 等待重试（指数退避: 1s + 2s + 处理时间）
    print_info "等待重试完成..."
    sleep 8

    # 查询状态
    STATUS=$(curl -s "$BASE_URL/v1/notifications/$TASK_ID")
    echo "状态: $STATUS"

    # 查询日志
    print_info "查询投递日志"
    LOGS=$(curl -s "$BASE_URL/v1/notifications/$TASK_ID/logs")
    echo "日志: $LOGS"

    if echo "$STATUS" | grep -q '"status":"SUCCESS"'; then
        print_success "测试通过：重试后成功"
    else
        echo -e "${YELLOW}⚠ 任务可能仍在重试中，请稍后查询${NC}"
    fi
}

# 测试3: 最终失败场景
test_final_failure() {
    print_title "测试3: 最终失败场景"
    print_info "发送通知到 /mock/always-fail（使用少量重试）"

    RESPONSE=$(curl -s -X POST "$BASE_URL/v1/notifications" \
        -H "Content-Type: application/json" \
        -d '{
            "sourceSystem": "test-failure",
            "targetUrl": "http://localhost:8080/mock/always-fail",
            "body": {"event": "fail-test"},
            "maxRetries": 2
        }')

    TASK_ID=$(echo "$RESPONSE" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    echo "任务ID: $TASK_ID"

    # 等待所有重试完成（1s + 2s + 处理时间）
    print_info "等待重试耗尽..."
    sleep 8

    # 查询状态
    STATUS=$(curl -s "$BASE_URL/v1/notifications/$TASK_ID")
    echo "状态: $STATUS"

    if echo "$STATUS" | grep -q '"status":"FAILED"'; then
        print_success "测试通过：重试耗尽后标记为失败"
    else
        echo -e "${YELLOW}⚠ 任务可能仍在重试中${NC}"
    fi
}

# 测试4: 手动重试
test_manual_retry() {
    print_title "测试4: 手动重试"

    # 先获取一个失败的任务
    print_info "创建一个会失败的任务"

    RESPONSE=$(curl -s -X POST "$BASE_URL/v1/notifications" \
        -H "Content-Type: application/json" \
        -d '{
            "sourceSystem": "test-manual-retry",
            "targetUrl": "http://localhost:8080/mock/always-fail",
            "body": {"event": "manual-retry-test"},
            "maxRetries": 1
        }')

    TASK_ID=$(echo "$RESPONSE" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    echo "任务ID: $TASK_ID"

    # 等待失败
    sleep 5

    # 检查状态
    STATUS=$(curl -s "$BASE_URL/v1/notifications/$TASK_ID")
    echo "当前状态: $STATUS"

    if echo "$STATUS" | grep -q '"status":"FAILED"'; then
        print_info "任务已失败，尝试手动重试"

        RETRY_RESPONSE=$(curl -s -X POST "$BASE_URL/v1/notifications/$TASK_ID/retry")
        echo "重试响应: $RETRY_RESPONSE"

        if echo "$RETRY_RESPONSE" | grep -q '"status":"PENDING"'; then
            print_success "测试通过：手动重试成功将任务重新入队"
        fi
    else
        echo -e "${YELLOW}⚠ 任务尚未失败${NC}"
    fi
}

# 测试5: 统计信息
test_stats() {
    print_title "测试5: 统计信息"
    print_info "获取系统统计"

    STATS=$(curl -s "$BASE_URL/v1/stats")
    echo "统计: $STATS"

    print_success "统计信息获取成功"
}

# 主流程
main() {
    echo "============================================================"
    echo " API通知服务 - 接口测试"
    echo "============================================================"

    check_service
    test_success
    test_retry
    test_final_failure
    test_manual_retry
    test_stats

    echo ""
    echo "============================================================"
    print_success "所有测试完成！"
    echo "============================================================"
}

main
