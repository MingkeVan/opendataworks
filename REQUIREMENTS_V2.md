# 数据门户 V2 需求设计文档

## 📋 需求概述

基于数据中台规范,增强现有数据门户系统,支持 Doris 表的创建、管理、任务关联和血缘关系追踪。

---

## 🎯 用户故事

### 用户故事 1: 规范化表创建
**作为** 数据开发人员
**我希望** 按照数据中台规范创建 Doris 表
**以便** 表名符合统一命名标准,并自动配置 Doris 表参数

**验收标准**:
- ✅ 支持按照分层、域、标识等元素生成规范表名
- ✅ 支持列定义(名称、类型、注释)
- ✅ 支持 Doris 表参数配置(副本数、分桶数、分区字段等)
- ✅ 生成 Doris DDL 并预览
- ✅ 支持直接在 Doris 中创建表

### 用户故事 2: 任务与表自动关联
**作为** 数据开发人员
**我希望** 创建写入任务时自动识别上下游表
**以便** 快速建立表与任务的关联关系,并查看血缘关系

**验收标准**:
- ✅ 根据任务 SQL 自动识别引用的表名
- ✅ 自动关联上游表(FROM/JOIN 的表)和下游表(INSERT INTO 的表)
- ✅ 在表详情页展示关联的读取任务和写入任务
- ✅ 在表详情页展示上游表列表和下游表列表
- ✅ 支持表级别血缘关系和全局血缘关系可视化

---

## 📊 表命名规范

### 命名格式

```
{数据分层}_{业务域}_{数据域}_{自定义标识}_{统计周期}_{更新类型}
```

**示例**: `dwd_tech_ops_cmp_performance_10m_di`

### 命名元素详解

| 元素 | 说明 | 示例值 | 是否必填 |
|-----|------|--------|---------|
| **数据分层** | 数据仓库分层 | `ods`, `dwd`, `dim`, `dws`, `ads` | ✅ 必填 |
| **业务域** | 业务板块/系统 | `tech`, `crm`, `trade`, `finance` | ✅ 必填 |
| **数据域** | 数据分类/主题 | `ops`, `user`, `order`, `product` | ✅ 必填 |
| **自定义标识** | 表的业务含义 | `cmp_performance`, `user_detail`, `order_summary` | ✅ 必填 |
| **统计周期** | 数据统计粒度 | `10m`, `1h`, `1d`, `realtime` | ⚪ 可选 |
| **更新类型** | 增量/全量标识 | `di` (每日增量), `df` (每日全量), `hi` (小时增量) | ✅ 必填 |

### 业务域、数据域、主题域关系

#### **业务域 (Business Domain)**
- **定义**: 按照业务板块或业务系统进行划分
- **视角**: 组织架构视角
- **示例**:
  - `tech` - 技术域
  - `crm` - 客户关系管理域
  - `trade` - 交易域
  - `finance` - 财务域
  - `logistics` - 物流域

#### **数据域 (Data Domain)**
- **定义**: 面向业务分析,对业务过程或维度进行抽象的集合
- **视角**: 数据分类视角(自下而上,从数据角度)
- **与业务域关系**: 一个业务域可以包含多个数据域
- **示例**:
  - `ops` - 运维数据域
  - `user` - 用户数据域
  - `order` - 订单数据域
  - `product` - 商品数据域
  - `payment` - 支付数据域

#### **主题域 (Subject Domain)**
- **定义**: 面向分析场景,联系紧密的数据主题集合
- **视角**: 业务分析视角(自上而下,从需求角度)
- **与数据域关系**: 主题域和数据域都是对数据的分类,一个是业务视角,一个是数据视角
- **示例**:
  - 客户主题域: 客户画像、客户行为、客户价值
  - 营销主题域: 营销活动、营销效果、营销ROI
  - 财务主题域: 收入、成本、利润

**关系图解**:
```
业务域 (tech)
  ├── 数据域 (ops)
  │     ├── 表: dwd_tech_ops_cmp_performance_10m_di
  │     └── 表: dwd_tech_ops_server_metrics_1h_di
  └── 数据域 (monitor)
        ├── 表: dwd_tech_monitor_alert_realtime_di
        └── 表: dwd_tech_monitor_log_1d_df

主题域 (运维监控主题)
  ├── 包含: tech.ops 的部分表
  ├── 包含: tech.monitor 的部分表
  └── 服务于: 运维大盘、告警分析等业务场景
```

