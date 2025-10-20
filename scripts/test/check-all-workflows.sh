#!/bin/bash

TOKEN="08739dcd-dc6e-45f9-b7e3-8d3dc9aa2c5a"

echo "=== 查询所有项目 ==="
PROJECTS=$(curl -s "http://localhost:12345/dolphinscheduler/projects/list?token=$TOKEN&pageSize=100")
echo "$PROJECTS" | jq '.data.totalList[] | {name: .name, code: .code}'

echo ""
echo "=== 查询每个项目的工作流 ==="
PROJECT_CODES=$(echo "$PROJECTS" | jq -r '.data.totalList[] | .code')

for PROJECT_CODE in $PROJECT_CODES; do
    PROJECT_NAME=$(echo "$PROJECTS" | jq -r ".data.totalList[] | select(.code == $PROJECT_CODE) | .name")
    echo ""
    echo "项目: $PROJECT_NAME (code: $PROJECT_CODE)"
    
    WORKFLOWS=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN")
    WORKFLOW_COUNT=$(echo "$WORKFLOWS" | jq '.data.totalList | length')
    echo "工作流数量: $WORKFLOW_COUNT"
    
    if [ "$WORKFLOW_COUNT" -gt "0" ]; then
        echo "$WORKFLOWS" | jq '.data.totalList[] | {name: .name, code: .code, releaseState: .releaseState}'
    fi
done

echo ""
echo "=== 搜索临时工作流 (code: 19386043855840) ==="
for PROJECT_CODE in $PROJECT_CODES; do
    FOUND=$(curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN" | \
            jq '.data.totalList[] | select(.code == 19386043855840)')
    
    if [ -n "$FOUND" ]; then
        PROJECT_NAME=$(echo "$PROJECTS" | jq -r ".data.totalList[] | select(.code == $PROJECT_CODE) | .name")
        echo "✓ 在项目 '$PROJECT_NAME' 中找到:"
        echo "$FOUND" | jq '.'
        exit 0
    fi
done

echo "✗ 在所有项目中都未找到该工作流"
