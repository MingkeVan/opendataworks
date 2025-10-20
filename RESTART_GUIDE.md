# 重启服务指南 - 应用 Workflow Code 修复

## 📋 概述

代码已修复完成，需要重启后端服务以应用更改。

## 🔧 修改的文件

- `backend/src/main/java/com/onedata/portal/service/DataTaskService.java`

## 🚀 重启步骤

### 方法 1: 停止并重启（推荐）

```bash
# 1. 停止当前运行的后端服务
pkill -f "DataPortalApplication"

# 2. 等待 2 秒确保进程完全停止
sleep 2

# 3. 重新编译并启动
cd /Users/guoruping/project/bigdata/onedata-works/backend
./gradlew bootRun > /tmp/backend.log 2>&1 &

# 4. 查看日志确认启动成功
tail -f /tmp/backend.log
```

### 方法 2: 使用 Gradle 重新编译（如果使用 DevTools）

```bash
cd /Users/guoruping/project/bigdata/onedata-works/backend
./gradlew build
```

Spring Boot DevTools 会自动重新加载。

## ✅ 验证修复

### 1. 确认服务已启动

```bash
# 检查进程
ps aux | grep DataPortalApplication | grep -v grep

# 检查端口
curl -s http://localhost:8080/api/health || echo "服务未就绪"
```

### 2. 执行测试任务

1. 打开浏览器: http://localhost:3000
2. 进入任务管理页面
3. 点击任意任务的 "执行任务" 按钮

### 3. 检查日志输出

```bash
# 查看后端日志
tail -f /tmp/backend.log | grep -E "temporary workflow|actualCode"
```

**预期输出**（修复后）:
```
INFO: Created temporary workflow: name=test-task-{code} actualCode={actual_code}
INFO: Started single task execution (test mode): task=test workflow=test-task-{code} actualCode={actual_code} execution=exec-{id}
```

**对比修复前**:
```
INFO: Synchronized Dolphin workflow test-task-{code}({wrong_code}) ...
# (没有 actualCode 日志)
```

### 4. 检查 Python 服务日志

```bash
tail -f /tmp/dolphin-service.log | grep -i mismatch
```

**预期结果**: 应该**不再看到** "Workflow code mismatch" 警告

## 📊 预期效果

| 指标 | 修复前 | 修复后 |
|-----|--------|--------|
| 日志中的 actualCode | ❌ 无 | ✅ 有 |
| Code mismatch 警告 | ⚠️ 有 | ✅ 无 |
| Workflow code 一致性 | ❌ 不一致 | ✅ 一致 |

## 🐛 故障排查

### 问题1: 后端启动失败

**检查**:
```bash
tail -100 /tmp/backend.log | grep -i error
```

**可能原因**:
- 端口 8080 被占用
- 数据库连接失败
- 编译错误

**解决**:
```bash
# 检查端口占用
lsof -i :8080

# 如果被占用，杀掉进程
kill -9 <PID>

# 重新启动
cd backend && ./gradlew bootRun > /tmp/backend.log 2>&1 &
```

### 问题2: 仍然看到 mismatch 警告

**可能原因**: 缓存或旧代码仍在运行

**解决**:
```bash
# 1. 清理编译产物
cd backend
./gradlew clean

# 2. 重新编译
./gradlew build

# 3. 杀掉所有 Java 进程
pkill -f DataPortalApplication
pkill -f gradlew

# 4. 重新启动
./gradlew bootRun > /tmp/backend.log 2>&1 &
```

### 问题3: Python 服务没有响应

**检查**:
```bash
curl http://localhost:5001/health
```

**解决**:
```bash
# 重启 Python 服务
cd /Users/guoruping/project/bigdata/onedata-works/dolphinscheduler-service
pkill -f uvicorn

# 重新启动
python -m uvicorn dolphinscheduler_service.main:app --host 0.0.0.0 --port 5001 &
```

## 📚 相关文档

- [修复总结](./docs/FIX_SUMMARY.md)
- [详细修复文档](./docs/WORKFLOW_CODE_MISMATCH_FIX.md)
- [浏览器测试结果](./docs/BROWSER_TEST_RESULTS.md)
- [手动测试指南](./docs/MANUAL_TEST_GUIDE.md)

## ✨ 完成后

修复验证通过后，你可以：

1. ✅ 清理测试脚本（可选）:
```bash
./cleanup-test-scripts.sh
```

2. ✅ 提交代码（如果使用 Git）:
```bash
git add backend/src/main/java/com/onedata/portal/service/DataTaskService.java
git add docs/
git commit -m "fix: 修复临时工作流 workflow code 不匹配问题

- 使用 DolphinScheduler 返回的实际 workflow code
- 添加详细日志记录
- 消除 code mismatch 警告
- 提高代码健壮性"
```

---

**需要帮助？** 查看故障排查章节或检查相关文档。
