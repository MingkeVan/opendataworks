# Doris 表统计信息集成说明

## 功能概述

本功能实现了与 Apache Doris 的集成，可以获取表的统计信息并在前端表详情页面展示，包括：

- 数据行数
- 数据大小
- 分区数量
- 表创建时间
- 数据更新时间
- 副本数、分桶数等配置信息

## 实现的功能

### 后端实现

#### 1. DTO 数据模型

**文件位置**: `backend/src/main/java/com/onedata/portal/dto/TableStatistics.java`

包含的字段：
- `databaseName`: 数据库名
- `tableName`: 表名
- `tableType`: 表类型
- `tableComment`: 表注释
- `createTime`: 创建时间
- `lastUpdateTime`: 最后更新时间
- `rowCount`: 数据行数
- `dataSize`: 数据大小（字节）
- `dataSizeReadable`: 数据大小（可读格式）
- `replicationNum`: 副本数量
- `partitionCount`: 分区数量
- `bucketNum`: 分桶数量
- `engine`: 表引擎
- `available`: 是否可用
- `lastCheckTime`: 统计信息最后收集时间

#### 2. 服务层扩展

**文件位置**: `backend/src/main/java/com/onedata/portal/service/DorisConnectionService.java`

新增方法：

```java
// 获取单个表的统计信息
public TableStatistics getTableStatistics(Long clusterId, String database, String tableName)

// 获取数据库所有表的统计信息
public List<TableStatistics> getAllTableStatistics(Long clusterId, String database)

// 格式化字节数为可读格式
private String formatBytes(long bytes)

// 丰富表详细信息（分区数、副本数、分桶数）
private void enrichTableDetails(Connection connection, String database, String tableName, TableStatistics stats)
```

实现原理：
- 通过查询 Doris 的 `information_schema.tables` 获取基础表信息
- 通过查询 `information_schema.partitions` 获取分区信息
- 通过解析 `SHOW CREATE TABLE` 获取副本数和分桶数

#### 3. API 接口

**文件位置**: `backend/src/main/java/com/onedata/portal/controller/DataTableController.java`

新增端点：

```
GET /v1/tables/{id}/statistics?clusterId={clusterId}
- 获取指定表的统计信息
- 参数：
  - id: 表ID（路径参数）
  - clusterId: Doris集群ID（可选，不传则使用默认集群）
- 返回：TableStatistics 对象

GET /v1/tables/statistics/database/{database}?clusterId={clusterId}
- 获取数据库中所有表的统计信息
- 参数：
  - database: 数据库名（路径参数）
  - clusterId: Doris集群ID（可选）
- 返回：TableStatistics 列表
```

### 前端实现

#### 1. API 封装

**文件位置**: `frontend/src/api/table.js`

新增方法：

```javascript
// 获取表统计信息
getStatistics(id, clusterId = null)

// 获取数据库所有表的统计信息
getDatabaseStatistics(database, clusterId = null)
```

#### 2. 表详情页面

**文件位置**: `frontend/src/views/tables/TableDetail.vue`

新增内容：

1. **统计信息卡片**：位于 Doris 配置卡片之后
2. **数据展示**：
   - 三个高亮显示的统计卡片：数据行数、数据大小、分区数量
   - 详细信息表格：数据库、表类型、创建时间、更新时间、副本数、分桶数、引擎、状态
3. **刷新功能**：点击"刷新"按钮获取最新统计信息
4. **智能提示**：
   - 表未同步到 Doris 时显示提示信息
   - 加载失败时显示错误信息
   - 首次加载时显示空状态

新增方法：

```javascript
// 刷新统计信息
refreshStatistics()

// 格式化数字（带千分位）
formatNumber(num)

// 格式化日期时间
formatDateTime(dateTime)
```

## 使用说明

### 前置条件

1. 已配置 Doris 集群连接信息
2. 表已同步到 Doris（`isSynced = 1`）

### 使用步骤

1. 进入表详情页面（`/tables/{id}`）
2. 滚动到"表统计信息"卡片
3. 点击"刷新"按钮获取统计信息
4. 查看数据行数、数据大小、分区数量等信息

### 注意事项

1. **表名格式**：
   - 如果表名包含数据库名（格式：`database.table_name`），系统会自动解析
   - 如果表名不包含数据库名，默认使用 `default_db`（可在代码中调整）

2. **数据库配置**：
   - 建议在 `DataTable` 实体中添加 `dbName` 字段来明确指定数据库
   - 或者根据项目实际情况调整 Controller 中的数据库解析逻辑

3. **性能考虑**：
   - 统计信息按需加载（点击刷新按钮才查询）
   - 避免自动刷新，减少对 Doris 的压力

4. **错误处理**：
   - 表不存在或查询失败时会显示错误提示
   - 不会影响页面其他功能的正常使用

## 后续优化建议

1. **缓存机制**：
   - 添加统计信息缓存，减少对 Doris 的频繁查询
   - 设置缓存过期时间（如 5 分钟）

2. **自动刷新**：
   - 添加定时自动刷新选项
   - 添加"最后更新时间"显示

3. **数据库字段**：
   - 在 `DataTable` 中添加 `dbName` 字段
   - 在表单中允许用户选择或输入数据库名

4. **批量查询**：
   - 在表列表页面添加批量获取统计信息功能
   - 优化数据库查询批量表的统计信息

5. **图表展示**：
   - 添加数据增长趋势图
   - 添加分区数据分布图
   - 添加表大小排行榜

6. **历史记录**：
   - 保存历史统计数据
   - 支持统计信息的历史对比

## 测试建议

### 后端测试

```bash
# 测试获取表统计信息
curl http://localhost:8080/api/v1/tables/1/statistics

# 测试获取数据库所有表的统计信息
curl http://localhost:8080/api/v1/tables/statistics/database/test_db
```

### 前端测试

1. 测试已同步表的统计信息加载
2. 测试未同步表的提示信息
3. 测试刷新功能
4. 测试错误处理（表不存在、网络错误等）
5. 测试数字格式化和日期格式化
6. 测试响应式布局

## 相关文件清单

### 后端文件

- `backend/src/main/java/com/onedata/portal/dto/TableStatistics.java` - 数据模型
- `backend/src/main/java/com/onedata/portal/service/DorisConnectionService.java` - 服务层
- `backend/src/main/java/com/onedata/portal/controller/DataTableController.java` - API 控制器

### 前端文件

- `frontend/src/api/table.js` - API 封装
- `frontend/src/views/tables/TableDetail.vue` - 表详情页面

## 技术栈

- **后端**：Spring Boot 2.7.18, MyBatis Plus, JDBC
- **前端**：Vue 3.4, Element Plus 2.5, Axios
- **数据库**：Apache Doris, MySQL 8.0

## 联系方式

如有问题或建议，请联系开发团队。
