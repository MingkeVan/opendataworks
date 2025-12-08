#!/bin/bash

# 工作流集成测试运行脚本
# 自动清理旧数据并运行测试

set -e

# 获取脚本所在目录的父目录（项目根目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/backend"

echo "================================================"
echo "   工作流集成测试"
echo "================================================"

# 清理旧的测试数据
echo -e "\n🧹 清理旧测试数据..."
mysql -h 127.0.0.1 -P 3306 -u opendataworks -popendataworks123 opendataworks -e "
DELETE FROM data_lineage WHERE task_id IN (SELECT id FROM data_task WHERE task_code LIKE 'test_task_%' OR task_code LIKE 'sample_%');
DELETE FROM data_task WHERE task_code LIKE 'test_task_%' OR task_code LIKE 'sample_%';
DELETE FROM data_table WHERE table_name LIKE 'test_table_%';
" 2>&1 | grep -v "mysql: \[Warning\]" || true

echo "✅ 旧数据已清理"

# 运行测试
echo -e "\n🧪 运行测试...\n"
cd "$BACKEND_DIR"
mvn test -Dtest=WorkflowLifecycleFullTest

echo -e "\n================================================"
echo "   测试完成！"
echo "================================================"
