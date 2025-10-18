# 集成测试执行清单

## 第一阶段：DolphinSchedulerClient测试（当前阶段）

### ✅ 已完成的工作

1. **核心代码实现**
   - ✅ `DolphinSchedulerClient` - REST API客户端
   - ✅ 所有DTO类（DolphinResponse, LoginResponse, TaskDefinitionDTO等）
   - ✅ `DolphinSchedulerException` - 异常处理
   - ✅ 配置类 `DolphinSchedulerProperties`

2. **集成测试代码**
   - ✅ `DolphinSchedulerClientIntegrationTest` - 完整的测试用例
   - ✅ 测试配置 `application-test.yml`
   - ✅ 测试文档 `INTEGRATION_TEST_README.md`
   - ✅ 准备脚本 `prepare-test-env.sh`
   - ✅ 执行脚本 `run-integration-test.sh`

### 🎯 执行步骤

#### 步骤1: 准备测试环境（10分钟）

```bash
# 1. 启动DolphinScheduler服务
# 确保服务运行在 http://localhost:12345/dolphinscheduler

# 2. 运行环境检查脚本
cd /Users/guoruping/project/bigdata/onedata-works/backend
./scripts/prepare-test-env.sh

# 3. 通过UI创建测试项目和工作流
# 访问: http://localhost:12345/dolphinscheduler/ui
# 登录: admin/dolphinscheduler123
# 创建项目: data-portal-test
# 创建工作流: data-portal-test-workflow（空工作流即可）
```

#### 步骤2: 配置测试参数（2分钟）

编辑文件: `src/test/resources/application-test.yml`

```yaml
dolphin:
  service-url: http://localhost:8081
  project-name: [与Python服务一致的项目名]
  workflow-code: [从UI获取的工作流编码]   # ← 修改这里
  workflow-name: data-portal-test-workflow
  tenant-code: default
  worker-group: default
  execution-type: PARALLEL
```

#### 步骤3: 运行测试（5分钟）

```bash
# 方式1: 使用脚本（推荐）
./scripts/run-integration-test.sh

# 方式2: 使用Maven命令
mvn test -Dtest=DolphinSchedulerClientIntegrationTest -Dspring.profiles.active=test

# 方式3: 使用IDE
# 打开 DolphinSchedulerClientIntegrationTest
# 右键 → Run 'DolphinSchedulerClientIntegrationTest'
```

#### 步骤4: 验证测试结果（3分钟）

**检查控制台输出:**
```
[INFO] ========== 开始测试: 登录并获取工作流定义 ==========
[INFO] ✓ 测试通过: 成功登录并获取工作流定义
[INFO] ========== 开始测试: 下线工作流 ==========
[INFO] ✓ 测试通过: 成功下线工作流
[INFO] ========== 开始测试: 添加任务到工作流 ==========
[INFO] ✓ 测试通过: 成功添加任务到工作流
[INFO] ========== 开始测试: 发布工作流 ==========
[INFO] ✓ 测试通过: 成功发布工作流
[INFO] ========== 开始测试: 从工作流删除任务 ==========
[INFO] ✓ 测试通过: 成功从工作流删除任务

[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

**通过DolphinScheduler UI验证:**
1. 登录UI
2. 进入测试工作流
3. 检查DAG图是否正确
4. 查看工作流版本历史

### ⚠️ 常见问题快速解决

| 问题 | 解决方案 |
|------|---------|
| 连接被拒绝 | 检查DolphinScheduler服务是否启动 |
| 登录失败 | 验证用户名密码配置 |
| 工作流不存在 | 检查workflow-code配置，确认工作流已创建 |
| JSON解析错误 | 查看DEBUG日志，检查API响应格式 |
| 权限不足 | 使用admin账号，检查项目权限 |

### ✅ 测试通过标志

- [ ] 6个测试用例全部通过
- [ ] 无错误日志
- [ ] UI中能看到测试任务的添加和删除历史
- [ ] 工作流状态正确（ONLINE）

---

## 第二阶段：DolphinWorkflowService实现（待开始）

### 📋 待实现功能

1. **添加节点**
   - `addTaskToWorkflow()`
   - 自动血缘依赖绑定
   - 位置计算

2. **修改节点**
   - `updateTaskInWorkflow()`
   - 版本号管理
   - 可选依赖重绑定

3. **删除节点**
   - `removeTaskFromWorkflow()`
   - 依赖链重连
   - 状态清理

4. **工作流管理**
   - 工作流配置表
   - 状态同步
   - 任务计数

### 🎯 下一步行动

1. ✅ 确认DolphinSchedulerClient测试全部通过
2. ➡️ 实现DolphinWorkflowService
3. ➡️ 编写DolphinWorkflowService单元测试
4. ➡️ 编写端到端集成测试
5. ➡️ 集成到DataTaskService

---

## 第三阶段：完整集成测试（未开始）

### 📋 测试场景

1. **完整任务发布流程**
   - 创建任务
   - 配置血缘
   - 发布到DolphinScheduler
   - 验证DAG正确性

2. **依赖自动绑定**
   - 多个有依赖关系的任务
   - 验证依赖链正确
   - 验证循环依赖检测

3. **任务修改同步**
   - 修改任务SQL
   - 重新绑定依赖
   - 验证版本更新

4. **任务删除清理**
   - 删除中间节点
   - 验证依赖重连
   - 验证状态清理

---

## 测试报告模板

### 测试执行记录

**测试时间**: ___________
**执行人**: ___________
**DolphinScheduler版本**: ___________
**测试环境**: ___________

### 测试结果

| 测试用例 | 状态 | 备注 |
|---------|-----|------|
| 登录并获取工作流定义 | ⬜ PASS / ⬜ FAIL | |
| 生成任务编码 | ⬜ PASS / ⬜ FAIL | |
| 下线工作流 | ⬜ PASS / ⬜ FAIL | |
| 添加任务到工作流 | ⬜ PASS / ⬜ FAIL | |
| 发布工作流 | ⬜ PASS / ⬜ FAIL | |
| 从工作流删除任务 | ⬜ PASS / ⬜ FAIL | |

### 问题记录

1. **问题描述**:
   - **原因分析**:
   - **解决方案**:
   - **状态**: ⬜ 已解决 / ⬜ 待解决

### 测试结论

⬜ 测试通过，可以进入下一阶段
⬜ 存在问题，需要修复后重新测试

---

## 快速参考

### 关键文件位置

```
backend/
├── src/main/java/com/onedata/portal/
│   └── client/dolphin/
│       ├── DolphinSchedulerClient.java        # 核心客户端
│       ├── dto/                               # DTO定义
│       └── exception/                         # 异常定义
├── src/test/
│   ├── java/.../DolphinSchedulerClientIntegrationTest.java
│   └── resources/application-test.yml         # 测试配置
├── scripts/
│   ├── prepare-test-env.sh                    # 环境准备
│   └── run-integration-test.sh                # 测试执行
└── INTEGRATION_TEST_README.md                 # 详细文档
```

### 有用的命令

```bash
# 查看测试日志
tail -f target/surefire-reports/*.txt

# 只运行单个测试方法
mvn test -Dtest=DolphinSchedulerClientIntegrationTest#testLoginAndGetWorkflow

# 跳过测试编译
mvn install -DskipTests

# 清理测试缓存
mvn clean test
```

---

**最后更新**: 2025-01-18
**维护者**: Claude Code
