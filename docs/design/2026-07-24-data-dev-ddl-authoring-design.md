# 数据开发 DDL 规范与建表工具 Design

## Context

数据开发全流程(`opendataworks-data-dev` 技能 + portal MCP 写工具)当前能完成
「生成/润色 SQL → 建任务 → 组装工作流 → 发布/上线 → 配调度」,但**建表环节是断的**:

- portal MCP **没有建表工具**(只有 create/update task、create/update workflow、
  publish、schedule)。新表的建表 DDL 由模型凭通用知识自由生成,只能落进任务 SQL
  或以文本交给用户手动执行。
- `opendataworks-data-dev` 技能只有命名与校验规则(`assets/dev-policies.json`)和任务
  字段(`reference/10-task-fields.md`),**没有任何引擎建表规范**。
- 结果:模型生成的 DDL 常不满足目标引擎(尤以 **Doris** 为甚)的建表规范——缺
  `DISTRIBUTED BY ... BUCKETS`、`replication_num`、表模型(Duplicate/Aggregate/Unique)、
  分区等,与平台实际建表约定不一致。

而平台后端**已有引擎感知的建表能力,且是建表约定的权威来源**:

- `service/TableCreateService`
  - `preview(request)`:生成表名 + 建表 DDL(只读,不执行)。
  - `create(request)`(`@Transactional`):校验 → 构建 DDL(或用传入的 `dorisDdl`)→
    写元数据 → 在引擎执行建表 → 记录版本。
  - 当前**仅 Doris**:`tableEngineHandlerRegistry.require(DatasourceType.DORIS)`。
- `service/table/TableEngineHandler`(引擎抽象)+ `DorisTableEngineHandler.buildCreateDdl()`
  编码的 Doris 真实约定:
  - `CREATE TABLE \`db\`.\`t\` (...) ENGINE=OLAP`
  - 表模型 `DUPLICATE`(默认/明细)`/AGGREGATE/UNIQUE` + `KEY(col, ...)`,KEY 列前置
  - `PARTITION BY RANGE(col) ()`
  - `DISTRIBUTED BY HASH(col, ...) BUCKETS n`(`bucketNum` 默认 **10**)
  - `PROPERTIES("replication_num"="3"(默认), "storage_format"="V2", "compression"="LZ4")`
- `dto/TableCreateRequest`:结构化字段(表名由 layer/businessDomain/dataDomain/
  customIdentifier/statisticsCycle/updateType 组件经 `TableNameGeneratorService` 生成;
  columns、tableModel、keyColumns、distributionColumns、bucketNum、replicaNum、
  partitionColumn、tableComment、dorisClusterId、syncToDoris、可选 dorisDdl)。
- AI 面向端点层:portal-mcp 的 `backend_client.py` 统一走 `/v1/ai/*`(如 `/v1/ai/task`、
  `/v1/ai/workflow`、`/v1/ai/metadata/ddl`)→ AI 控制器 → `agentapi/service/BackendAgent*Service`
  桥 → 领域服务。

## Problem

两个独立缺口:

1. **知识缺口**:模型没有引擎建表规范可依,生成 DDL 不合规(Doris 尤甚;MySQL 也需
   规范约束)。
2. **能力缺口**:即便 DDL 正确,数据开发会话也没有「执行建表」的受控工具,断了
   「需求 → 建表 → 建任务 → 工作流 → 发布」的闭环。

## Goal

- **G1(第一优先)**:在 `opendataworks-data-dev` 技能内补一份**引擎感知的建表规范
  reference(Doris + MySQL)**,对齐后端 `DorisTableEngineHandler` 的真实约定,作为模型
  生成/校验 DDL 的单一依据。
- **G2(第二优先)**:新增受控**建表工具 `portal_create_table`**,复用
  `TableCreateService` 在目标引擎执行 DDL;经统一权限门确认(**批准即执行,无需附言**),
  接入 data-dev 技能 playbook,补齐建表闭环。

## Non-Goals

- 不新增 MySQL 引擎的后端建表 handler。后端当前仅注册 Doris handler;`portal_create_table`
  初版**仅 Doris 执行**。MySQL 的 DDL 规范先作为**知识指导**(用于生成/校验、落任务 SQL),
  待后端补 MySQL handler 再放开工具执行。
- 不改表名生成/校验链路(沿用 `TableNameGeneratorService` 与 `dev-policies.json`)。
- 不改 plan/审批链路(沿用 `2026-06-26-widget-plan-mode-approval` 的 `can_use_tool` 门控);
  建表工具复用现有确认卡机制。
- 不引入独立技能。按用户确认,DDL 规范并入现有 data-dev 技能的 reference(零 DB 迁移)。

## Design

### G1 —— DDL 规范知识(并入 data-dev 技能)

新增 `dataagent/.claude/skills/opendataworks-data-dev/reference/40-ddl-standards.md`,分引擎:

- **通用**:命名沿用 `dev-policies.json`(层前缀 ods/dwd/dws/dim/ads);每列必须
  `COMMENT`;显式 `NOT NULL`/`NULL`;默认值书写;时间/数值/字符串类型选择;避免保留字。
