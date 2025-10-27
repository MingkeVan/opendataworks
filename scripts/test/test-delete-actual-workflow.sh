#!/bin/bash

ACTUAL_WORKFLOW_CODE="19385942554208"
TOKEN="08739dcd-dc6e-45f9-b7e3-8d3dc9aa2c5a"
PROJECT_CODE="19385942554176"

echo "=== 测试删除实际创建的工作流 ==="
echo "实际工作流 code: $ACTUAL_WORKFLOW_CODE"
echo ""

echo "[1] 验证工作流存在..."
WORKFLOW=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/$ACTUAL_WORKFLOW_CODE?token=$TOKEN")
echo "$WORKFLOW" | jq '{code: .data.code, name: .data.name, releaseState: .data.releaseState}'

echo ""
echo "[2] 调用删除 API..."
DELETE_RESULT=$(curl -s -X POST "http://localhost:8081/api/v1/workflows/$ACTUAL_WORKFLOW_CODE/delete" \
  -H "Content-Type: application/json" \
  -d '{"projectName": "opendataworks"}')
echo "删除结果: $DELETE_RESULT"

echo ""
echo "[3] 等待删除完成 (3秒)..."
sleep 3

echo ""
echo "[4] 验证工作流已删除..."
AFTER_DELETE=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/$ACTUAL_WORKFLOW_CODE?token=$TOKEN")
SUCCESS=$(echo "$AFTER_DELETE" | jq -r '.success')

if [ "$SUCCESS" == "false" ]; then
    echo "✓ 工作流已成功删除！"
    echo ""
    echo "=== 测试结果: 成功 ✓ ==="
else
    echo "✗ 工作流仍然存在"
    echo "$AFTER_DELETE" | jq '.'
    echo ""
    echo "=== 测试结果: 失败 ✗ ===" 
fi
