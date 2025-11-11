#!/bin/bash

# 工作流集成测试快速运行脚本
# 用于验证工作流生命周期管理的完整场景

set -e  # 遇到错误立即退出

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  工作流集成测试运行器${NC}"
echo -e "${BLUE}========================================${NC}\n"

# 1. 检查前置条件
echo -e "${YELLOW}[1/6] 检查前置条件...${NC}"

# 检查 MySQL
if ! command -v mysql &> /dev/null; then
    echo -e "${RED}✗ MySQL 未安装${NC}"
    exit 1
fi
echo -e "${GREEN}✓ MySQL 已安装${NC}"

# 检查 Maven
if ! command -v mvn &> /dev/null && [ ! -f "./mvnw" ]; then
    echo -e "${RED}✗ Maven 未安装${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Maven 已安装${NC}"

# 检查 Python 服务
if ! curl -s http://localhost:5001/health > /dev/null 2>&1; then
    echo -e "${RED}✗ dolphinscheduler-service 未运行 (http://localhost:5001)${NC}"
    echo -e "${YELLOW}  请先启动 Python 服务：${NC}"
    echo -e "${YELLOW}  cd dolphinscheduler-service && source venv/bin/activate && python -m dolphinscheduler_service.app${NC}"
    exit 1
fi
echo -e "${GREEN}✓ dolphinscheduler-service 运行中${NC}"

# 检查后端服务
if ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo -e "${YELLOW}⚠ 后端服务未运行 (http://localhost:8080)${NC}"
    echo -e "${YELLOW}  测试将启动后端服务...${NC}"
else
    echo -e "${GREEN}✓ 后端服务运行中${NC}"
fi

# 2. 创建 Doris 测试数据库和表
echo -e "\n${YELLOW}[2/6] 创建 Doris 测试数据库和表...${NC}"

DORIS_SQL="
CREATE DATABASE IF NOT EXISTS test_db;
USE test_db;

DROP TABLE IF EXISTS test_table_a;
CREATE TABLE test_table_a (
    id INT,
    name VARCHAR(100),
    value DECIMAL(10,2),
    created_time DATETIME
) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1;

DROP TABLE IF EXISTS test_table_b;
CREATE TABLE test_table_b (
    id INT,
    name VARCHAR(100),
    value DECIMAL(10,2),
    created_time DATETIME
) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1;

DROP TABLE IF EXISTS test_table_c;
CREATE TABLE test_table_c (
    id INT,
    name VARCHAR(100),
    value DECIMAL(10,2),
    created_time DATETIME
) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1;

INSERT INTO test_table_a VALUES
(1, 'Alice', 100.50, '2025-10-18 10:00:00'),
(2, 'Bob', 200.75, '2025-10-18 11:00:00'),
(3, 'Charlie', 150.25, '2025-10-18 12:00:00');
"

if mysql -h localhost -P 9030 -u root -e "$DORIS_SQL" 2>/dev/null; then
    echo -e "${GREEN}✓ Doris 测试表创建成功${NC}"
else
    echo -e "${YELLOW}⚠ 无法连接到 Doris (localhost:9030)${NC}"
    echo -e "${YELLOW}  如果您没有安装 Doris，测试的 SQL 部分将失败${NC}"
    echo -e "${YELLOW}  但测试的其他部分仍会继续运行${NC}"
fi

# 3. 清理旧的测试数据
echo -e "\n${YELLOW}[3/6] 清理旧的测试数据...${NC}"

CLEANUP_SQL="
DELETE FROM data_lineage WHERE task_id IN (
    SELECT id FROM data_task WHERE task_code LIKE 'test_task_%'
);
DELETE FROM data_task WHERE task_code LIKE 'test_task_%';
DELETE FROM data_table WHERE table_name LIKE 'test_table_%';
"

# 从配置文件读取数据库连接信息
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-3306}
DB_NAME=${DB_NAME:-opendataworks}
DB_USER=${DB_USER:-opendataworks}
DB_PASS=${DB_PASS:-opendataworks123}

if [ -n "$DB_PASS" ]; then
    mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME -e "$CLEANUP_SQL" 2>/dev/null || true
else
    mysql -h $DB_HOST -P $DB_PORT -u $DB_USER $DB_NAME -e "$CLEANUP_SQL" 2>/dev/null || true
fi
echo -e "${GREEN}✓ 旧测试数据已清理${NC}"

# 4. 检查 DolphinScheduler 数据源
echo -e "\n${YELLOW}[4/6] 检查 DolphinScheduler 数据源...${NC}"
echo -e "${YELLOW}  请确保已在 DolphinScheduler UI 中创建名为 'doris_test' 的数据源${NC}"
echo -e "${YELLOW}  访问: http://localhost:12345/dolphinscheduler${NC}"
echo -e "${YELLOW}  数据源类型: MYSQL, 主机: localhost, 端口: 9030${NC}"

read -p "$(echo -e ${GREEN}已创建 doris_test 数据源？[y/N]${NC}) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${RED}✗ 请先创建数据源再运行测试${NC}"
    exit 1
fi

# 5. 运行集成测试
echo -e "\n${YELLOW}[5/6] 运行集成测试...${NC}\n"

cd "$REPO_ROOT/backend"

# 使用 Maven Wrapper 或 Maven
if [ -f "./mvnw" ]; then
    MVN_CMD="./mvnw"
else
    MVN_CMD="mvn"
fi

# 运行测试
$MVN_CMD test -Dtest=WorkflowLifecycleIntegrationTest

TEST_RESULT=$?

# 6. 显示测试结果
echo -e "\n${YELLOW}[6/6] 测试结果${NC}\n"

if [ $TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  ✓ 所有测试通过！${NC}"
    echo -e "${GREEN}========================================${NC}\n"

    echo -e "${BLUE}测试覆盖的场景：${NC}"
    echo -e "  ✓ 创建3个表（table_a, table_b, table_c）"
    echo -e "  ✓ 创建3个串行任务（task_1, task_2, task_3）"
    echo -e "  ✓ 发布工作流并上线（ONLINE状态）"
    echo -e "  ✓ 下线工作流（OFFLINE状态）"
    echo -e "  ✓ 添加第4个任务（task_4）"
    echo -e "  ✓ 重新发布并上线工作流"
    echo -e "  ✓ 验证血缘关系和依赖"
    echo -e "  ✓ 清理测试数据\n"

    echo -e "${BLUE}验证方式：${NC}"
    echo -e "  1. 登录 DolphinScheduler UI: ${YELLOW}http://localhost:12345/dolphinscheduler${NC}"
    echo -e "  2. 查看工作流定义 -> 确认最新创建的工作流"
    echo -e "  3. 查看 DAG 图，应该有4个任务节点和依赖关系\n"

else
    echo -e "${RED}========================================${NC}"
    echo -e "${RED}  ✗ 测试失败${NC}"
    echo -e "${RED}========================================${NC}\n"

    echo -e "${YELLOW}故障排查：${NC}"
    echo -e "  1. 检查日志文件：backend/target/surefire-reports/"
    echo -e "  2. 检查 dolphinscheduler-service 日志"
    echo -e "  3. 检查 DolphinScheduler 是否正常运行"
    echo -e "  4. 查看详细文档：docs/workflow-integration-test-guide.md\n"
fi

exit $TEST_RESULT
