# 任务表单增强 - 支持 DolphinScheduler 节点类型选择

## 问题描述

之前通过数据中台前端创建的任务，提交到 DolphinScheduler 后都显示为 SHELL 任务，即使任务中包含 SQL 代码。

### 根本原因

1. **前端缺失字段**：任务创建表单没有以下关键字段：
   - `dolphinNodeType` - DolphinScheduler 节点类型（SQL/SHELL/PYTHON）
   - `datasourceName` - 数据源名称（SQL 节点必需）
   - `datasourceType` - 数据源类型（DORIS/MYSQL 等）

2. **后端默认行为**：
   ```java
   // DataTaskService.java:113
   String nodeType = dataTask.getDolphinNodeType() != null ?
       dataTask.getDolphinNodeType() : "SHELL";
   ```
   当前端没有传递 `dolphinNodeType` 时，后端默认使用 SHELL 类型。

## 解决方案

### 1. 前端表单增强

#### 新增字段（TaskForm.vue）

**节点类型选择器**（当 engine = 'dolphin' 时显示）：
```vue
<el-form-item label="节点类型" prop="dolphinNodeType" v-if="form.engine === 'dolphin'">
  <el-select v-model="form.dolphinNodeType" style="width: 100%">
    <el-option label="SQL" value="SQL" />
    <el-option label="SHELL" value="SHELL" />
    <el-option label="PYTHON" value="PYTHON" />
  </el-select>
</el-form-item>
```

**数据源配置**（当 dolphinNodeType = 'SQL' 时显示）：
```vue
<el-form-item label="数据源名称" prop="datasourceName"
              v-if="form.engine === 'dolphin' && form.dolphinNodeType === 'SQL'">
  <el-input v-model="form.datasourceName" placeholder="例如: doris_test" />
</el-form-item>

<el-form-item label="数据源类型" prop="datasourceType"
              v-if="form.engine === 'dolphin' && form.dolphinNodeType === 'SQL'">
  <el-select v-model="form.datasourceType" style="width: 100%">
    <el-option label="DORIS" value="DORIS" />
    <el-option label="MYSQL" value="MYSQL" />
    <el-option label="POSTGRESQL" value="POSTGRESQL" />
    <el-option label="CLICKHOUSE" value="CLICKHOUSE" />
    <el-option label="HIVE" value="HIVE" />
  </el-select>
</el-form-item>
```

**表单数据模型更新**：
```javascript
const form = reactive({
  // ... 原有字段
  dolphinNodeType: 'SQL',        // 新增：默认 SQL
  datasourceName: 'doris_test',  // 新增：默认 doris_test
  datasourceType: 'DORIS',       // 新增：默认 DORIS
  // ...
})
```

**验证规则**：
```javascript
const rules = {
  // ... 原有规则
  dolphinNodeType: [{ required: true, message: '请选择节点类型', trigger: 'change' }],
  datasourceName: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  datasourceType: [{ required: true, message: '请选择数据源类型', trigger: 'change' }]
}
```

### 2. 任务列表显示增强（TaskList.vue）

**新增列**：
- **节点类型列**：显示 SQL/SHELL/PYTHON 标签
- **数据源列**：显示数据源名称和类型

```vue
<el-table-column prop="dolphinNodeType" label="节点类型" width="100">
  <template #default="{ row }">
    <el-tag v-if="row.dolphinNodeType" :type="getNodeTypeColor(row.dolphinNodeType)">
      {{ row.dolphinNodeType }}
    </el-tag>
    <span v-else>-</span>
  </template>
</el-table-column>

<el-table-column label="数据源" width="150">
  <template #default="{ row }">
    <span v-if="row.datasourceName">
      {{ row.datasourceName }}
      <el-tag size="small" v-if="row.datasourceType">{{ row.datasourceType }}</el-tag>
    </span>
    <span v-else>-</span>
  </template>
</el-table-column>
```

