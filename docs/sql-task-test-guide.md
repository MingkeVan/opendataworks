# SQL 任务测试指南

本文档说明如何在 DolphinScheduler 中创建 Doris 数据源并测试 SQL 任务。

## 1. 在 DolphinScheduler 中创建 Doris 数据源

### 方式一：通过 Web UI 创建（推荐）

1. 登录 DolphinScheduler Web UI
   - 默认地址：http://localhost:12345/dolphinscheduler
   - 默认账号：admin/dolphinscheduler123

2. 进入数据源管理
   - 导航到：数据源中心 -> 创建数据源

3. 填写数据源信息
   ```
   数据源类型: MYSQL (Doris 兼容 MySQL 协议)
   数据源名称: doris_test
   数据源描述: Doris 测试数据源
   IP 地址: localhost
   端口: 9030 (Doris FE MySQL 协议端口)
   数据库名: test_db
   用户名: root
   密码: (留空或您的密码)
   ```

4. 测试连接并保存

### 方式二：通过 API 创建

```bash
curl -X POST 'http://localhost:12345/dolphinscheduler/datasources' \
  -H 'Content-Type: application/json' \
  -H 'token: YOUR_TOKEN' \
  -d '{
    "name": "doris_test",
    "note": "Doris测试数据源",
    "type": "MYSQL",
    "host": "localhost",
    "port": 9030,
    "database": "test_db",
    "userName": "root",
    "password": "",
    "connectType": "JDBC"
  }'
```

## 2. 在 Doris 中准备测试数据

在 Doris 中执行以下 SQL 创建测试表：

```sql
-- 创建测试数据库
CREATE DATABASE IF NOT EXISTS test_db;
USE test_db;

-- 创建源表
CREATE TABLE IF NOT EXISTS source_table (
    id INT,
    name VARCHAR(100),
    created_time DATETIME
)
DUPLICATE KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 1;

-- 插入测试数据
INSERT INTO source_table VALUES
(1, 'Alice', '2025-10-18 10:00:00'),
(2, 'Bob', '2025-10-18 11:00:00'),
(3, 'Charlie', '2025-10-18 12:00:00');

-- 创建目标表
CREATE TABLE IF NOT EXISTS target_table (
    id INT,
    name VARCHAR(100),
    created_time DATETIME,
    processed_time DATETIME
)
DUPLICATE KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 1;
```

## 3. 创建串行依赖的 SQL 测试任务

### 任务 1：查询源数据（SQL 任务）

```bash
curl -X POST 'http://localhost:8080/v1/tasks' \
  -H 'Content-Type: application/json' \
  -d '{
    "task": {
      "taskName": "查询源表数据",
      "taskCode": "sql_query_source",
      "taskType": "batch",
      "engine": "dolphin",
      "dolphinNodeType": "SQL",
      "datasourceName": "doris_test",
      "datasourceType": "MYSQL",
      "taskSql": "SELECT COUNT(*) as total FROM source_table",
      "taskDesc": "查询源表记录数",
      "scheduleCron": null,
      "priority": 2,
      "timeoutSeconds": 300,
      "retryTimes": 1,
      "retryInterval": 60,
      "owner": "admin",
      "status": "draft"
    },
    "inputTableIds": [],
    "outputTableIds": []
  }'
```

### 任务 2：数据转换（SQL 任务，依赖任务1）

```bash
curl -X POST 'http://localhost:8080/v1/tasks' \
  -H 'Content-Type: application/json' \
  -d '{
    "task": {
      "taskName": "数据转换任务",
      "taskCode": "sql_transform_data",
      "taskType": "batch",
      "engine": "dolphin",
      "dolphinNodeType": "SQL",
      "datasourceName": "doris_test",
      "datasourceType": "MYSQL",
      "taskSql": "INSERT INTO target_table SELECT id, name, created_time, NOW() as processed_time FROM source_table WHERE id > 0",
      "taskDesc": "将源表数据转换并插入目标表",
      "scheduleCron": null,
      "priority": 2,
      "timeoutSeconds": 600,
      "retryTimes": 2,
      "retryInterval": 60,
      "owner": "admin",
      "status": "draft"
    },
    "inputTableIds": [],
    "outputTableIds": []
  }'
```

### 任务 3：验证结果（SQL 任务，依赖任务2）

```bash
curl -X POST 'http://localhost:8080/v1/tasks' \
  -H 'Content-Type: application/json' \
  -d '{
    "task": {
      "taskName": "验证数据完整性",
      "taskCode": "sql_verify_result",
      "taskType": "batch",
      "engine": "dolphin",
      "dolphinNodeType": "SQL",
      "datasourceName": "doris_test",
      "datasourceType": "MYSQL",
      "taskSql": "SELECT COUNT(*) as count, MAX(processed_time) as last_processed FROM target_table",
      "taskDesc": "验证目标表数据完整性",
      "scheduleCron": null,
      "priority": 2,
      "timeoutSeconds": 300,
      "retryTimes": 1,
      "retryInterval": 60,
      "owner": "admin",
      "status": "draft"
    },
    "inputTableIds": [],
    "outputTableIds": []
  }'
```

## 4. 发布和执行任务

### 发布任务

假设创建的任务 ID 分别为 1, 2, 3，需要依次发布：

```bash
# 发布任务 1
curl -X POST 'http://localhost:8080/v1/tasks/1/publish'

# 发布任务 2
curl -X POST 'http://localhost:8080/v1/tasks/2/publish'

# 发布任务 3
curl -X POST 'http://localhost:8080/v1/tasks/3/publish'
```

### 手动执行任务

```bash
# 执行任务流程（会按依赖顺序执行）
curl -X POST 'http://localhost:8080/v1/tasks/1/execute'
```

## 5. 验证任务执行结果

### 在 Doris 中查询结果

```sql
-- 查看目标表数据
SELECT * FROM test_db.target_table;

-- 应该能看到从 source_table 转换过来的数据，包含 processed_time 字段
```

### 在 DolphinScheduler UI 中查看

1. 登录 DolphinScheduler Web UI
2. 导航到：工作流实例 -> 查看实例详情
3. 查看任务执行状态、日志和结果

## 6. 测试要点

- ✅ SQL 节点支持 Doris（通过 MySQL 协议）
- ✅ 任务之间的串行依赖关系
- ✅ 任务失败重试机制
- ✅ 任务超时控制
- ✅ 任务执行日志和监控

## 7. 常见问题

### Q: 数据源连接失败？
A: 检查 Doris FE 服务是否启动，端口 9030 是否可访问

### Q: SQL 执行报错？
A: 检查 SQL 语法是否正确，表是否存在，权限是否足够

### Q: 任务依赖没有生效？
A: 确认任务已正确发布，且 DataTaskService 正确设置了任务依赖关系

## 8. 高级用法

### 使用 Shell 任务调用 Doris

如果需要更复杂的操作，可以创建 SHELL 类型任务：

```json
{
  "task": {
    "taskName": "Shell调用Doris",
    "taskCode": "shell_call_doris",
    "taskType": "batch",
    "engine": "dolphin",
    "dolphinNodeType": "SHELL",
    "taskSql": "#!/bin/bash\nmysql -h localhost -P 9030 -u root test_db -e \"SELECT COUNT(*) FROM source_table\"",
    "taskDesc": "通过Shell脚本调用Doris",
    "priority": 2,
    "timeoutSeconds": 300
  }
}
```
