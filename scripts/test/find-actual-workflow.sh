#!/bin/bash

TOKEN="08739dcd-dc6e-45f9-b7e3-8d3dc9aa2c5a"
ACTUAL_CODE="19385942554208"

echo "=== 搜索实际创建的工作流 (code: $ACTUAL_CODE) ==="

# Get all projects
PROJECTS=$(curl -s "http://localhost:12345/dolphinscheduler/projects/list?token=$TOKEN&pageSize=100")
PROJECT_CODES=$(echo "$PROJECTS" | jq -r '.data.totalList[]? | .code' 2>/dev/null)

if [ -z "$PROJECT_CODES" ]; then
    echo "⚠️  无法获取项目列表，尝试已知项目..."
    PROJECT_CODES="19385942554176"  # opendataworks project code from earlier
fi

for PROJECT_CODE in $PROJECT_CODES; do
    WORKFLOWS=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN")
    FOUND=$(echo "$WORKFLOWS" | jq ".data.totalList[]? | select(.code == $ACTUAL_CODE)" 2>/dev/null)
    
    if [ -n "$FOUND" ]; then
        PROJECT_NAME=$(echo "$PROJECTS" | jq -r ".data.totalList[]? | select(.code == $PROJECT_CODE) | .name" 2>/dev/null)
        echo "✓ 在项目 '$PROJECT_NAME' (code: $PROJECT_CODE) 中找到工作流:"
        echo "$FOUND" | jq '{name: .name, code: .code, releaseState: .releaseState, createTime: .createTime}'
        echo ""
        echo "=== 找到临时工作流! ==="
        exit 0
    fi
done

# Also check for workflows starting with "test-task-"
echo ""
echo "=== 搜索所有以 'test-task-' 开头的工作流 ==="
for PROJECT_CODE in $PROJECT_CODES; do
    WORKFLOWS=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN")
    TEMP_WORKFLOWS=$(echo "$WORKFLOWS" | jq '.data.totalList[]? | select(.name | startswith("test-task-"))' 2>/dev/null)
    
    if [ -n "$TEMP_WORKFLOWS" ]; then
        echo "在项目 code=$PROJECT_CODE 中找到:"
        echo "$TEMP_WORKFLOWS" | jq '{name: .name, code: .code, releaseState: .releaseState}'
    fi
done
