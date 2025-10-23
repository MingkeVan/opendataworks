#!/bin/bash

#############################################
# OneData Works - 快速部署验证脚本
# 用于在新环境中快速部署和验证系统
#############################################

set -e  # 遇到错误立即退出

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查命令是否存在
check_command() {
    if ! command -v $1 &> /dev/null; then
        log_error "$1 未安装，请先安装"
        return 1
    else
        log_success "$1 已安装"
        return 0
    fi
}

# 打印横幅
print_banner() {
    echo ""
    echo "=========================================="
    echo "  OneData Works - 快速部署验证工具"
    echo "=========================================="
    echo ""
}

# 检查环境依赖
check_dependencies() {
    log_info "检查环境依赖..."

    local all_ok=true

    # 必需的工具
    check_command "java" || all_ok=false
    check_command "python3" || all_ok=false
    check_command "node" || all_ok=false
    check_command "npm" || all_ok=false
    check_command "docker" || all_ok=false
    check_command "curl" || all_ok=false
    check_command "jq" || all_ok=false

    if [ "$all_ok" = false ]; then
        log_error "缺少必需的依赖，请安装后重试"
        exit 1
    fi

    log_success "所有依赖检查通过"
    echo ""
}

# 检查 DolphinScheduler
check_dolphinscheduler() {
    log_info "检查 DolphinScheduler..."

    if docker ps | grep -q dolphinscheduler; then
        log_success "DolphinScheduler 容器正在运行"

        # 测试 API 连接
        if curl -sf http://localhost:12345/dolphinscheduler/ui > /dev/null; then
            log_success "DolphinScheduler Web UI 可访问"
        else
            log_warning "DolphinScheduler Web UI 无法访问"
        fi
    else
        log_error "DolphinScheduler 容器未运行"
        log_info "请先启动 DolphinScheduler:"
        echo "  docker-compose up -d"
        exit 1
    fi
    echo ""
}

# 部署后端服务
deploy_backend() {
    log_info "部署后端服务..."

    pushd "$REPO_ROOT/backend" >/dev/null

    # 检查是否已编译
    if [ ! -d "build" ]; then
        log_info "首次部署，开始编译..."
        ./gradlew build
    fi

    # 检查端口占用
    if lsof -ti:8080 > /dev/null 2>&1; then
        log_warning "端口 8080 已被占用，停止旧进程..."
        pkill -f "DataPortalApplication" || true
        sleep 2
    fi

    # 启动服务
    log_info "启动后端服务..."
    ./gradlew bootRun > /tmp/backend.log 2>&1 &
    BACKEND_PID=$!

    # 等待服务启动
    log_info "等待后端服务启动..."
    for i in {1..30}; do
        if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1 || \
           curl -sf http://localhost:8080/api/health > /dev/null 2>&1 || \
           grep -q "Started DataPortalApplication" /tmp/backend.log 2>/dev/null; then
            log_success "后端服务启动成功 (PID: $BACKEND_PID)"
            popd >/dev/null
            echo ""
            return 0
        fi
        sleep 1
    done

    log_error "后端服务启动超时"
    log_info "查看日志: tail -f /tmp/backend.log"
    popd >/dev/null
    exit 1
}

# 部署 Python 服务
deploy_python_service() {
    log_info "部署 Python DolphinScheduler 服务..."

    pushd "$REPO_ROOT/dolphinscheduler-service" >/dev/null

    # 检查 Python 环境
    if [ ! -d "venv" ]; then
        log_info "创建 Python 虚拟环境..."
        python3 -m venv venv
    fi

    # 激活虚拟环境
    source venv/bin/activate

    # 安装依赖
    log_info "安装 Python 依赖..."
    pip install -q -r requirements.txt || pip install -r requirements.txt

    # 检查端口占用
    if lsof -ti:5001 > /dev/null 2>&1; then
        log_warning "端口 5001 已被占用，停止旧进程..."
        pkill -f "uvicorn.*dolphinscheduler_service" || true
        sleep 2
    fi

    # 启动服务
    log_info "启动 Python 服务..."
    python -m uvicorn dolphinscheduler_service.main:app \
        --host 0.0.0.0 --port 5001 \
        > /tmp/dolphin-service.log 2>&1 &
    PYTHON_PID=$!

    # 等待服务启动
    log_info "等待 Python 服务启动..."
    for i in {1..20}; do
        if curl -sf http://localhost:5001/health > /dev/null 2>&1; then
            log_success "Python 服务启动成功 (PID: $PYTHON_PID)"
            popd >/dev/null
            echo ""
            return 0
        fi
        sleep 1
    done

    log_error "Python 服务启动超时"
    log_info "查看日志: tail -f /tmp/dolphin-service.log"
    popd >/dev/null
    exit 1
}

