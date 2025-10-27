#!/bin/bash
set -e

echo "=== 测试临时工作流生命周期 ==="
echo ""

# 1. 登录获取 token
echo "[1] 登录 DolphinScheduler..."
TOKEN=$(curl -s -X POST "http://localhost:12345/dolphinscheduler/login" \
  -d "userName=admin&userPassword=dolphinscheduler123" | jq -r '.data.sessionId')
echo "Token: ${TOKEN:0:20}..."

# 2. 获取项目代码
echo ""
echo "[2] 获取项目代码..."
PROJECT_CODE=$(curl -s "http://localhost:12345/dolphinscheduler/projects/list?token=$TOKEN&pageSize=100" \
  | jq -r '.data.totalList[] | select(.name=="opendataworks") | .code')
echo "Project Code: $PROJECT_CODE"

# 3. 查看初始工作流列表
echo ""
echo "[3] 查看初始工作流列表..."
INITIAL_WORKFLOWS=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN" \
  | jq '.data.totalList')
INITIAL_COUNT=$(echo "$INITIAL_WORKFLOWS" | jq 'length')
echo "初始工作流数量: $INITIAL_COUNT"
echo "工作流列表:"
echo "$INITIAL_WORKFLOWS" | jq -r '.[] | "  - \(.name) (code: \(.code))"'

# 4. 检查是否有测试任务
echo ""
echo "[4] 查找可执行的测试任务..."
BACKEND_URL="http://localhost:8080"

# 获取任务列表
TASKS=$(curl -s "$BACKEND_URL/api/tasks?pageNum=1&pageSize=10" | jq '.records')
TASK_COUNT=$(echo "$TASKS" | jq 'length')
echo "找到 $TASK_COUNT 个任务"

if [ "$TASK_COUNT" -eq "0" ]; then
    echo "没有找到任务，创建一个测试任务..."
    # 创建测试任务
    TEST_TASK=$(curl -s -X POST "$BACKEND_URL/api/tasks" \
      -H "Content-Type: application/json" \
      -d '{
        "taskName": "临时工作流测试任务",
        "taskCode": "test-workflow-lifecycle-'$(date +%s)'",
        "taskType": "batch",
        "engine": "dolphin",
        "taskSql": "echo '\''Testing workflow lifecycle'\''",
        "priority": 5
      }' | jq '.')
    TASK_ID=$(echo "$TEST_TASK" | jq -r '.id')
    echo "创建的任务 ID: $TASK_ID"
else
    # 使用第一个任务
    TASK_ID=$(echo "$TASKS" | jq -r '.[0].id')
    TASK_NAME=$(echo "$TASKS" | jq -r '.[0].taskName')
    echo "使用现有任务: $TASK_NAME (ID: $TASK_ID)"
fi

# 5. 执行任务
echo ""
echo "[5] 执行任务 (ID: $TASK_ID)..."
EXEC_RESULT=$(curl -s -X POST "$BACKEND_URL/api/tasks/$TASK_ID/execute" | jq '.')
echo "执行结果: $EXEC_RESULT"

# 6. 等待工作流创建
echo ""
echo "[6] 等待工作流创建 (3秒)..."
sleep 3

# 7. 检查新工作流
echo ""
echo "[7] 检查是否创建了临时工作流..."
AFTER_WORKFLOWS=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN" \
  | jq '.data.totalList')
AFTER_COUNT=$(echo "$AFTER_WORKFLOWS" | jq 'length')
echo "执行后工作流数量: $AFTER_COUNT"

# 查找临时工作流
TEMP_WORKFLOW=$(echo "$AFTER_WORKFLOWS" | jq -r '.[] | select(.name | startswith("test-task-"))')
if [ -n "$TEMP_WORKFLOW" ]; then
    TEMP_NAME=$(echo "$TEMP_WORKFLOW" | jq -r '.name')
    TEMP_CODE=$(echo "$TEMP_WORKFLOW" | jq -r '.code')
    echo "✓ 找到临时工作流: $TEMP_NAME (code: $TEMP_CODE)"

    # 8. 测试删除功能
    echo ""
    echo "[8] 测试删除临时工作流..."
    DELETE_RESULT=$(curl -s -X POST "http://localhost:8081/api/v1/workflows/$TEMP_CODE/delete" \
      -H "Content-Type: application/json" \
      -d "{\"projectName\": \"opendataworks\"}" | jq '.')
    echo "删除结果: $DELETE_RESULT"

    # 9. 等待删除完成
    echo ""
    echo "[9] 等待删除完成 (3秒)..."
    sleep 3

    # 10. 验证删除
    echo ""
    echo "[10] 验证工作流已删除..."
    FINAL_WORKFLOWS=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN" \
      | jq '.data.totalList')
    FINAL_COUNT=$(echo "$FINAL_WORKFLOWS" | jq 'length')
    echo "最终工作流数量: $FINAL_COUNT"

    STILL_EXISTS=$(echo "$FINAL_WORKFLOWS" | jq -r ".[] | select(.code == $TEMP_CODE)")
    if [ -z "$STILL_EXISTS" ]; then
        echo "✓ 工作流已成功删除！"
        echo ""
        echo "=== 测试结果: 成功 ✓ ==="
        echo "- 初始工作流: $INITIAL_COUNT"
        echo "- 执行后: $AFTER_COUNT (新增 $((AFTER_COUNT - INITIAL_COUNT)))"
        echo "- 删除后: $FINAL_COUNT"
        echo "- 临时工作流已正确创建和删除"
    else
        echo "✗ 工作流仍然存在！"
        echo ""
        echo "=== 测试结果: 删除失败 ✗ ==="
    fi
else
    echo "✗ 未找到临时工作流"
    echo "所有工作流:"
    echo "$AFTER_WORKFLOWS" | jq -r '.[] | "  - \(.name) (code: \(.code))"'
    echo ""
    echo "=== 测试结果: 未创建临时工作流 ✗ ==="
fi
