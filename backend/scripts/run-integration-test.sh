#!/bin/bash

# DolphinScheduler集成测试执行脚本

set -e

echo "========================================="
echo "  DolphinScheduler 集成测试"
echo "========================================="
echo ""

# 进入项目目录
cd "$(dirname "$0")/.."

echo "当前目录: $(pwd)"
echo ""

echo "1. 检查配置文件..."
if [ ! -f "src/test/resources/application-test.yml" ]; then
    echo "✗ 测试配置文件不存在"
    exit 1
fi
echo "✓ 测试配置文件存在"
echo ""

echo "2. 编译项目..."
mvn clean compile test-compile -DskipTests
echo "✓ 编译完成"
echo ""

echo "3. 运行集成测试..."
echo "========================================="
mvn test \
    -Dtest=DolphinSchedulerClientIntegrationTest \
    -Dspring.profiles.active=test \
    -Dlogging.level.com.onedata.portal=DEBUG \
    -Dmaven.test.failure.ignore=false

TEST_RESULT=$?

echo ""
echo "========================================="
if [ $TEST_RESULT -eq 0 ]; then
    echo "✓ 所有测试通过！"
    echo ""
    echo "下一步："
    echo "1. 查看测试日志确认所有操作成功"
    echo "2. 通过DolphinScheduler UI验证工作流状态"
    echo "3. 继续实现DolphinWorkflowService"
else
    echo "✗ 测试失败"
    echo ""
    echo "排查步骤："
    echo "1. 查看上方错误日志"
    echo "2. 检查DolphinScheduler服务是否正常"
    echo "3. 验证配置文件中的project-code和workflow-code"
    echo "4. 查看详细文档: INTEGRATION_TEST_README.md"
fi
echo "========================================="

exit $TEST_RESULT
