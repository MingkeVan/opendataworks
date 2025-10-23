#!/bin/bash

# 创建测试脚本目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TARGET_DIR="$REPO_ROOT/scripts/test"

mkdir -p "$TARGET_DIR"

# 移动测试脚本
FILES=(
    "check-all-workflows.sh"
    "find-actual-workflow.sh"
    "test-delete-actual-workflow.sh"
    "test-workflow-lifecycle.sh"
    "verify-deletion.sh"
    "verify-temp-workflow.sh"
)

for file in "${FILES[@]}"; do
    SRC="$REPO_ROOT/$file"
    if [ -f "$SRC" ]; then
        mv "$SRC" "$TARGET_DIR/"
        echo "已移动: $file"
    fi
done

echo "测试脚本已归档到 scripts/test/ 目录"
ls -la "$TARGET_DIR"
