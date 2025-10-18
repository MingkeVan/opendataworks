# DolphinScheduler SQL 节点支持 - 实现总结

## 已完成的工作

### 1. ✅ 扩展 DataTask 实体

**文件**: `backend/src/main/java/com/onedata/portal/entity/DataTask.java`

新增字段：
- `dolphinNodeType`: Dolphin 节点类型（SHELL/SQL/PYTHON/SPARK/FLINK）
- `datasourceName`: SQL 节点数据源名称
- `datasourceType`: 数据源类型（MYSQL/DORIS等）

```java
private String dolphinNodeType; // SHELL, SQL, PYTHON, SPARK, FLINK
private String datasourceName; // datasource name for SQL node
private String datasourceType; // datasource type: MYSQL, DORIS, etc.
```

### 2. ✅ 修改数据库表添加新字段

**文件**: `backend/src/main/resources/db/migration/V2__add_table_features.sql`

数据库迁移脚本已更新：
```sql
ALTER TABLE data_task
ADD COLUMN IF NOT EXISTS dolphin_node_type VARCHAR(20) COMMENT 'Dolphin节点类型(SHELL/SQL/PYTHON/SPARK/FLINK)',
ADD COLUMN IF NOT EXISTS datasource_name VARCHAR(100) COMMENT 'SQL节点数据源名称',
ADD COLUMN IF NOT EXISTS datasource_type VARCHAR(20) COMMENT '数据源类型(MYSQL/DORIS等)';
```

### 3. ✅ 扩展 DolphinSchedulerService 支持 SQL 节点

**文件**: `backend/src/main/java/com/onedata/portal/service/DolphinSchedulerService.java`

**关键实现**：

1. **重载的 buildTaskDefinition 方法**（第180-220行）：
   ```java
   public Map<String, Object> buildTaskDefinition(
       long taskCode, int taskVersion, String taskName, String description,
       String rawScript, String taskPriority, int retryTimes, int retryInterval,
       int timeoutSeconds, String nodeType, String datasourceName, String datasourceType)
   ```

2. **TaskParams 内部类支持 SQL**（第354-384行）：
   - `TaskParams.shell(String script)` - Shell 任务
   - `TaskParams.sql(String sql, String datasourceName, String datasourceType)` - SQL 任务

3. **SQL 参数构建**：
   ```java
   if ("SQL".equalsIgnoreCase(nodeType)) {
       payload.put("taskParams", objectMapper.writeValueAsString(
           TaskParams.sql(rawScript, datasourceName, datasourceType)));
   }
   ```

### 4. ✅ 扩展 Python scheduler 支持 SQL 任务类型

**文件**: `dolphinscheduler-service/dolphinscheduler_service/scheduler.py`

**关键实现**：

1. **导入 SQL 任务**（第11行）：
   ```python
   from pydolphinscheduler.tasks.sql import Sql
   ```

2. **_build_task 方法支持 SQL**（第209-256行）：
   ```python
   def _build_task(self, workflow: Workflow, payload: TaskDefinitionPayload) -> Shell | Sql:
       task_type = payload.task_type.upper() if payload.task_type else "SHELL"

       if task_type == "SQL":
           task = Sql(
               name=payload.name,
               datasource_name=datasource_name,
               sql=sql_statement,
               datasource_type=datasource_type,
               sql_type=sql_type,  # 0=NON_QUERY, 1=QUERY
               workflow=workflow,
               ...
           )
       else:
           task = Shell(...)
   ```

3. **任务映射支持 SQL**（第94行）：
   ```python
   tasks_map: Dict[int, Shell | Sql] = {}
   ```

### 5. ✅ 创建测试指南文档

**文件**: `docs/sql-task-test-guide.md`

包含以下内容：
1. 在 DolphinScheduler 中创建 Doris 数据源的详细步骤
2. 在 Doris 中准备测试数据的 SQL 脚本
3. 创建 3 个串行依赖的 SQL 测试任务的 HTTP 请求示例
4. 发布和执行任务的命令
5. 验证任务执行结果的方法
6. 常见问题解答
7. 高级用法示例

## 架构说明

### 数据流向

```
用户创建 SQL 任务
    ↓
DataTaskController 接收请求
    ↓
DataTaskService 创建任务记录
    ↓
发布时：DolphinSchedulerService.buildTaskDefinition()
    ↓
构建 SQL 任务参数（TaskParams.sql()）
    ↓
通过 HTTP 调用 dolphinscheduler-service (Python)
    ↓
Python scheduler 使用 pydolphinscheduler.tasks.sql.Sql
    ↓
提交到 DolphinScheduler
    ↓
DolphinScheduler 执行 SQL 任务（连接数据源）
```

