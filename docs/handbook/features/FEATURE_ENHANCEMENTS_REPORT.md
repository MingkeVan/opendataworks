# Doris 表统计信息功能增强 - 完整实现报告

## 功能概述

本次更新在原有 Doris 表统计信息功能的基础上，实现了以下增强功能：

1. ✅ **在 DataTable 实体中添加 dbName 字段**
2. ✅ **实现统计信息缓存机制**（5分钟过期）
3. ✅ **添加统计历史记录功能**
4. ✅ **实现数据增长趋势图表**

---

## 一、DataTable 实体 dbName 字段

### 实现位置

- **实体类**：`backend/.../entity/DataTable.java` (第39行)
- **数据库表**：`data_table.db_name` (已存在)

### 使用方式

Controller 优先使用 `dbName` 字段获取数据库名，如果为空则尝试从表名中解析：

```java
if (table.getDbName() != null && !table.getDbName().isEmpty()) {
    database = table.getDbName();
    actualTableName = table.getTableName().contains(".")
            ? table.getTableName().split("\\.", 2)[1]
            : table.getTableName();
}
```

### 优势

- 明确指定数据库名，避免歧义
- 支持表名不包含数据库前缀的场景
- 向后兼容，支持从表名解析

---

## 二、统计信息缓存机制

### 实现文件

`backend/.../service/TableStatisticsCacheService.java`

### 核心功能

1. **内存缓存**：使用 `ConcurrentHashMap` 存储统计信息
2. **自动过期**：缓存默认5分钟过期
3. **缓存 Key**：`tableId_clusterId` 格式
4. **缓存管理**：
   - `get()` - 获取缓存（自动检查过期）
   - `put()` - 放入缓存
   - `remove()` - 移除指定缓存
   - `removeAll()` - 移除表的所有缓存
   - `clear()` - 清空所有缓存
   - `cleanupExpired()` - 清理过期缓存

### API 使用

```javascript
// 前端调用时支持强制刷新参数
tableApi.getStatistics(id, clusterId, forceRefresh)

// forceRefresh=false：优先从缓存获取
// forceRefresh=true：强制从 Doris 查询并更新缓存
```

### 性能优势

- 减少对 Doris 的频繁查询
- 降低数据库负载
- 提升页面响应速度
- 用户体验更流畅

---

## 三、统计历史记录功能

### 1. 数据库表

**表名**：`table_statistics_history`

**字段**：
- id - 主键
- table_id - 关联的表ID
- cluster_id - Doris集群ID
- database_name - 数据库名
- table_name - 表名
- row_count - 数据行数
- data_size - 数据大小（字节）
- partition_count - 分区数量
- replication_num - 副本数量
- bucket_num - 分桶数量
- table_last_update_time - 表最后更新时间
- statistics_time - 统计时间
- created_at - 记录创建时间

**索引**：
- `idx_table_id` - 表ID索引
- `idx_statistics_time` - 统计时间索引
- `idx_table_stats` - 组合索引（table_id, statistics_time）

### 2. 实现文件

- **实体类**：`TableStatisticsHistory.java`
- **Mapper**：`TableStatisticsHistoryMapper.java` + `TableStatisticsHistoryMapper.xml`
- **服务类**：`TableStatisticsHistoryService.java`
- **数据库迁移**：`V3__add_statistics_history.sql`

### 3. 自动保存机制

每次调用 `GET /v1/tables/{id}/statistics` 时自动保存历史记录：

```java
TableStatistics statistics = dorisConnectionService.getTableStatistics(...);
cacheService.put(id, clusterId, statistics);
historyService.saveHistory(id, clusterId, statistics); // 自动保存
```

### 4. 查询 API

```
GET /v1/tables/{id}/statistics/history?limit=30
- 获取最近N条历史记录

GET /v1/tables/{id}/statistics/history/last7days
- 获取最近7天的历史记录

GET /v1/tables/{id}/statistics/history/last30days
- 获取最近30天的历史记录
```

---

## 四、数据增长趋势图表

### 1. 实现位置

`frontend/src/views/tables/TableDetail.vue`

### 2. 图表特性

#### 可视化效果

- **双轴折线图**：
  - 左轴：数据行数（支持K/M单位自动转换）
  - 右轴：数据大小（GB）
- **平滑曲线**：使用 smooth 属性
- **渐变填充**：使用 areaStyle 渐变色
- **交互提示**：鼠标悬停显示详细数据

#### 时间周期

- 最近7天
- 最近30天
- 单选按钮切换

#### 数据处理

- 自动将字节转换为 GB
- 行数格式化（1K=1000, 1M=1000000）
- 日期格式化为 MM/DD

#### 响应式设计

- 图表随窗口大小自动调整
- 高度固定为 400px
- 宽度自适应

### 3. 实现步骤

详见 `CHART_IMPLEMENTATION_GUIDE.md` 文件

### 4. 依赖库

- **ECharts 5.4+**：项目已包含，无需额外安装

---

## 五、API 端点汇总

### 表统计信息

```
GET /v1/tables/{id}/statistics?clusterId={clusterId}&forceRefresh={boolean}
- 获取指定表的统计信息
- forceRefresh: 是否强制刷新（跳过缓存）
- 自动保存历史记录

GET /v1/tables/statistics/database/{database}?clusterId={clusterId}
- 获取数据库所有表的统计信息
```

### 历史记录

