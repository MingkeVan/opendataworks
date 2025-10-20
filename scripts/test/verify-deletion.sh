#!/bin/bash

TOKEN="08739dcd-dc6e-45f9-b7e3-8d3dc9aa2c5a"
PROJECT_CODE="19385942554176"
WORKFLOW_CODE="19385942554208"

echo "=== 验证工作流已删除 ==="
echo ""
echo "查询工作流: code=$WORKFLOW_CODE"
RESULT=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/$WORKFLOW_CODE?token=$TOKEN")
echo "$RESULT" | jq '.'

SUCCESS=$(echo "$RESULT" | jq -r '.success')
if [ "$SUCCESS" == "false" ]; then
    echo ""
    echo "✓✓✓ 测试成功！工作流已从 DolphinScheduler 删除 ✓✓✓"
    exit 0
else
    echo ""
    echo "✗ 工作流仍然存在"
    exit 1
fi