### 更新类型标识

| 标识 | 含义 | 说明 |
|-----|------|------|
| `di` | Daily Increment | 每日增量 |
| `df` | Daily Full | 每日全量 |
| `hi` | Hourly Increment | 小时增量 |
| `hf` | Hourly Full | 小时全量 |
| `ri` | Realtime Increment | 实时增量 |

### 统计周期标识

| 标识 | 含义 |
|-----|------|
| `10m` | 10分钟 |
| `30m` | 30分钟 |
| `1h` | 1小时 |
| `1d` | 1天 |
| `realtime` | 实时 |

---

## 🏗️ 数据模型设计

### 1. 扩展 data_table 表结构

需要在现有 `data_table` 表中增加以下字段:

```sql
ALTER TABLE data_table
ADD COLUMN business_domain VARCHAR(50) COMMENT '业务域' AFTER layer,
ADD COLUMN data_domain VARCHAR(50) COMMENT '数据域' AFTER business_domain,
ADD COLUMN custom_identifier VARCHAR(100) COMMENT '自定义表标识' AFTER data_domain,
ADD COLUMN statistics_cycle VARCHAR(20) COMMENT '统计周期(如: 10m, 1h, 1d)' AFTER custom_identifier,
ADD COLUMN update_type VARCHAR(10) COMMENT '更新类型(di/df/hi/hf/ri)' AFTER statistics_cycle,
ADD COLUMN table_model VARCHAR(20) COMMENT 'Doris表模型(DUPLICATE/AGGREGATE/UNIQUE)' AFTER update_type,
ADD COLUMN bucket_num INT COMMENT '分桶数' AFTER table_model,
ADD COLUMN replica_num INT DEFAULT 3 COMMENT '副本数' AFTER bucket_num,
ADD COLUMN partition_column VARCHAR(100) COMMENT '分区字段' AFTER replica_num,
ADD COLUMN distribution_column VARCHAR(100) COMMENT '分桶字段' AFTER partition_column,
ADD COLUMN key_columns VARCHAR(500) COMMENT '主键列(逗号分隔)' AFTER distribution_column,
ADD COLUMN doris_ddl TEXT COMMENT '生成的Doris DDL' AFTER key_columns,
ADD COLUMN is_synced TINYINT DEFAULT 0 COMMENT '是否已同步到Doris(0-未同步,1-已同步)' AFTER doris_ddl,
ADD COLUMN sync_time DATETIME COMMENT 'Doris同步时间' AFTER is_synced;
```

### 2. 新增 data_domain 表 (数据域配置)

```sql
CREATE TABLE data_domain (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  domain_code VARCHAR(50) NOT NULL COMMENT '数据域代码',
  domain_name VARCHAR(100) NOT NULL COMMENT '数据域名称',
  business_domain VARCHAR(50) COMMENT '所属业务域',
  description VARCHAR(500) COMMENT '描述',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_domain_code (domain_code)
) COMMENT '数据域配置表';
```

### 3. 新增 business_domain 表 (业务域配置)

```sql
CREATE TABLE business_domain (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  domain_code VARCHAR(50) NOT NULL COMMENT '业务域代码',
  domain_name VARCHAR(100) NOT NULL COMMENT '业务域名称',
  description VARCHAR(500) COMMENT '描述',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_domain_code (domain_code)
) COMMENT '业务域配置表';
```

### 4. 新增 doris_cluster 表 (Doris集群配置)

```sql
CREATE TABLE doris_cluster (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cluster_name VARCHAR(100) NOT NULL COMMENT '集群名称',
  fe_host VARCHAR(100) NOT NULL COMMENT 'FE Host',
  fe_port INT NOT NULL DEFAULT 9030 COMMENT 'FE MySQL端口',
  username VARCHAR(50) NOT NULL COMMENT '用户名',
  password VARCHAR(200) NOT NULL COMMENT '密码',
  is_default TINYINT DEFAULT 0 COMMENT '是否默认集群',
  status VARCHAR(20) DEFAULT 'active' COMMENT '状态',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
) COMMENT 'Doris集群配置表';
```

