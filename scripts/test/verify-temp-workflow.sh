#!/bin/bash

TOKEN="08739dcd-dc6e-45f9-b7e3-8d3dc9aa2c5a"
PROJECT_CODE="19385942554176"
TEMP_WORKFLOW_CODE="19386043855840"

echo "=== 步骤 1: 验证临时工作流已创建 ==="
TEMP_WF=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN" | jq '.data.totalList[] | select(.code == '$TEMP_WORKFLOW_CODE')')

if [ -n "$TEMP_WF" ]; then
    echo "✓ 临时工作流已创建:"
    echo "$TEMP_WF" | jq '{name: .name, code: .code, releaseState: .releaseState}'
else
    echo "✗ 未找到临时工作流 code=$TEMP_WORKFLOW_CODE"
    exit 1
fi

echo ""
echo "=== 步骤 2: 调用删除 API ==="
DELETE_RESULT=$(curl -s -X POST "http://localhost:8081/api/v1/workflows/$TEMP_WORKFLOW_CODE/delete" \
  -H "Content-Type: application/json" \
  -d '{"projectName": "opendataworks"}')
echo "删除结果: $DELETE_RESULT"

echo ""
echo "=== 步骤 3: 等待删除完成 (3秒) ==="
sleep 3

echo ""
echo "=== 步骤 4: 验证工作流已删除 ==="
AFTER_DELETE=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN" | jq '.data.totalList[] | select(.code == '$TEMP_WORKFLOW_CODE')')

if [ -z "$AFTER_DELETE" ]; then
    echo "✓ 临时工作流已成功删除！"
    echo ""
    echo "=== 测试结果: 成功 ✓ ==="
    echo "临时工作流的创建和删除功能正常工作！"
else
    echo "✗ 临时工作流仍然存在"
    echo "$AFTER_DELETE" | jq '.'
    echo ""
    echo "=== 测试结果: 删除失败 ✗ ==="
    exit 1
fi