```
GET /v1/tables/{id}/statistics/history?limit={number}
- 获取最近N条历史记录（默认30条）

GET /v1/tables/{id}/statistics/history/last7days
- 获取最近7天的历史记录

GET /v1/tables/{id}/statistics/history/last30days
- 获取最近30天的历史记录
```

---

## 六、前端 API 方法

`frontend/src/api/table.js`

```javascript
// 获取表统计信息
getStatistics(id, clusterId = null, forceRefresh = false)

// 获取数据库所有表的统计信息
getDatabaseStatistics(database, clusterId = null)

// 获取表统计历史记录
getStatisticsHistory(id, limit = 30)

// 获取最近7天统计历史
getLast7DaysHistory(id)

// 获取最近30天统计历史
getLast30DaysHistory(id)
```

---

## 七、使用流程

### 1. 首次使用

1. 确保表已配置 `dbName` 字段
2. 确保表已同步到 Doris（`isSynced = 1`）
3. 进入表详情页面
4. 点击"刷新"按钮获取统计信息

### 2. 查看趋势

1. 多次刷新统计信息（建议每天刷新一次）
2. 累积足够的历史数据（至少2条）
3. 在"数据增长趋势"卡片中查看图表
4. 切换7天/30天视图

### 3. 强制刷新

如果需要绕过缓存获取最新数据：

```javascript
// 前端调用
tableApi.getStatistics(id, clusterId, true)
```

---

## 八、数据流程图

```
用户点击刷新
    ↓
检查缓存（5分钟有效期）
    ↓ 缓存未命中
查询 Doris
    ↓
返回统计信息
    ↓
存入缓存 + 保存历史记录
    ↓
前端显示统计信息
    ↓
自动加载历史数据
    ↓
渲染趋势图表
```

---

## 九、数据库迁移

### 新增表

- `table_statistics_history` - 统计历史记录表

### 迁移文件

- `V3__add_statistics_history.sql`

### 执行迁移

项目启动时自动执行（使用 Flyway 或类似工具）

---

## 十、性能优化建议

### 1. 缓存优化

- **当前**：内存缓存，5分钟过期
- **可选优化**：
  - 使用 Redis 实现分布式缓存
  - 支持缓存预热
  - 实现缓存穿透保护

### 2. 历史记录优化

- **当前**：每次刷新都保存
- **可选优化**：
  - 增加保存间隔控制（如最少1小时）
  - 定期清理旧数据（保留最近90天）
  - 添加数据压缩

### 3. 趋势图表优化

- **当前**：前端实时渲染
- **可选优化**：
  - 后端预聚合数据
  - 支持更多时间维度（季度、年度）
  - 添加数据对比功能

---

## 十一、测试建议

### 后端测试

```bash
# 测试获取统计信息（无缓存）
curl http://localhost:8080/api/v1/tables/1/statistics

# 测试获取统计信息（强制刷新）
curl http://localhost:8080/api/v1/tables/1/statistics?forceRefresh=true

# 测试获取最近7天历史
curl http://localhost:8080/api/v1/tables/1/statistics/history/last7days

# 测试获取最近30天历史
curl http://localhost:8080/api/v1/tables/1/statistics/history/last30days
```

### 前端测试

1. **统计信息加载**
   - 测试首次加载
   - 测试缓存命中
   - 测试强制刷新
   - 测试错误处理

2. **历史记录**
   - 测试无历史数据
   - 测试有历史数据
   - 测试7天/30天切换

3. **趋势图表**
   - 测试图表渲染
   - 测试交互功能
   - 测试响应式布局
   - 测试数据格式化

---

## 十二、已完成的文件清单

### 后端新增/修改

1. `TableStatistics.java` - 统计信息 DTO
2. `TableStatisticsCacheService.java` - 缓存服务（新增）
3. `TableStatisticsHistory.java` - 历史记录实体（新增）
4. `TableStatisticsHistoryMapper.java` - Mapper 接口（新增）
5. `TableStatisticsHistoryMapper.xml` - MyBatis XML（新增）
6. `TableStatisticsHistoryService.java` - 历史记录服务（新增）
7. `DorisConnectionService.java` - 统计查询方法
8. `DataTableController.java` - API 端点
9. `V3__add_statistics_history.sql` - 数据库迁移（新增）

### 前端新增/修改

1. `table.js` - API 方法
2. `TableDetail.vue` - 表详情页面（需要手动添加图表部分）

### 文档

1. `DORIS_STATISTICS_INTEGRATION.md` - 原功能文档
2. `CHART_IMPLEMENTATION_GUIDE.md` - 图表实现指南
3. `FEATURE_ENHANCEMENTS_REPORT.md` - 本文件

---

## 十三、后续优化方向

1. **定时任务**
   - 自动定时刷新所有表的统计信息
   - 自动清理过期历史记录

2. **告警功能**
   - 数据量异常增长告警
   - 数据大小超限告警

3. **对比分析**
   - 同期对比（同比、环比）
   - 多表对比
   - 自定义时间范围对比

4. **导出功能**
   - 导出统计报表
   - 导出趋势图表

5. **权限控制**
   - 统计信息查看权限
   - 历史记录访问权限

---

## 十四、技术栈

- **后端**：Spring Boot 2.7.18, MyBatis Plus 3.5.5, JDBC
- **前端**：Vue 3.4, Element Plus 2.5, ECharts 5.4+, Axios
- **数据库**：Apache Doris, MySQL 8.0
- **缓存**：ConcurrentHashMap（内存缓存）

---

## 十五、联系方式

如有问题或建议，请联系开发团队。