### 5. 扩展 data_lineage 表 (增加任务关联)

```sql
ALTER TABLE data_lineage
ADD COLUMN task_id BIGINT COMMENT '关联的任务ID' AFTER output_table_id,
ADD COLUMN lineage_type VARCHAR(20) DEFAULT 'table' COMMENT '血缘类型(table-表级/field-字段级)' AFTER task_id;
```

### 6. 新增 table_task_relation 表 (表与任务关联关系)

```sql
CREATE TABLE table_task_relation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  table_id BIGINT NOT NULL COMMENT '表ID',
  task_id BIGINT NOT NULL COMMENT '任务ID',
  relation_type VARCHAR(20) NOT NULL COMMENT '关联类型(read-读取/write-写入)',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_table_task (table_id, task_id, relation_type)
) COMMENT '表与任务关联关系表';
```

---

## 🎨 功能设计

### 功能 1: 规范化表创建

#### 1.1 表单设计

**基本信息**:
- 数据分层: 下拉选择 (ODS/DWD/DIM/DWS/ADS)
- 业务域: 下拉选择 (从 business_domain 表加载)
- 数据域: 下拉选择 (根据业务域筛选,从 data_domain 表加载)
- 自定义标识: 文本输入 (支持字母、数字、下划线)
- 统计周期: 下拉选择或输入 (10m/30m/1h/1d/realtime/空)
- 更新类型: 下拉选择 (di/df/hi/hf/ri)
- 数据库名: 文本输入
- 表注释: 文本输入

**自动生成表名预览**:
```
实时预览: dwd_tech_ops_cmp_performance_10m_di
```

**列定义**:
- 列名: 文本输入
- 数据类型: 下拉选择 (支持 Doris 所有类型)
  - 数值: TINYINT, SMALLINT, INT, BIGINT, LARGEINT, FLOAT, DOUBLE, DECIMAL
  - 字符串: CHAR, VARCHAR, STRING, TEXT
  - 日期: DATE, DATETIME, TIMESTAMP
  - 其他: BOOLEAN, JSON, ARRAY, MAP
- 类型参数: 文本输入 (如 VARCHAR(255), DECIMAL(12,2))
- 是否可空: 复选框
- 默认值: 文本输入
- 注释: 文本输入
- 操作: 添加/删除行

**Doris 表参数**:
- 表模型: 下拉选择 (DUPLICATE KEY / AGGREGATE KEY / UNIQUE KEY)
  - 根据数据分层自动推荐:
    - ODS/DWD: 默认 DUPLICATE KEY
    - DIM: 默认 UNIQUE KEY
    - DWS/ADS: 默认 AGGREGATE KEY
- 主键列: 多选 (从已定义的列中选择)
- 分区字段: 下拉选择 (DATE/DATETIME 类型的列)
- 分桶字段: 下拉选择 (从已定义的列中选择,支持多选)
- 分桶数: 数字输入 (1-128,根据数据量提供建议)
- 副本数: 下拉选择 (1/3,默认3)

**Doris 集群**:
- 选择集群: 下拉选择 (从 doris_cluster 表加载)

#### 1.2 DDL 生成逻辑

根据用户输入生成 Doris DDL:

```sql
CREATE TABLE `{db_name}`.`{table_name}` (
  {column_definitions}
) ENGINE=OLAP
{table_model}({key_columns})
COMMENT '{table_comment}'
[PARTITION BY RANGE({partition_column}) (...)]
DISTRIBUTED BY HASH({distribution_columns}) BUCKETS {bucket_num}
PROPERTIES (
  "replication_num" = "{replica_num}",
  "storage_format" = "V2",
  "compression" = "LZ4"
);
```

#### 1.3 流程设计

1. **填写表单** → 自动生成表名预览
2. **定义列** → 验证数据类型和参数
3. **配置 Doris 参数** → 根据分层自动推荐
4. **生成 DDL** → 预览 SQL
5. **用户确认** → 执行以下操作:
   - 保存表元数据到 `data_table`
   - 执行 DDL 在 Doris 中创建表
   - 更新 `is_synced` 为 1
