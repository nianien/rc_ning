#!/bin/bash

# API通知服务启动脚本
# 使用方法: ./run.sh [命令]
# 命令:
#   build   - 编译项目
#   start   - 启动单体应用（推荐用于本地测试）
#   start-ms - 启动微服务模式（API + Worker分离）
#   test    - 运行测试用例
#   clean   - 清理编译产物

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_banner() {
    echo "============================================================"
    echo " API通知服务 - Notification Service"
    echo "============================================================"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java未安装，请安装JDK 17+"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        print_error "Java版本过低，需要JDK 17+，当前版本: $JAVA_VERSION"
        exit 1
    fi
    print_success "Java版本检查通过: $(java -version 2>&1 | head -1)"
}

check_maven() {
    if ! command -v mvn &> /dev/null; then
        print_error "Maven未安装，请安装Maven 3.8+"
        exit 1
    fi
    print_success "Maven检查通过: $(mvn -version | head -1)"
}

build() {
    print_banner
    echo "正在编译项目..."
    check_java
    check_maven

    mvn clean package -DskipTests -q
    print_success "编译完成！"
}

start_standalone() {
    print_banner
    echo "启动单体应用（Mock模式）..."

    JAR_FILE="notification-standalone/target/notification-standalone-1.0.0-SNAPSHOT.jar"

    if [ ! -f "$JAR_FILE" ]; then
        print_warning "JAR文件不存在，开始编译..."
        build
    fi

    echo ""
    echo "启动服务..."
    echo "- API文档: http://localhost:8080/swagger-ui.html"
    echo "- 健康检查: http://localhost:8080/v1/health"
    echo "- H2控制台: http://localhost:8080/h2-console"
    echo ""

    java -jar "$JAR_FILE"
}

start_microservices() {
    print_banner
    echo "启动微服务模式..."

    API_JAR="notification-api/target/notification-api-1.0.0-SNAPSHOT.jar"
    WORKER_JAR="notification-worker/target/notification-worker-1.0.0-SNAPSHOT.jar"

    if [ ! -f "$API_JAR" ] || [ ! -f "$WORKER_JAR" ]; then
        print_warning "JAR文件不存在，开始编译..."
        build
    fi

    echo ""
    echo "启动API服务（端口8080）..."
    java -jar "$API_JAR" &
    API_PID=$!

    sleep 5

    echo "启动Worker服务（端口8081）..."
    java -jar "$WORKER_JAR" &
    WORKER_PID=$!

    echo ""
    print_success "服务已启动"
    echo "- API服务 PID: $API_PID"
    echo "- Worker服务 PID: $WORKER_PID"
    echo ""
    echo "按 Ctrl+C 停止所有服务"

    trap "kill $API_PID $WORKER_PID 2>/dev/null; exit" SIGINT SIGTERM
    wait
}

run_tests() {
    print_banner
    echo "运行测试..."
    check_java
    check_maven

    mvn test
    print_success "测试完成！"
}

clean() {
    print_banner
    echo "清理编译产物..."

    mvn clean -q
    print_success "清理完成！"
}

show_help() {
    print_banner
    echo ""
    echo "使用方法: ./run.sh [命令]"
    echo ""
    echo "命令:"
    echo "  build      编译项目"
    echo "  start      启动单体应用（推荐，无需外部依赖）"
    echo "  start-ms   启动微服务模式（API + Worker分离）"
    echo "  test       运行测试用例"
    echo "  clean      清理编译产物"
    echo "  help       显示帮助信息"
    echo ""
    echo "示例:"
    echo "  ./run.sh build    # 编译项目"
    echo "  ./run.sh start    # 启动服务"
    echo ""
}

# 主逻辑
case "${1:-help}" in
    build)
        build
        ;;
    start)
        start_standalone
        ;;
    start-ms)
        start_microservices
        ;;
    test)
        run_tests
        ;;
    clean)
        clean
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        print_error "未知命令: $1"
        show_help
        exit 1
        ;;
esac
