# 数据开发 DDL 规范与建表工具 Plan

配套设计:`docs/design/2026-07-24-data-dev-ddl-authoring-design.md`

## 受影响栈

- DataAgent 技能:`dataagent/.claude/skills/opendataworks-data-dev`(DDL 规范 reference、
  SKILL playbook、工具配方)
- portal-mcp:`dataagent/portal-mcp`(建表工具注册、backend_client 调用、输入模型)
- DataAgent 后端:`dataagent/dataagent-backend/core/permission_gate.py`(写/高危分类)
- Java 后端:`backend`(AI 端点 + `BackendAgentTableService` 桥,复用 `TableCreateService`)

## 任务

### 第一优先 —— G1 DDL 规范知识(独立可交付)

1. 新增 `opendataworks-data-dev/reference/40-ddl-standards.md`:通用 + Doris(对齐
   `DorisTableEngineHandler.buildCreateDdl`:表模型/KEY 前置/DISTRIBUTED BY HASH BUCKETS/
   replication_num/分区/storage_format=V2/compression=LZ4/类型注意/分桶数量建议)+ MySQL。
2. 新增 `opendataworks-data-dev/assets/engine-ddl-rules.json`:doris 默认 bucket=10、
   replica=3、table_models、fixed_properties;mysql engine=InnoDB、charset=utf8mb4。
3. 更新 `opendataworks-data-dev/SKILL.md`:在「SQL 生成/润色」与「创建任务」之间加
   「建表」步骤,指向 `reference/40-ddl-standards.md`。
4. 回归测试:`dataagent/dataagent-backend/tests/test_builtin_skill_content.py` 增断言——
   `40-ddl-standards.md` 存在,且含 Doris 关键约定(DISTRIBUTED BY / BUCKETS /
   replication_num / DUPLICATE / UNIQUE)与 MySQL(InnoDB / utf8mb4);`engine-ddl-rules.json`
   默认值与后端一致(bucket=10、replica=3)。

### 第二优先 —— G2 建表工具

5. Java 后端:
   - 新增 `agentapi/service/BackendAgentTableService`,桥接 `TableCreateService.preview/create`
     (仿 `BackendAgentTaskService`,接 `AgentDataScope`)。
   - AI 控制器加 `POST /v1/ai/table/preview` 与 `POST /v1/ai/table`。
6. portal-mcp:
   - `backend_client.py` 增 `preview_create_table`/`create_table`。
   - `app.py` 注册 `portal_preview_create_table`(只读)与 `portal_create_table`(执行);
     输入模型映射 `TableCreateRequest`(优先结构化字段,`dorisDdl` 可选)。
7. 权限门:`core/permission_gate.py` 把 `portal_create_table` 加入 `HIGH_RISK_TOOL_NAMES`;
   `portal_preview_create_table` 保持只读。
8. 技能接线:`SKILL.md` + `reference/30-tool-recipes.md` 增建表配方(先 preview 后 create,
   强制预览)。

### 文档

9. 本 design/plan;`docs/handbook` 数据开发/工具处补一句建表工具与 DDL 规范来源。

## 验证

### G1(本次可完成)
- `.venv-py313` 下 `pytest dataagent/dataagent-backend/tests/test_builtin_skill_content.py`。
- 技能文档放置/命名/交叉链接与仓库规则一致性核对。

### G2
- portal-mcp:`pytest dataagent/portal-mcp/tests`(工具注册与参数映射;新增 create_table 用例)。
- 权限门:`pytest dataagent/dataagent-backend/tests/test_permission_gate.py`
  (`portal_create_table` ∈ 写且高危、plan deny;`portal_preview_create_table` 只读放行)。
- Java:touched 模块最小编译/单测(`BackendAgentTableService`)。
- 端到端 smoke(本地 MySQL 127.0.0.1:3316 + Redis 127.0.0.1:6379 + backend + 前端,环境可用时):
  topic → 「新建一张 dwd 明细表 …」→ `portal_preview_create_table` 出表名 + DDL →
  确认卡 → 批准 → Doris 执行建表 → 元数据落库 → 终态消息持久化;拒绝路径验证不建表。
  记录 MySQL/Redis/Python venv、是否真实 provider、各场景通过/跳过。

## 回滚

- G1 为技能内新增文件 + SKILL 引用 + 测试断言,回滚即删除文件与断言,无破坏性变更。
- G2 改动集中在新增端点/工具/高危分类;回滚即摘除 `portal_create_table` 注册与
  `permission_gate` 高危项,后端新增端点独立、可单独下线。

## 已知限制

- `portal_create_table` 初版仅 Doris(后端仅 Doris handler);MySQL 仅有 DDL 规范知识,
  不经工具执行。
- 建表为不可逆 DDL,统一按 HIGH_RISK 每次确认;plan 模式下 deny,仅出方案。
