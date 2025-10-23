# 手动测试指南 - 临时工作流生命周期

## 前提条件

1. **确保服务运行:**
   ```bash
   # DolphinScheduler (已运行)
   docker ps | grep dolphin

   # 启动后端服务
   cd backend
   ./gradlew bootRun

   # 启动 Python 服务
   cd dolphinscheduler-service
   python -m uvicorn dolphinscheduler_service.main:app --host 0.0.0.0 --port 8081
   ```

2. **等待服务启动:**
   - 后端: http://localhost:8080
   - Python服务: http://localhost:8081
   - DolphinScheduler: http://localhost:12345/dolphinscheduler

## 测试步骤

### 方法 1: 通过浏览器测试

#### 步骤 1: 登录 DolphinScheduler 查看初始状态

1. 打开浏览器访问: http://localhost:12345/dolphinscheduler
2. 登录凭据:
   - 用户名: `admin`
   - 密码: `dolphinscheduler123`
3. 进入项目 "data-portal"
4. 点击 "工作流定义" 或 "Workflow Definition"
5. **记录当前工作流数量** (例如: 5个)

#### 步骤 2: 通过前端执行任务

1. 打开数据门户前端: http://localhost:3000 (如果运行)
2. 或直接使用 API:
   ```bash
   # 获取任务列表
   curl http://localhost:8080/api/tasks?pageNum=1&pageSize=10

   # 执行第一个任务 (假设 ID 为 1)
   curl -X POST http://localhost:8080/api/tasks/1/execute
   ```

#### 步骤 3: 验证临时工作流已创建

1. 回到 DolphinScheduler 界面
2. 刷新 "工作流定义" 页面
3. **应该看到新增一个工作流**，名称类似: `test-task-1735678901234`
4. 工作流数量应该 +1 (例如: 从5个变成6个)

#### 步骤 4: 手动触发删除测试

由于自动删除需要等待5分钟，我们可以手动触发:

```bash
# 获取临时工作流的 code (从浏览器界面或 API)
WORKFLOW_CODE=123456789

# 调用删除 API
curl -X POST "http://localhost:8081/api/v1/workflows/$WORKFLOW_CODE/delete" \
  -H "Content-Type: application/json" \
  -d '{"projectName": "data-portal"}'
```

#### 步骤 5: 验证工作流已删除

1. 回到 DolphinScheduler 界面
2. 刷新 "工作流定义" 页面
3. **临时工作流应该消失**
4. 工作流数量恢复到初始值 (例如: 回到5个)

### 方法 2: 通过 API 测试

使用提供的测试脚本:

```bash
# 运行自动化测试脚本
scripts/test/test-workflow-lifecycle.sh
```

脚本会自动:
1. 登录 DolphinScheduler
2. 记录初始工作流数量
3. 创建并执行测试任务
4. 验证临时工作流创建
5. 删除临时工作流
6. 验证工作流已删除
7. 输出测试结果

### 方法 3: 通过 Java 集成测试

```bash
cd backend
./gradlew test --tests TaskExecutionWorkflowTest
```

## 预期结果

### ✅ 成功场景

1. **执行任务后:**
   - DolphinScheduler 中出现名为 `test-task-{code}` 的新工作流
   - 工作流数量 +1
   - 工作流状态为 ONLINE

2. **删除后 (手动或5分钟后自动):**
   - 临时工作流从 DolphinScheduler 中消失
   - 工作流数量恢复到执行前的值
   - 通过 API 查询该工作流返回 404

### ❌ 失败场景 (修复前的行为)

1. **执行任务后:**
   - 临时工作流创建成功 ✓

2. **5分钟后:**
   - 临时工作流**仍然存在** ✗
   - 工作流数量持续增加 ✗
   - 需要手动删除 ✗

## 验证命令

### 查看当前所有工作流

```bash
# 登录获取 token
TOKEN=$(curl -s -X POST "http://localhost:12345/dolphinscheduler/login" \
  -d "userName=admin&userPassword=dolphinscheduler123" | jq -r '.data.sessionId')

# 获取项目代码
PROJECT_CODE=$(curl -s "http://localhost:12345/dolphinscheduler/projects/list?token=$TOKEN&pageSize=100" \
  | jq -r '.data.totalList[] | select(.name=="data-portal") | .code')

# 列出所有工作流
curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN" \
  | jq '.data.totalList[] | {name: .name, code: .code, releaseState: .releaseState}'
```

### 查找临时工作流

```bash
curl -s "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/list?token=$TOKEN" \
  | jq '.data.totalList[] | select(.name | startswith("test-task-"))'
```

### 删除特定工作流

```bash
WORKFLOW_CODE=<工作流代码>
curl -X DELETE "http://localhost:12345/dolphinscheduler/projects/$PROJECT_CODE/process-definition/$WORKFLOW_CODE?token=$TOKEN"
```

## 常见问题

### Q1: 后端服务启动失败

检查数据库连接:
```bash
# 查看后端日志
tail -f backend/build/bootRun/out.log
```

### Q2: Python 服务连接失败

检查环境变量和端口:
```bash
# 检查服务状态
curl http://localhost:8081/health

# 查看日志
tail -f dolphinscheduler-service/logs/app.log
```

### Q3: DolphinScheduler API 返回 401

Token 可能过期,重新登录获取新 token:
```bash
TOKEN=$(curl -s -X POST "http://localhost:12345/dolphinscheduler/login" \
  -d "userName=admin&userPassword=dolphinscheduler123" | jq -r '.data.sessionId')
```

### Q4: 删除失败返回错误

可能的原因:
1. 工作流正在运行 - 等待完成后再删除
2. 工作流不存在 - 已被删除
3. 权限不足 - 检查用户权限

## 测试清单

- [ ] 服务全部启动
- [ ] 登录 DolphinScheduler 成功
- [ ] 记录初始工作流数量
- [ ] 执行任务成功
- [ ] 临时工作流创建成功
- [ ] 工作流名称以 `test-task-` 开头
- [ ] 工作流状态为 ONLINE
- [ ] 手动删除工作流成功
- [ ] 工作流从列表中消失
- [ ] 工作流数量恢复正常

## 下一步

如果测试通过，说明:
1. ✅ 临时工作流创建功能正常
2. ✅ 工作流删除功能已修复
3. ✅ API 集成正确

如果测试失败，检查:
1. 服务日志
2. DolphinScheduler 配置
3. 网络连接
4. 数据库状态
