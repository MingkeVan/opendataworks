#!/bin/bash
set -e

# 集成测试运行脚本
# 该脚本会:
# 1. 检查DolphinScheduler是否运行
# 2. 启动Python服务(如果未运行)
# 3. 运行集成测试
# 4. 清理

echo "=================================================="
echo "集成测试: Java -> Python Service -> DolphinScheduler"
echo "=================================================="

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 配置
PYTHON_SERVICE_DIR="./dolphinscheduler-service"
PYTHON_SERVICE_URL="http://localhost:8081"
PYTHON_SERVICE_PID_FILE="/tmp/dolphinscheduler-service.pid"

# 检查DolphinScheduler是否运行
check_dolphinscheduler() {
    echo -e "\n${YELLOW}检查DolphinScheduler...${NC}"

    # 检查Java Gateway端口
    if nc -z 127.0.0.1 25333 2>/dev/null; then
        echo -e "${GREEN}✓ DolphinScheduler Java Gateway正在运行 (端口25333)${NC}"
        return 0
    else
        echo -e "${RED}✗ DolphinScheduler Java Gateway未运行${NC}"
        echo -e "${YELLOW}请先启动DolphinScheduler服务${NC}"
        return 1
    fi
}

# 检查Python依赖
check_python_deps() {
    echo -e "\n${YELLOW}检查Python依赖...${NC}"

    if [ ! -d "$PYTHON_SERVICE_DIR/venv" ]; then
        echo -e "${YELLOW}创建Python虚拟环境...${NC}"
        cd "$PYTHON_SERVICE_DIR"
        python3 -m venv venv
        source venv/bin/activate
        pip install -r requirements.txt
        cd ..
        echo -e "${GREEN}✓ Python依赖安装完成${NC}"
    else
        echo -e "${GREEN}✓ Python虚拟环境已存在${NC}"
    fi
}

# 启动Python服务
start_python_service() {
    echo -e "\n${YELLOW}检查Python服务...${NC}"

    # 检查服务是否已运行
    if curl -s "$PYTHON_SERVICE_URL/health" >/dev/null 2>&1; then
        echo -e "${GREEN}✓ Python服务已运行${NC}"
        return 0
    fi

    echo -e "${YELLOW}启动Python服务...${NC}"

    # 复制环境配置
    if [ ! -f "$PYTHON_SERVICE_DIR/.env" ]; then
        if [ -f "$PYTHON_SERVICE_DIR/.env.example" ]; then
            cp "$PYTHON_SERVICE_DIR/.env.example" "$PYTHON_SERVICE_DIR/.env"
            echo -e "${GREEN}✓ 已创建.env配置文件${NC}"
        fi
    fi

    # 启动服务
    cd "$PYTHON_SERVICE_DIR"
    source venv/bin/activate
    nohup uvicorn dolphinscheduler_service.main:app --host 0.0.0.0 --port 8081 > /tmp/dolphinscheduler-service.log 2>&1 &
    SERVICE_PID=$!
    echo $SERVICE_PID > "$PYTHON_SERVICE_PID_FILE"
    cd ..

    # 等待服务启动
    echo -e "${YELLOW}等待服务启动...${NC}"
    for i in {1..30}; do
        if curl -s "$PYTHON_SERVICE_URL/health" >/dev/null 2>&1; then
            echo -e "${GREEN}✓ Python服务启动成功 (PID: $SERVICE_PID)${NC}"
            return 0
        fi
        sleep 1
    done

    echo -e "${RED}✗ Python服务启动失败${NC}"
    cat /tmp/dolphinscheduler-service.log
    return 1
}

# 运行集成测试
run_integration_test() {
    echo -e "\n${YELLOW}运行集成测试...${NC}"

    if [ ! -f "integration-test.py" ]; then
        echo -e "${RED}✗ 找不到integration-test.py${NC}"
        return 1
    fi

    # 检查是否安装了requests库
    if ! python3 -c "import requests" 2>/dev/null; then
        echo -e "${YELLOW}安装requests库...${NC}"
        pip3 install requests
    fi

    python3 integration-test.py --service-url "$PYTHON_SERVICE_URL"
    TEST_RESULT=$?

    if [ $TEST_RESULT -eq 0 ]; then
        echo -e "\n${GREEN}✓ 集成测试通过${NC}"
    else
        echo -e "\n${RED}✗ 集成测试失败${NC}"
    fi

    return $TEST_RESULT
}

# 清理函数
cleanup() {
    echo -e "\n${YELLOW}清理资源...${NC}"

    # 询问是否停止Python服务
    read -p "是否停止Python服务? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        if [ -f "$PYTHON_SERVICE_PID_FILE" ]; then
            PID=$(cat "$PYTHON_SERVICE_PID_FILE")
            if ps -p $PID > /dev/null 2>&1; then
                kill $PID
                rm "$PYTHON_SERVICE_PID_FILE"
                echo -e "${GREEN}✓ Python服务已停止${NC}"
            fi
        fi
    else
        echo -e "${YELLOW}Python服务保持运行${NC}"
    fi
}

# 主流程
main() {
    # 检查DolphinScheduler
    if ! check_dolphinscheduler; then
        echo -e "\n${RED}测试终止: 请先启动DolphinScheduler${NC}"
        exit 1
    fi

    # 检查Python依赖
    check_python_deps

    # 启动Python服务
    if ! start_python_service; then
        echo -e "\n${RED}测试终止: Python服务启动失败${NC}"
        exit 1
    fi

    # 运行测试
    run_integration_test
    TEST_RESULT=$?

    # 清理
    cleanup

    exit $TEST_RESULT
}

# 捕获中断信号
trap cleanup EXIT

# 运行主流程
main