### SQL 任务参数结构

```json
{
  "code": 1234567890,
  "name": "SQL任务示例",
  "taskType": "SQL",
  "taskParams": {
    "datasource": "doris_test",
    "sql": "SELECT COUNT(*) FROM table_name",
    "type": "MYSQL",
    "sqlType": 1,
    "displayRows": 10
  }
}
```

## 测试步骤

### 前置条件

1. ✅ DolphinScheduler 服务运行中
2. ✅ dolphinscheduler-service (Python) 运行中
3. ✅ Doris 服务运行中（FE MySQL 端口 9030）
4. ✅ 后端服务运行中

### 测试流程

1. **创建 Doris 数据源**
   - 在 DolphinScheduler UI 中创建名为 `doris_test` 的数据源
   - 类型：MYSQL
   - 地址：localhost:9030
   - 数据库：test_db

2. **准备测试数据**
   - 在 Doris 中执行 `docs/sql-task-test-guide.md` 中的建表和插入语句

3. **创建测试任务**
   - 任务1：查询源表数据（SQL）
   - 任务2：数据转换（SQL，依赖任务1）
   - 任务3：验证结果（SQL，依赖任务2）

4. **发布并执行**
   - 依次发布 3 个任务
   - 执行任务流程
   - 在 DolphinScheduler UI 查看执行结果

5. **验证结果**
   - 在 Doris 中查询 `target_table` 应该有数据
   - DolphinScheduler UI 显示任务成功执行

## 技术要点

### 1. SQL 类型判断

```java
// Java side
this.sqlType = sql != null && sql.trim().toUpperCase().startsWith("SELECT") ? 1 : 0;
```

- `sqlType = 0`: NON_QUERY（INSERT/UPDATE/DELETE）
- `sqlType = 1`: QUERY（SELECT）

### 2. Doris 兼容性

Doris 完全兼容 MySQL 协议，因此：
- 数据源类型选择 `MYSQL`
- JDBC 驱动使用 MySQL Connector
- 端口使用 9030（Doris FE MySQL 端口）

### 3. 任务依赖

任务依赖通过 `TaskRelationPayload` 实现：
```java
TaskRelationPayload relation = new TaskRelationPayload();
relation.setPreTaskCode(upstreamCode);
relation.setPostTaskCode(downstreamCode);
```

### 4. 超时转换

```python
# Python side: 秒 -> 分钟
timeout_minutes = max(1, math.ceil(timeout_seconds / 60))
```

## 文件清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `DataTask.java` | ✅ 已修改 | 添加 SQL 相关字段 |
| `V2__add_table_features.sql` | ✅ 已修改 | 数据库迁移脚本 |
| `DolphinSchedulerService.java` | ✅ 已完善 | SQL 任务构建逻辑 |
| `scheduler.py` | ✅ 已完善 | Python SQL 任务支持 |
| `sql-task-test-guide.md` | ✅ 新建 | 测试指南文档 |

## 后续优化建议

1. **数据源管理**
   - 在后端增加数据源配置管理功能
   - 支持从 DolphinScheduler 同步数据源列表

2. **SQL 验证**
   - 在创建任务时进行 SQL 语法验证
   - 支持 SQL 预执行和 EXPLAIN

3. **监控增强**
   - SQL 执行时间监控
   - 慢查询分析
   - 数据源连接池监控

4. **UI 增强**
   - 前端任务创建页面增加数据源选择器
   - SQL 编辑器支持语法高亮和自动补全
   - 任务执行结果可视化展示

5. **更多任务类型**
   - PYTHON 节点支持
   - SPARK 节点支持
   - FLINK 节点支持

## 总结

本次实现成功为 onedata-works 平台添加了 DolphinScheduler SQL 节点支持，主要成果：

1. ✅ 数据模型扩展完成
2. ✅ Java 服务端支持 SQL 任务构建
3. ✅ Python 调度服务支持 SQL 任务
4. ✅ 完整的测试指南和文档
5. ✅ 支持串行依赖的任务编排

现在可以通过 API 创建 SQL 类型的任务，这些任务将在 DolphinScheduler 中以 SQL 节点执行，直接连接 Doris 等数据源进行数据处理。
