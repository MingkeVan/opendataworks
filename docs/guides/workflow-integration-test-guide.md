# 工作流生命周期集成测试指南

## 测试概述

本集成测试验证了完整的工作流生命周期管理场景，包括：

1. ✅ 创建3个表（table_a, table_b, table_c）
2. ✅ 创建3个串行依赖的SQL任务
3. ✅ 发布工作流并自动上线（ONLINE状态）
4. ✅ 下线工作流（OFFLINE状态）
5. ✅ 添加第4个新任务（依赖已存在的表）
6. ✅ 重新发布并上线工作流
7. ✅ 验证所有血缘关系和依赖
8. ✅ 清理测试数据

## 测试场景详解

### 场景1：初始工作流创建

```
table_a -> task_1 -> table_b -> task_2 -> table_c -> task_3
```

- **task_1**: 从 table_a 读取数据，写入 table_b（SQL任务）
- **task_2**: 从 table_b 读取数据，写入 table_c（SQL任务）
- **task_3**: 从 table_c 读取数据，进行验证（SQL任务）

依赖关系：task_1 -> task_2 -> task_3（串行）

### 场景2：添加新任务后的工作流

```
table_a -> task_1 -> table_b -> task_2 -> table_c -> task_3
                             \-> task_4
```

- **task_4**: 从 table_b 读取数据，进行分析（SQL任务）

依赖关系：
- task_1 -> task_2 -> task_3（串行）
- task_1 -> task_4（task_4 依赖 task_1，因为它们都操作 table_b）

## 前置条件

### 1. 服务运行检查

确保以下服务都在运行：

```bash
# 1. MySQL 数据库
mysql -u root -p -e "SELECT 1"

# 2. DolphinScheduler
# 访问 http://localhost:12345/dolphinscheduler
# 默认账号：admin/dolphinscheduler123

# 3. Python dolphinscheduler-service
cd dolphinscheduler-service
source venv/bin/activate
python -m dolphinscheduler_service.app
# 应该监听在 http://localhost:5001

# 4. 后端服务
cd backend
./mvnw spring-boot:run
# 应该监听在 http://localhost:8080
```

### 2. 创建 Doris 数据源

在运行测试前，需要在 DolphinScheduler 中创建名为 `doris_test` 的数据源：

1. 登录 DolphinScheduler UI: http://localhost:12345/dolphinscheduler
2. 进入"数据源中心" -> "创建数据源"
3. 填写信息：
   - 数据源类型: MYSQL
   - 数据源名称: doris_test
   - IP地址: localhost
   - 端口: 9030
   - 数据库名: test_db
   - 用户名: root
   - 密码: (留空或您的密码)
4. 测试连接并保存

### 3. 初始化测试数据库

在 Doris 中创建测试数据库和表：

```sql
-- 连接到 Doris
mysql -h localhost -P 9030 -u root

-- 创建测试数据库
CREATE DATABASE IF NOT EXISTS test_db;
USE test_db;

-- 创建测试表（测试会自动使用这些表名）
CREATE TABLE IF NOT EXISTS test_table_a (
    id INT,
    name VARCHAR(100),
    value DECIMAL(10,2),
    created_time DATETIME
) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1;

CREATE TABLE IF NOT EXISTS test_table_b (
    id INT,
    name VARCHAR(100),
    value DECIMAL(10,2),
    created_time DATETIME
) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1;

CREATE TABLE IF NOT EXISTS test_table_c (
    id INT,
    name VARCHAR(100),
    value DECIMAL(10,2),
    created_time DATETIME
) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1;

-- 插入一些测试数据到 table_a
INSERT INTO test_table_a VALUES
(1, 'Alice', 100.50, '2025-10-18 10:00:00'),
(2, 'Bob', 200.75, '2025-10-18 11:00:00'),
(3, 'Charlie', 150.25, '2025-10-18 12:00:00');
```

## 运行测试