6. **创建成功** → 跳转到表详情页

---

### 功能 2: Doris 表同步

#### 2.1 同步策略

**定时同步** (推荐):
- 每天凌晨 2:00 自动同步
- 从 Doris 集群获取所有数据库和表
- 对比本地 `data_table` 表:
  - 新增的表: 自动导入元数据
  - 已存在的表: 更新字段信息
  - 已删除的表: 标记为已删除

**手动同步**:
- 在表管理页面提供"同步 Doris 表"按钮
- 支持全量同步和增量同步

#### 2.2 同步逻辑

```java
// 伪代码
1. 连接 Doris 集群 (JDBC)
2. 执行 SHOW DATABASES
3. 对每个数据库:
   - 执行 SHOW TABLES
   - 对每张表:
     - 执行 SHOW CREATE TABLE {table_name}
     - 解析 DDL 获取:
       - 列信息 (名称、类型、注释)
       - 表模型 (KEY 类型)
       - 分区信息
       - 分桶信息
       - 副本数
     - 尝试解析表名 (匹配命名规范)
     - 保存或更新到 data_table
4. 返回同步结果统计
```

#### 2.3 表名解析

对于从 Doris 同步的表,尝试解析表名:

```java
// 正则匹配: ^(ods|dwd|dim|dws|ads)_([a-z]+)_([a-z]+)_(.+?)_?([0-9]+[mhd]|realtime)?_(di|df|hi|hf|ri)$
// 示例: dwd_tech_ops_cmp_performance_10m_di
// 分组:
//   1: dwd (数据分层)
//   2: tech (业务域)
//   3: ops (数据域)
//   4: cmp_performance (自定义标识)
//   5: 10m (统计周期)
//   6: di (更新类型)
```

如果无法解析,则:
- 数据分层: 根据数据库名推断 (如 doris_ods → ODS)
- 其他字段: 留空,允许用户手动补充

---

### 功能 3: 任务创建与表关联

#### 3.1 SQL 表名匹配逻辑

当用户创建/编辑任务时:

1. **提取 SQL 中的表名**:
   ```java
   // 简化的正则匹配
   // FROM/JOIN 后的表名 → 上游表
   Pattern upstreamPattern = Pattern.compile(
     "\\b(?:FROM|JOIN)\\s+([a-z0-9_]+\\.)?([a-z0-9_]+)",
     Pattern.CASE_INSENSITIVE
   );

   // INSERT INTO 后的表名 → 下游表
   Pattern downstreamPattern = Pattern.compile(
     "\\bINSERT\\s+INTO\\s+([a-z0-9_]+\\.)?([a-z0-9_]+)",
     Pattern.CASE_INSENSITIVE
   );
   ```

2. **模糊匹配表资产**:
   ```java
   // 在 data_table 中查找匹配的表
   // 优先级:
   //   1. 完全匹配 (db_name.table_name)
   //   2. 表名匹配 (table_name)
   //   3. 模糊匹配 (LIKE '%table_name%')
   ```

3. **自动建立关联**:
   ```java
   // 保存到 table_task_relation
   for (upstreamTable : upstreamTables) {
     insert into table_task_relation (table_id, task_id, relation_type)
     values (upstreamTable.id, task.id, 'read');
   }

   for (downstreamTable : downstreamTables) {
     insert into table_task_relation (table_id, task_id, relation_type)
     values (downstreamTable.id, task.id, 'write');
   }
   ```

4. **保存血缘关系**:
   ```java
   // 保存到 data_lineage
   for (upstreamTable : upstreamTables) {
     for (downstreamTable : downstreamTables) {
       insert into data_lineage (input_table_id, output_table_id, task_id, lineage_type)
       values (upstreamTable.id, downstreamTable.id, task.id, 'table');
     }
   }
   ```

#### 3.2 表未匹配的处理

如果 SQL 中的表在系统中不存在:
- **提示用户**: "以下表未在系统中找到: xxx, yyy"
- **提供操作**:
  - 忽略 (继续创建任务,不建立关联)
  - 手动选择 (从现有表中选择)
  - 创建新表 (跳转到表创建页面)

---