**颜色映射函数**：
```javascript
const getNodeTypeColor = (nodeType) => {
  const colors = {
    'SQL': 'success',      // 绿色
    'SHELL': 'warning',    // 橙色
    'PYTHON': 'primary'    // 蓝色
  }
  return colors[nodeType] || 'info'
}
```

## 使用流程

### 创建 SQL 任务

1. 进入"任务管理" → 点击"新建任务"
2. 填写基本信息：
   - 任务名称：例如 "用户画像计算"
   - 任务编码：例如 "user_profile_calc"
3. 选择任务类型：**批任务**
4. 选择执行引擎：**DolphinScheduler**
5. **选择节点类型：SQL** ⭐ 新增
6. **填写数据源配置**：⭐ 新增
   - 数据源名称：doris_test
   - 数据源类型：DORIS
7. 输入 SQL：
   ```sql
   INSERT INTO dws.user_profile
   SELECT user_id, COUNT(*) as visit_count
   FROM ods.user_events
   GROUP BY user_id
   ```
8. 选择输入表和输出表
9. 点击"提交"

### 创建 SHELL 任务

1. 选择节点类型：**SHELL**
2. 数据源配置字段会隐藏（不需要）
3. 在任务 SQL 框输入 Shell 脚本：
   ```bash
   hdfs dfs -rm -r /tmp/old_data/*
   echo "清理完成"
   ```

### 创建 PYTHON 任务

1. 选择节点类型：**PYTHON**
2. 在任务 SQL 框输入 Python 脚本：
   ```python
   import pandas as pd
   df = pd.read_csv('/data/input.csv')
   print(df.describe())
   ```

## 后端处理逻辑

### DataTaskService.java

**任务发布时的节点类型判断**（第113-136行）：

```java
// 确定节点类型（默认 SHELL）
String nodeType = dataTask.getDolphinNodeType() != null ?
    dataTask.getDolphinNodeType() : "SHELL";
String sqlOrScript;

// SQL 节点：直接使用原始 SQL
if ("SQL".equalsIgnoreCase(nodeType)) {
    sqlOrScript = dataTask.getTaskSql();
} else {
    // SHELL 节点：包装为 Shell 脚本
    sqlOrScript = dolphinSchedulerService.buildShellScript(dataTask.getTaskSql());
}

// 构建任务定义（传递节点类型和数据源信息）
Map<String, Object> definition = dolphinSchedulerService.buildTaskDefinition(
    dataTask.getDolphinTaskCode(),
    version,
    dataTask.getTaskName(),
    dataTask.getTaskDesc(),
    sqlOrScript,
    priority,
    dataTask.getRetryTimes(),
    dataTask.getRetryInterval(),
    dataTask.getTimeoutSeconds(),
    nodeType,                      // ⭐ 传递节点类型
    dataTask.getDatasourceName(),  // ⭐ 传递数据源名称
    dataTask.getDatasourceType()   // ⭐ 传递数据源类型
);
```

### DolphinSchedulerService.java

**buildTaskDefinition 方法**：

```java
public Map<String, Object> buildTaskDefinition(
    long taskCode, int version, String name, String description,
    String rawScriptOrSql, String priority,
    int failRetryTimes, int failRetryInterval, int timeout,
    String taskType,           // SQL/SHELL/PYTHON
    String datasourceName,     // 数据源名称
    String datasourceType      // 数据源类型
) {
    Map<String, Object> taskParams = new HashMap<>();
    taskParams.put("localParams", new ArrayList<>());
    taskParams.put("resourceList", new ArrayList<>());

    if ("SQL".equalsIgnoreCase(taskType)) {
        // SQL 节点特有参数
        taskParams.put("type", datasourceType);        // DORIS/MYSQL 等
        taskParams.put("datasource", datasourceName);  // doris_test
        taskParams.put("sql", rawScriptOrSql);
        taskParams.put("sqlType", rawScriptOrSql.trim().toUpperCase().startsWith("SELECT") ? 1 : 0);
        taskParams.put("displayRows", 10);
    } else {
        // SHELL/PYTHON 节点
        taskParams.put("rawScript", rawScriptOrSql);
    }

    // 构建任务定义
    Map<String, Object> definition = new HashMap<>();
    definition.put("code", taskCode);
    definition.put("taskType", taskType);  // ⭐ 设置任务类型
    definition.put("taskParams", taskParams);
    // ...
    return definition;
}
```