### 方式1：使用 Maven 运行

```bash
cd backend

# 运行特定的集成测试
./mvnw test -Dtest=WorkflowLifecycleIntegrationTest

# 或运行所有测试
./mvnw test
```

### 方式2：使用 IDE 运行

1. 在 IntelliJ IDEA 或 Eclipse 中打开项目
2. 找到 `WorkflowLifecycleIntegrationTest.java`
3. 右键点击类名 -> Run 'WorkflowLifecycleIntegrationTest'
4. 或者点击类内的绿色运行按钮

### 方式3：运行单个测试步骤

测试步骤按顺序执行（使用 `@Order` 注解），可以单独运行：

```bash
# 只运行步骤1：创建表
./mvnw test -Dtest=WorkflowLifecycleIntegrationTest#step1_createTables

# 只运行步骤3：发布工作流
./mvnw test -Dtest=WorkflowLifecycleIntegrationTest#step3_publishAndOnlineWorkflow
```

## 测试步骤详解

### 步骤1：创建3个测试表 ✅
- 创建 test_table_a (ods层)
- 创建 test_table_b (dwd层)
- 创建 test_table_c (dws层)

### 步骤2：创建3个串行依赖的任务 ✅
- task_1: 转换任务1_A到B (读 table_a, 写 table_b)
- task_2: 转换任务2_B到C (读 table_b, 写 table_c)
- task_3: 验证任务3_读C (读 table_c)

### 步骤3：发布工作流并上线 ✅
- 发布所有3个任务
- 验证工作流已创建
- 验证工作流自动上线（ONLINE状态）
- 验证所有任务关联到同一工作流

### 步骤4：下线工作流 ✅
- 调用 `setWorkflowReleaseState(workflowCode, "OFFLINE")`
- 验证工作流已下线

### 步骤5：添加新的串行任务 task_4 ✅
- task_4: 分析任务4_读B (读 table_b)
- 创建血缘关系（依赖 table_b）

### 步骤6：重新发布并上线工作流 ✅
- 发布 task_4
- 验证工作流重新同步
- 验证工作流自动上线
- 验证 task_4 关联到同一工作流

### 步骤7：验证血缘关系和依赖 ✅
- 验证 task_1 的输入/输出血缘
- 验证 task_2 的输入/输出血缘
- 验证 task_3 的输入血缘
- 验证 task_4 的输入血缘
- 验证完整血缘图

### 步骤8：清理测试数据 ✅
- 下线工作流
- 删除所有任务
- 删除所有表
- 清理血缘关系

## 预期输出

测试成功运行后，控制台应输出类似以下内容：

```
================================================================================
🧪 步骤1：创建3个测试表
================================================================================

📋 创建测试表...

✅ 创建表 table_a (ID: 1)
✅ 创建表 table_b (ID: 2)
✅ 创建表 table_c (ID: 3)
================================================================================

================================================================================
🧪 步骤2：创建3个串行依赖的任务
================================================================================

⚙️ 创建串行任务...

✅ 创建任务1 (ID: 1): table_a -> table_b
✅ 创建任务2 (ID: 2): table_b -> table_c
✅ 创建任务3 (ID: 3): 读取 table_c 进行验证

📊 任务依赖关系：
   task_1 -> task_2 -> task_3 (串行依赖)
================================================================================

... (更多输出)

✅ 所有测试通过
```

## 验证结果

### 1. 在数据库中验证

```sql
-- 查看创建的任务
SELECT id, task_name, task_code, dolphin_task_code, status
FROM data_task
WHERE task_code LIKE 'test_task_%'
ORDER BY id;

-- 查看血缘关系
SELECT t.task_name, dt1.table_name as upstream_table, dt2.table_name as downstream_table
FROM data_lineage dl
LEFT JOIN data_task t ON dl.task_id = t.id
LEFT JOIN data_table dt1 ON dl.upstream_table_id = dt1.id
LEFT JOIN data_table dt2 ON dl.downstream_table_id = dt2.id
WHERE t.task_code LIKE 'test_task_%';
```