### 功能 4: 表详情页增强

#### 4.1 新增展示内容

**关联任务**:
```
┌─ 写入任务 (2)
│  ├─ [任务1] dwd_tech_ops_cmp_performance_10m_di 数据同步
│  │   状态: 运行中 | 最后执行: 2025-10-17 12:00:00
│  └─ [任务2] dwd_tech_ops 实时同步
│      状态: 成功 | 最后执行: 2025-10-17 11:50:00
│
└─ 读取任务 (5)
   ├─ [任务3] dws_tech_ops_summary_1d_df 汇总统计
   ├─ [任务4] ads_tech_ops_dashboard_1d_df 大盘数据
   └─ ... (查看全部)
```

**上下游表**:
```
┌─ 上游表 (3)
│  ├─ ods_tech_ops_raw_10m_di (原始数据表)
│  ├─ dim_tech_server (服务器维度表)
│  └─ dim_tech_component (组件维度表)
│
└─ 下游表 (2)
   ├─ dws_tech_ops_summary_1d_df (汇总表)
   └─ ads_tech_ops_dashboard_1d_df (应用层表)
```

**血缘关系可视化**:
- 点击"查看血缘关系" → 跳转到血缘关系页面,自动定位到当前表

#### 4.2 API 设计

```java
// 获取表的关联任务
GET /api/v1/tables/{id}/tasks
Response:
{
  "writeTasks": [
    {
      "id": 1,
      "taskName": "xxx",
      "status": "running",
      "lastExecuted": "2025-10-17 12:00:00"
    }
  ],
  "readTasks": [...]
}

// 获取表的上下游
GET /api/v1/tables/{id}/lineage
Response:
{
  "upstreamTables": [
    {
      "id": 1,
      "tableName": "ods_tech_ops_raw_10m_di",
      "tableComment": "原始数据表",
      "layer": "ODS"
    }
  ],
  "downstreamTables": [...]
}
```

---

### 功能 5: 血缘关系增强

#### 5.1 表级血缘 vs 全局血缘

**表级血缘**:
- 入口: 表详情页 → "查看血缘关系"
- 展示: 以当前表为中心,展示直接上下游(1层)
- 交互: 点击其他表可切换中心表

**全局血缘**:
- 入口: 导航菜单 → "血缘关系"
- 展示: 所有表的血缘关系图(支持筛选)
- 筛选条件:
  - 数据分层 (ODS/DWD/DIM/DWS/ADS)
  - 业务域
  - 数据域
  - 关键词搜索

#### 5.2 血缘图优化

**节点样式**:
- 不同分层使用不同颜色:
  - ODS: 蓝色 #409EFF
  - DWD: 绿色 #67C23A
  - DIM: 橙色 #E6A23C
  - DWS: 红色 #F56C6C
  - ADS: 紫色 #9B59B6

**节点信息**:
- 表名
- 表注释
- 关联任务数量
- 悬浮显示详情

**连线信息**:
- 显示关联的任务名称
- 点击连线高亮任务路径

---

## 🔄 实施路径

### Phase 1: 数据模型升级 (优先级: 高)
1. ✅ 扩展 data_table 表
2. ✅ 创建 business_domain, data_domain 表
3. ✅ 创建 doris_cluster 表
4. ✅ 创建 table_task_relation 表
5. ✅ 扩展 data_lineage 表
6. ✅ 初始化业务域、数据域基础数据

### Phase 2: 表创建功能 (优先级: 高)
1. ✅ 后端: 表创建 API (生成 DDL + 执行)
2. ✅ 后端: Doris JDBC 连接管理
3. ✅ 前端: 表创建表单页面
4. ✅ 前端: 表名自动生成预览
5. ✅ 前端: DDL 预览和执行

### Phase 3: Doris 表同步 (优先级: 高)
1. ✅ 后端: Doris 表同步服务
2. ✅ 后端: 定时任务配置
3. ✅ 后端: 表名解析逻辑
4. ✅ 前端: 手动同步按钮

### Phase 4: 任务表关联 (优先级: 高)
1. ✅ 后端: SQL 表名提取逻辑
2. ✅ 后端: 表名模糊匹配
3. ✅ 后端: 自动建立关联关系
4. ✅ 前端: 任务创建/编辑时显示匹配的表
5. ✅ 前端: 未匹配表的处理提示