- **Doris**(严格对齐 `buildCreateDdl`):
  - `ENGINE=OLAP`;表模型选择:
    - **明细表 = `DUPLICATE`**(默认,保留明细、不去重)
    - 聚合 = `AGGREGATE`(建表即固定聚合方式,列变更受限)
    - 主键去重/需 upsert = **`UNIQUE KEY(...)`**
  - KEY 列(`keyColumns`)前置且顺序敏感,是前缀索引与排序键。
  - `DISTRIBUTED BY HASH(高基数列) BUCKETS n` —— 见「分桶数量建议」。
  - `replication_num`:本地/测试单副本 = `1`;生产默认 = `3`。
  - 分区:`PARTITION BY RANGE(时间列)`,大表按时间分区;可选动态分区
    `dynamic_partition.*`(与后端解析约定一致:`replication_allocation`/
    `dynamic_partition.replication_allocation`)。
  - 固定 `PROPERTIES`:`storage_format=V2`、`compression=LZ4`。
  - 类型注意:无 `UNSIGNED`;超大整数用 `LARGEINT`;`DATETIME` 无隐式默认;`DECIMAL(p,s)`
    精度显式;`VARCHAR(n)` 按字节;字符串大字段用 `STRING`。
  - **分桶数量建议**:单 bucket 目标数据量约 `1–10GB`;非分区表按全表估算,分区表按
    **单分区**估算;小表最少 `1–10` 桶,避免过度分桶;分桶键选高基数且查询高频的等值列
    (常为 join/过滤键),避免数据倾斜。
- **MySQL**(知识先行):`ENGINE=InnoDB`;`DEFAULT CHARSET=utf8mb4`;显式 `PRIMARY KEY`;
  合理二级索引;`AUTO_INCREMENT`;`TIMESTAMP` vs `DATETIME` 语义;`UNSIGNED`、
  `TINYINT(1)` 布尔约定;可选分区。

新增 `dataagent/.claude/skills/opendataworks-data-dev/assets/engine-ddl-rules.json`:
机器可读的默认值/约束,与后端默认严格一致,供模型快速取默认值——
`doris`: `default_bucket_num=10`、`default_replica_num=3`、`table_models`、`fixed_properties`;
`mysql`: `engine=InnoDB`、`charset=utf8mb4`。

更新 `SKILL.md`:在「SQL 生成/润色」与「创建任务」之间加**建表**步骤,指向
`reference/40-ddl-standards.md`;明确「新表先按引擎规范产出并核对 DDL,再经
`portal_create_table` 执行或落入任务定义」。

### G2 —— 建表工具 `portal_create_table`

**后端**(复用 `TableCreateService`,不重复造 DDL):

- 新增 `agentapi/service/BackendAgentTableService`(仿 `BackendAgentTaskService`),桥接
  `TableCreateService.preview/create`,并接入 `AgentDataScope` 过滤与既有鉴权。
- 在 AI 控制器层(与 `/v1/ai/task`、`/v1/ai/metadata/ddl` 同层)加端点:
  - `POST /v1/ai/table/preview` → `TableCreateService.preview`(生成表名 + DDL,**只读**)
  - `POST /v1/ai/table` → `TableCreateService.create`(**执行建表**)

**portal-mcp**:

- `backend_client.py` 增 `preview_create_table`/`create_table` 调用。
- `app.py` 注册两个工具:
  - `portal_preview_create_table`(只读预览:返回表名 + 规范化 DDL + 是否已存在)
  - `portal_create_table`(执行建表)
- 输入 schema 映射 `TableCreateRequest` 关键字段。**优先传结构化字段让后端构建规范 DDL**
  (保证表名与 DDL 内表名一致);`dorisDdl` 作为高级可选项。`title`/`summary` 作为确认卡
  注解(门控会 strip,不下发领域工具)。

**权限门**(`core/permission_gate.py`):

- `portal_create_table` 加入 `HIGH_RISK_TOOL_NAMES` —— 执行真实引擎 DDL、变更 schema、
  不可逆,值得每次确认;`default`/`acceptEdits` 下均触发确认卡,`plan` 下 deny。
  **批准即执行,无需附言**(符合用户诉求;沿用现有 allow/deny 决策链路,覆盖
  批准/拒绝/取消/超时)。
- `portal_preview_create_table` 只读,不入写集,自动放行。

**技能接线**:`SKILL.md` 与 `reference/30-tool-recipes.md` 增建表工具配方——
先 `portal_preview_create_table` 展示表名 + DDL + 差异 →(确认)→ `portal_create_table`,
与「发布前强制预览」的既有安全范式一致。

### Interfaces

- `portal_create_table` 入参(映射 `TableCreateRequest`):`dbName`、表名组件
  (`layer`/`businessDomain`/`dataDomain`/`customIdentifier`/`statisticsCycle`/`updateType`)、
  `columns[]`、`tableModel`、`keyColumns[]`、`distributionColumns[]`、`bucketNum`、
  `replicaNum`、`partitionColumn`、`tableComment`、`dorisClusterId`、`syncToDoris`、
  可选 `dorisDdl`;确认卡注解 `title`/`summary`。
- 高危分类:`portal_create_table` → HIGH_RISK;`portal_preview_create_table` → 只读。

## Tradeoffs

- **复用 `TableCreateService` 而非在 MCP/技能侧自拼 DDL**:单一权威、与手动建表完全一致、
  复用事务与版本记录。代价:初版仅 Doris(后端约束),MySQL 执行后置。
- **`portal_create_table` 归 HIGH_RISK**:建表不可逆,每次确认更稳;批准即执行、无需附言,
  正好符合用户对「建表工具」的预期。
- **MySQL 知识先行、执行后置**:避免为放开工具而仓促新增后端 MySQL handler;规范知识对
  「生成/校验 DDL、落任务 SQL」已有即时价值。
- 规范并入 data-dev 技能而非独立技能:零 DB 迁移、改动最小,落在 DDL 实际生成处;若日后
  本体建模等流程也要复用,再提升为独立技能。