### 2. 在 DolphinScheduler UI 中验证

1. 登录 DolphinScheduler UI
2. 进入"工作流定义"
3. 找到 "onedata-unified-workflow" 工作流
4. 查看工作流DAG图，应该能看到：
   - 4个任务节点
   - 任务之间的依赖连线
   - task_1 -> task_2 -> task_3
   - task_1 -> task_4

### 3. 在 Python 服务日志中验证

```bash
# 查看 dolphinscheduler-service 日志
tail -f dolphinscheduler-service/logs/app.log

# 应该看到类似的日志
# [INFO] Updating existing workflow: workflow=onedata-unified-workflow code=XXXXX tasks=4
# [INFO] Workflow onedata-unified-workflow submitted successfully with code XXXXX and 4 tasks
```

## 常见问题排查

### Q1: 测试失败，提示"数据源不存在"

**原因**: DolphinScheduler 中未创建 `doris_test` 数据源

**解决**: 按照"前置条件 -> 2. 创建 Doris 数据源"的步骤创建数据源

### Q2: 测试失败，提示"无法连接到 dolphinscheduler-service"

**原因**: Python 服务未运行

**解决**:
```bash
cd dolphinscheduler-service
source venv/bin/activate
python -m dolphinscheduler_service.app
```

### Q3: 工作流创建成功但任务依赖关系不对

**原因**: 血缘关系数据不正确

**解决**: 检查 `data_lineage` 表的数据，确保：
- task_1 的 input 是 table_a，output 是 table_b
- task_2 的 input 是 table_b，output 是 table_c
- task_3 的 input 是 table_c
- task_4 的 input 是 table_b

### Q4: 测试提示"任务编码已存在"

**原因**: 之前运行的测试数据未清理

**解决**:
```sql
-- 手动清理测试数据
DELETE FROM data_lineage WHERE task_id IN (
    SELECT id FROM data_task WHERE task_code LIKE 'test_task_%'
);
DELETE FROM data_task WHERE task_code LIKE 'test_task_%';
DELETE FROM data_table WHERE table_name LIKE 'test_table_%';
```

## 测试覆盖的关键功能

✅ **数据建模**
- 创建表元数据
- 创建任务定义
- 创建血缘关系

✅ **工作流管理**
- 创建新工作流
- 更新已存在的工作流
- 下线/上线工作流

✅ **任务依赖**
- 通过血缘自动推导任务依赖
- 串行依赖链
- 并行依赖（task_2 和 task_4 都依赖 task_1）

✅ **SQL 节点支持**
- SQL 任务参数构建
- 数据源配置
- SQL 类型判断（QUERY vs NON_QUERY）

✅ **生命周期管理**
- 任务状态流转（draft -> published）
- 工作流状态流转（OFFLINE -> ONLINE）
- 动态添加任务

## 扩展测试场景

可以基于此测试继续扩展：

1. **测试并行任务执行**
   - 创建多个不相关的任务
   - 验证并行执行

2. **测试任务失败重试**
   - 设置 retryTimes 和 retryInterval
   - 验证失败重试机制

3. **测试超时控制**
   - 设置 timeoutSeconds
   - 验证超时后的行为

4. **测试不同节点类型**
   - SHELL 节点
   - PYTHON 节点
   - SPARK 节点

5. **测试工作流执行**
   - 调用 execute() 方法
   - 验证执行日志记录

## 总结

本集成测试全面验证了工作流生命周期管理的关键场景：

1. ✅ 创建串行依赖的任务链
2. ✅ 发布和上线工作流
3. ✅ 动态添加新任务（先下线，添加任务，再上线）
4. ✅ 血缘关系和依赖推导
5. ✅ 数据清理和资源释放

这确保了系统在真实使用场景下的稳定性和正确性。