# 部署前端（可选）
deploy_frontend() {
    if [ "$1" = "--skip-frontend" ]; then
        log_warning "跳过前端部署"
        echo ""
        return 0
    fi

    log_info "部署前端服务..."

    pushd "$REPO_ROOT/frontend" >/dev/null

    # 安装依赖
    if [ ! -d "node_modules" ]; then
        log_info "安装前端依赖..."
        npm install
    fi

    # 检查端口占用
    if lsof -ti:3000 > /dev/null 2>&1; then
        log_warning "端口 3000 已被占用，停止旧进程..."
        pkill -f "vite.*3000" || pkill -f "vue.*3000" || true
        sleep 2
    fi

    # 启动服务
    log_info "启动前端开发服务器..."
    npm run dev > /tmp/frontend.log 2>&1 &
    FRONTEND_PID=$!

    # 等待服务启动
    log_info "等待前端服务启动..."
    for i in {1..20}; do
        if curl -sf http://localhost:3000 > /dev/null 2>&1; then
            log_success "前端服务启动成功 (PID: $FRONTEND_PID)"
            popd >/dev/null
            echo ""
            return 0
        fi
        sleep 1
    done

    log_warning "前端服务启动超时（这是正常的，前端可能需要更长时间）"
    log_info "查看日志: tail -f /tmp/frontend.log"
    popd >/dev/null
    echo ""
}

# 运行验证测试
run_verification_tests() {
    log_info "运行验证测试..."

    # 1. 测试后端健康检查
    log_info "测试后端 API..."
    if curl -sf http://localhost:8080/api/tasks?pageNum=1&pageSize=10 > /dev/null; then
        log_success "后端 API 响应正常"
    else
        log_error "后端 API 测试失败"
        return 1
    fi

    # 2. 测试 Python 服务
    log_info "测试 Python DolphinScheduler 服务..."
    if curl -sf http://localhost:5001/health > /dev/null; then
        log_success "Python 服务响应正常"
    else
        log_error "Python 服务测试失败"
        return 1
    fi

    # 3. 测试 DolphinScheduler 连接
    log_info "测试 DolphinScheduler 连接..."
    TOKEN=$(curl -sf -X POST "http://localhost:12345/dolphinscheduler/login" \
        -d "userName=admin&userPassword=dolphinscheduler123" | jq -r '.data.sessionId' 2>/dev/null)

    if [ -n "$TOKEN" ] && [ "$TOKEN" != "null" ]; then
        log_success "DolphinScheduler 连接成功"
    else
        log_warning "DolphinScheduler 登录失败（可能需要手动配置）"
    fi

    echo ""
    log_success "所有验证测试通过！"
    echo ""
}

# 显示部署信息
show_deployment_info() {
    echo "=========================================="
    echo "  部署完成！"
    echo "=========================================="
    echo ""
    echo "服务访问地址:"
    echo "  - 前端:              http://localhost:3000"
    echo "  - 后端 API:          http://localhost:8080"
    echo "  - Python 服务:       http://localhost:5001"
    echo "  - DolphinScheduler:  http://localhost:12345/dolphinscheduler"
    echo ""
    echo "DolphinScheduler 登录:"
    echo "  - 用户名: admin"
    echo "  - 密码:   dolphinscheduler123"
    echo ""
    echo "日志文件:"
    echo "  - 后端:     tail -f /tmp/backend.log"
    echo "  - Python:   tail -f /tmp/dolphin-service.log"
    echo "  - 前端:     tail -f /tmp/frontend.log"
    echo ""
    echo "快速测试命令:"
    echo "  curl http://localhost:8080/api/tasks?pageNum=1&pageSize=10"
    echo ""
    echo "=========================================="
}

# 主函数
main() {
    print_banner

    # 解析参数
    SKIP_FRONTEND=false
    for arg in "$@"; do
        case $arg in
            --skip-frontend)
                SKIP_FRONTEND=true
                shift
                ;;
        esac
    done

    # 执行部署流程
    check_dependencies
    check_dolphinscheduler
    deploy_python_service
    deploy_backend

    if [ "$SKIP_FRONTEND" = false ]; then
        deploy_frontend
    else
        deploy_frontend --skip-frontend
    fi

    run_verification_tests
    show_deployment_info

    log_success "部署完成！系统已准备就绪。"
}

# 运行主函数
main "$@"