### Phase 5: 表详情增强 (优先级: 中)
1. ✅ 后端: 获取关联任务 API
2. ✅ 后端: 获取上下游表 API
3. ✅ 前端: 表详情页增强
4. ✅ 前端: 关联任务列表展示
5. ✅ 前端: 上下游表列表展示

### Phase 6: 血缘关系增强 (优先级: 中)
1. ✅ 前端: 表级血缘关系页面
2. ✅ 前端: 全局血缘关系筛选
3. ✅ 前端: 血缘图样式优化
4. ✅ 前端: 连线任务信息展示

---

## 📝 技术实现要点

### 1. Doris JDBC 连接

```java
// Doris JDBC URL
jdbc:mysql://{fe_host}:{fe_port}/{database}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true

// 驱动类
com.mysql.cj.jdbc.Driver
```

### 2. Doris 数据类型映射

| Doris 类型 | Java 类型 | 前端展示 |
|-----------|----------|---------|
| TINYINT | Byte | TINYINT |
| SMALLINT | Short | SMALLINT |
| INT | Integer | INT |
| BIGINT | Long | BIGINT |
| LARGEINT | BigInteger | LARGEINT |
| FLOAT | Float | FLOAT |
| DOUBLE | Double | DOUBLE |
| DECIMAL(P,S) | BigDecimal | DECIMAL(P,S) |
| CHAR(N) | String | CHAR(N) |
| VARCHAR(N) | String | VARCHAR(N) |
| STRING | String | STRING |
| DATE | LocalDate | DATE |
| DATETIME | LocalDateTime | DATETIME |
| BOOLEAN | Boolean | BOOLEAN |
| JSON | String | JSON |

### 3. SQL 表名提取正则

```java
// 上游表 (FROM/JOIN)
(?:FROM|JOIN)\s+(?:([a-z0-9_]+)\.)?([a-z0-9_]+)

// 下游表 (INSERT INTO)
INSERT\s+INTO\s+(?:([a-z0-9_]+)\.)?([a-z0-9_]+)

// 处理:
// - 忽略大小写
// - 支持 db.table 和 table 两种格式
// - 去除别名 (AS xxx)
```

### 4. 表名生成逻辑

```java
public String generateTableName(TableNameParams params) {
    StringBuilder tableName = new StringBuilder();

    // 1. 数据分层 (必填)
    tableName.append(params.getLayer().toLowerCase());

    // 2. 业务域 (必填)
    tableName.append("_").append(params.getBusinessDomain());

    // 3. 数据域 (必填)
    tableName.append("_").append(params.getDataDomain());

    // 4. 自定义标识 (必填)
    tableName.append("_").append(params.getCustomIdentifier());

    // 5. 统计周期 (可选)
    if (params.getStatisticsCycle() != null) {
        tableName.append("_").append(params.getStatisticsCycle());
    }

    // 6. 更新类型 (必填)
    tableName.append("_").append(params.getUpdateType());

    return tableName.toString();
}
```

---

## 🎯 成功指标

1. **表创建效率**:
   - 平均表创建时间 < 2分钟
   - DDL 生成准确率 > 95%

2. **表同步准确性**:
   - Doris 表同步成功率 > 99%
   - 表名解析准确率 > 80%

3. **任务关联准确性**:
   - SQL 表名提取准确率 > 90%
   - 表匹配成功率 > 85%

4. **用户满意度**:
   - 血缘关系展示清晰度评分 > 4.5/5
   - 功能易用性评分 > 4.0/5

---

## ❓ 待确认事项

1. **业务域和数据域初始数据**: 需要提供初始的业务域和数据域列表
2. **Doris 集群信息**: 需要提供 Doris 集群的连接信息(FE Host, Port, Username, Password)
3. **血缘关系层级**: 全局血缘关系是否需要支持多层追溯(目前设计为仅1层)?
4. **权限控制**: 是否需要对表创建、Doris 同步等操作进行权限控制?

---

**文档版本**: V2.0
**创建时间**: 2025-10-17
**最后更新**: 2025-10-17
