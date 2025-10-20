#!/bin/bash

# 创建测试脚本目录
mkdir -p scripts/test

# 移动测试脚本
mv check-all-workflows.sh scripts/test/
mv find-actual-workflow.sh scripts/test/
mv test-delete-actual-workflow.sh scripts/test/
mv test-workflow-lifecycle.sh scripts/test/
mv verify-deletion.sh scripts/test/
mv verify-temp-workflow.sh scripts/test/

echo "测试脚本已移动到 scripts/test/ 目录"
ls -la scripts/test/
