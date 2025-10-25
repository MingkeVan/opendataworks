# DolphinScheduler集成测试指南

## 测试前准备

### 1. 启动DolphinScheduler服务

确保DolphinScheduler服务已启动并可访问：
```bash
# 检查服务是否可访问
curl http://localhost:12345/dolphinscheduler/login

# 预期返回登录页面或JSON响应
```

### 2. 创建测试项目和工作流

#### 方式1: 通过UI创建（推荐）

1. 访问 DolphinScheduler UI: `http://localhost:12345/dolphinscheduler/ui`
2. 使用 `admin/dolphinscheduler123` 登录
3. 创建项目：
   - 项目名称: `data-portal-test`
   - 记录项目编码 (project-code)
4. 在项目中创建工作流：
   - 工作流名称: `data-portal-test-workflow`
   - 记录工作流编码 (workflow-code)

#### 方式2: 通过API创建

```bash
# 1. 登录获取sessionId
curl -X POST "http://localhost:12345/dolphinscheduler/login?userName=admin&userPassword=dolphinscheduler123"

# 2. 创建项目（如果不存在）
curl -X POST "http://localhost:12345/dolphinscheduler/projects" \
  -H "Cookie: sessionId=YOUR_SESSION_ID" \
  -d "projectName=data-portal-test&description=测试项目"

# 3. 创建工作流
curl -X POST "http://localhost:12345/dolphinscheduler/projects/{projectCode}/workflow-definition" \
  -H "Cookie: sessionId=YOUR_SESSION_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "data-portal-test-workflow",
    "description": "集成测试工作流",
    "taskDefinitionJson": "[]",
    "taskRelationJson": "[]",
    "locations": "[]",
    "timeout": 0,
    "executionType": "PARALLEL"
  }'
```

### 3. 更新测试配置

修改 `backend/src/test/resources/application-test.yml`:

```yaml
dolphin:
  service-url: http://localhost:8081
  project-name: data-portal-test-workflow
  workflow-code: YOUR_WORKFLOW_CODE    # 替换为实际的工作流编码
  workflow-name: data-portal-test-workflow
  tenant-code: default
  worker-group: default
  execution-type: PARALLEL
```

### 4. 配置数据库

确保测试数据库已创建：
```sql
CREATE DATABASE IF NOT EXISTS onedata_portal
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

## 运行测试

### 方式1: 使用Maven命令

```bash
cd /Users/guoruping/project/bigdata/onedata-works/backend

# 运行单个测试类
mvn test -Dtest=DolphinSchedulerClientIntegrationTest -Dspring.profiles.active=test

# 运行所有集成测试
mvn test -P integration-test
```

### 方式2: 使用IDE（IDEA）

1. 打开测试类: `DolphinSchedulerClientIntegrationTest`
2. 右键点击类名 → `Run 'DolphinSchedulerClientIntegrationTest'`
3. 或点击测试方法左侧的绿色箭头运行单个测试

### 方式3: 使用命令行（推荐用于调试）

```bash
cd /Users/guoruping/project/bigdata/onedata-works/backend

# 编译项目
mvn clean compile test-compile

# 运行测试（带详细日志）
mvn test \
  -Dtest=DolphinSchedulerClientIntegrationTest \
  -Dspring.profiles.active=test \
  -Dlogging.level.com.onedata.portal=DEBUG
```

## 测试流程说明

测试按以下顺序执行（通过@Order注解控制）：

1. **测试1: 登录并获取工作流定义**
   - 验证登录功能
   - 验证能否获取工作流详情
   - 检查工作流基本信息

2. **测试2: 生成任务编码（本地序列）**
   - 调用后端本地序列生成逻辑
   - 验证返回的编码有效性
   - 保存编码供后续测试使用

3. **测试3: 下线工作流**
   - 调用下线API
   - 验证工作流状态变更

4. **测试4: 添加任务到工作流**
   - 构建完整的任务定义
   - 构建任务依赖关系
   - 更新工作流定义
   - 验证任务添加成功

5. **测试5: 发布工作流**
   - 调用上线API
   - 验证工作流状态为ONLINE

6. **测试6: 从工作流删除任务**
   - 下线工作流
   - 删除测试任务
   - 更新工作流
   - 重新上线
   - 验证任务删除成功

## 验证结果

### 通过UI验证

1. 登录 DolphinScheduler UI
2. 进入测试项目和工作流
3. 检查：
   - 工作流定义是否正确
   - 任务是否按预期添加/删除
   - DAG图是否正确显示

### 通过日志验证

查看测试日志输出：
```
[INFO] ========== 开始测试: 登录并获取工作流定义 ==========
[INFO] 工作流信息: code=20250118001, name=data-portal-test-workflow, taskCount=0, relationCount=0
[INFO] ✓ 测试通过: 成功登录并获取工作流定义
...
```

## 常见问题排查

### 问题1: 连接被拒绝

```
java.net.ConnectException: Connection refused
```

**解决方法**:
- 检查DolphinScheduler服务是否启动
- 验证URL配置是否正确
- 检查端口是否被占用

### 问题2: 登录失败

```
DolphinSchedulerException: 登录失败: 用户名或密码错误
```

**解决方法**:
- 检查用户名和密码配置
- 验证DolphinScheduler用户是否存在
- 检查密码是否正确

### 问题3: 工作流不存在

```
DolphinSchedulerException: 工作流不存在
```

**解决方法**:
- 检查workflow-code配置是否正确
- 通过UI确认工作流是否已创建
- 检查project-code是否正确

### 问题4: 权限不足

```
DolphinSchedulerException: 权限不足
```

**解决方法**:
- 确认用户对项目和工作流有操作权限
- 使用admin账号进行测试
- 检查租户配置是否正确

### 问题5: JSON解析错误

```
com.fasterxml.jackson.databind.JsonMappingException
```

**解决方法**:
- 检查DTO定义是否与API响应匹配
- 查看DEBUG日志中的原始响应内容
- 验证Jackson配置是否正确

## 调试技巧

### 1. 开启详细日志

在 `application-test.yml` 中：
```yaml
logging:
  level:
    com.onedata.portal.client.dolphin: TRACE
    org.springframework.web.reactive: DEBUG
```

### 2. 查看HTTP请求详情

添加日志配置：
```yaml
logging:
  level:
    reactor.netty.http.client: DEBUG
```

### 3. 捕获原始响应

在测试中添加断点，查看response变量的值

### 4. 使用Postman验证API

导出测试中的HTTP请求，在Postman中手动执行验证

## 测试数据清理

测试完成后，可选择清理测试数据：

### 方式1: 通过UI清理
1. 登录DolphinScheduler UI
2. 删除测试工作流
3. 删除测试项目

### 方式2: 保留测试数据
- 测试数据可以保留用于后续测试
- 确保不影响生产环境

## 下一步

测试通过后：
1. ✅ 验证DolphinSchedulerClient功能正常
2. ➡️ 实现DolphinWorkflowService
3. ➡️ 集成到DataTaskService
4. ➡️ 进行端到端测试