## 数据源配置

### 在 DolphinScheduler UI 中创建数据源

1. 访问：http://localhost:12345/dolphinscheduler
2. 登录：admin / dolphinscheduler123
3. 数据源中心 → 数据源管理 → 创建数据源
4. 填写配置：
   - **数据源名称**：doris_test
   - **数据源类型**：DORIS
   - **地址**：127.0.0.1:9030
   - **数据库**：test_db
   - **用户名/密码**：（根据实际配置）
5. 点击"测试连接" → "保存"

### 注意事项

⚠️ **数据源名称和类型必须与 DolphinScheduler 中的配置完全一致！**

- 前端表单中的 `datasourceName` 必须对应 DolphinScheduler 中已创建的数据源名称
- `datasourceType` 必须与数据源的实际类型匹配
- 否则任务发布时会报错：
  ```
  Can not find any datasource by name xxx and type yyy
  ```

## 文件变更清单

### 前端文件

| 文件 | 变更内容 |
|------|---------|
| `frontend/src/views/tasks/TaskForm.vue` | 新增节点类型、数据源名称、数据源类型字段及验证 |
| `frontend/src/views/tasks/TaskList.vue` | 新增节点类型列、数据源列及颜色函数 |

### 后端文件（已存在，无需修改）

| 文件 | 说明 |
|------|------|
| `backend/src/main/java/com/onedata/portal/service/DataTaskService.java` | 已支持 dolphinNodeType 字段的处理 |
| `backend/src/main/java/com/onedata/portal/service/DolphinSchedulerService.java` | 已支持不同节点类型的参数构建 |
| `backend/src/main/java/com/onedata/portal/entity/DataTask.java` | 实体类已包含相关字段 |

## 测试验证

### 集成测试

运行 `WorkflowLifecycleFullTest.java` 验证 SQL 节点功能：

```bash
cd backend
mvn test -Dtest=WorkflowLifecycleFullTest
```

测试会验证：
1. ✅ 创建 SQL 任务（dolphinNodeType=SQL, datasourceType=DORIS）
2. ✅ 发布到 DolphinScheduler（正确识别为 SQL 节点）
3. ✅ 数据源配置传递正确
4. ✅ 工作流自动上线

### 前端测试

1. 启动前端应用：
   ```bash
   cd frontend
   npm run dev
   ```

2. 访问：http://localhost:5173/tasks

3. 创建测试任务：
   - 节点类型选择：SQL
   - 数据源：doris_test (DORIS)
   - SQL：`SELECT * FROM test_table LIMIT 10`

4. 发布任务

5. 在 DolphinScheduler UI 查看：
   - 工作流定义中应显示 **SQL 节点**（不是 SHELL）
   - 节点详情应包含数据源配置

## 优化建议

### 未来增强

1. **数据源下拉选择**
   - 从后端 API 获取已创建的数据源列表
   - 前端显示为下拉框供用户选择

2. **SQL 编辑器**
   - 集成 Monaco Editor 或 CodeMirror
   - 提供语法高亮和自动补全

3. **数据源测试连接**
   - 在表单中提供"测试连接"按钮
   - 验证数据源配置是否可用

4. **任务模板**
   - 预定义常用任务模板
   - 一键创建标准化任务

## 总结

通过此次增强，数据中台前端现已支持：

✅ **选择 DolphinScheduler 节点类型**（SQL/SHELL/PYTHON）
✅ **配置 SQL 节点的数据源**（名称和类型）
✅ **任务列表清晰显示节点类型和数据源**
✅ **后端正确处理并传递至 DolphinScheduler**

用户现在可以通过前端表单创建真正的 SQL 任务，而不再被默认为 SHELL 任务！
