# 可复用分析方法论声明式 DAG Skill Plan

配套设计：[`docs/design/2026-07-27-methodology-dag-skill-design.md`](../design/2026-07-27-methodology-dag-skill-design.md)

## 受影响栈

- **DataAgent skill 包**：新增 `dataagent/.claude/skills/opendataworks-methodology-dag/`（主要工作量）
- **DataAgent 后端**：无改动（技能自包含，不需要共享运行时导出任何新变量）
- **前端**：无改动（复用 `sql_execution` 渲染）
- **数据库 / 部署**：无改动（不写迁移；skills 目录是 bind mount）

## 任务

### G1 —— 模型与绑定层（独立可交付，无需数据库）

1. `scripts/methodology_schema.py`
   - pydantic 模型：`Methodology`（`id`/`version`/`name_zh`/`intent`/`caliber`/`owner`/
     `ontology_ref`/`params`/`nodes`/`target`）、`ParamSpec`、六种节点模型的判别联合。
   - `FIELD_DICTIONARY` + `methodology_json_schema()`，对齐
     `ontology-modeling-assistant/scripts/ontology_schema.py:47,156` 的写法。
   - `__main__` 打印 schema，用于生成 `assets/methodology.schema.json`。
2. `scripts/binding.py`
   - 受限表达式求值：`ast.parse(mode="eval")` + 节点类型白名单，禁用 `eval`/`exec`。
   - `{{ expr }}` 受检值绑定：标量转义加引号，标量数组展开逗号列表，其余类型报错。
   - `{{! expr }}` 片段绑定：白名单 `^[A-Za-z_][A-Za-z0-9_.]*$` 或参数 `values` 枚举。
   - 谓词 DSL 编译器：`eq`/`ne`/`lt`/`lte`/`gt`/`gte`/`in`/`between`/`like`/`and`/`or`/`not`，
     null 子谓词丢弃，全空时整个片段渲染为空串。
3. 生成 `assets/methodology.schema.json`。

### G2 —— 引擎

4. `scripts/engine.py`
   - 节点执行器注册表（`sql`/`sqlite`/`transform`/`literal`/`conditional`/`call`）。
   - `force(node)`：锁保护的双检 lazy + memoize，保证每节点每次运行最多执行一次。
   - 依赖并发：`ThreadPoolExecutor` 同时 force 独立依赖。
   - `conditional`：先算谓词再 force 选中分支；分支是软依赖，不参与预先依赖解析。
   - `call`：解析注册表中的另一方法论，维护调用链并拒绝环。
   - `sql` 执行器：子进程调用 platform-tools 的 `scripts/run_sql.py` 并解析 JSON。
     平台工具目录由脚本自行定位：默认同级目录 `../opendataworks-platform-tools`，
     `DATAAGENT_PLATFORM_SKILL_ROOT` 仅作可选覆盖；两处都找不到时抛
     `platform_tools_unavailable`。
   - `sqlite` 执行器：依赖结果按节点名建表装入 `sqlite3` 内存库后执行 SQL。
   - 两级超时：总预算 + 单节点超时。
   - mock 模式：注入 `{node_name: result}`，命中的节点不执行真实逻辑。
   - 每节点 trace 收集（type/status/duration_ms/row_count/pruned）。

### G3 —— 入口脚本与注册表

5. `scripts/validate_methodology.py`
   - schema 校验、`target` 存在、依赖引用存在、DFS 判环并报具体路径、
     模板可解析、参数声明与模板引用一致、`call` 目标可解析且调用图无环。
   - 桩值绑定后的 SQL 过 `validate_sql.py`（platform-tools 可用时；不可用则跳过并在输出中标注）。
   - `--all` 校验整个注册表；`--path` 校验单个文件。
6. `scripts/run_methodology.py`
   - 参数解析与校验（必填、类型、范围、枚举）→ 加载 → 静态校验 → 执行 → 产出
     `kind=sql_execution` 契约（含 `methodology` 与 `trace`）。
   - `--mock` 透传给引擎。
7. `scripts/lookup_methodology.py`
   - `--query` 关键字检索（匹配 `name_zh`/`intent`/`synonyms`/`caliber`），
     `--id` 精确取，`--list` 列全部。只返回语义与参数槽位，**不执行**。
8. 三个注册方法论 `assets/registry/*.json`：
   - `table_growth_ratio`：链式依赖 + SQLite join，`current` 共享依赖。
   - `top_owner_task_growth`：Top-N 选择 → 参数化二次查询。
   - `workflow_publish_trend`：条件剪枝 + transform。

### G4 —— 文档

9. `SKILL.md`：定位、边界、playbook（先 lookup 后 run，未命中回落）、
   与 platform-tools 的硬前置关系、口径变更规则。
10. `reference/10-model.md`、`20-authoring.md`、`30-invocation.md`、`40-output-contract.md`。
    可执行引用一律相对技能自身目录：`cd <本技能目录> && python3 scripts/<name>.py`。
    技能包内不得出现任何根路径——宿主注入的变量、`.claude/skills/...` staging 布局、
    仓库相对路径、`/app/...` 部署路径都不行，否则技能只能在某一套运行时里活。

### G5 —— 后端契约测试

11. `tests/test_builtin_skill_content.py`：新增本 skill 的内容契约测试，
    参照现有 `test_platform_tools_skill_documents_run_sql_as_only_recommended_sql_execution_entrypoint`。
    其中一条专门断言**技能包不含任何根路径**（宿主变量、staging 布局、仓库路径、部署路径），
    防止以后有人为了省事又把根路径写回文档。

### G6 —— 测试

12. `tests/test_methodology_dag_schema.py`：模型接受合法工件、拒绝多余字段与非法节点类型；
    `assets/methodology.schema.json` 与模型导出一致（防止 schema 文件漂移）。
13. `tests/test_methodology_dag_binding.py`（注入安全，必须逐条断言）：
    - 受检值里的 `'` 被转义，无法闭合字符串。
    - 标量数组展开成逗号列表可直接用于 `IN (...)`。
    - 片段占位符拒绝 `1;DROP TABLE x`、空格、引号等非标识符输入。
    - 谓词 DSL 丢弃 null 子谓词；全空时 WHERE 片段为空串。
    - 表达式求值器拒绝函数调用、属性访问、下标以外的 AST 节点。
14. `tests/test_methodology_dag_engine.py`（论文四条语义，逐条断言，不只是"跑通"）：
    - 共享依赖节点一次运行只执行一次（计数 mock 的调用次数）。
    - 条件未选中分支从未被执行（该分支挂一个会抛异常的 mock 节点，运行仍成功）。
    - 独立分支并发（mock 节点各 sleep 0.2s，总耗时显著小于串行和）。
    - `call` 环、依赖环、缺失依赖、不存在的 target 在加载期被拒绝，环报出具体路径。
15. `tests/test_methodology_dag_validate.py`：各类非法工件被拒绝且错误信息可定位。
16. `tests/test_methodology_dag_registry.py`：注册表里每个方法论都通过静态校验，
    且都能在 mock 模式下跑到 target。
17. `tests/evals/evals.json`：问题 → 期望命中的方法论 id 与参数槽位。

## 验证

### 本次可完成（无需数据库，当前容器可跑）

```bash
python -m pytest dataagent/.claude/skills/opendataworks-methodology-dag/tests -q
python -m pytest dataagent/dataagent-backend/tests/test_builtin_skill_content.py -q
"$(command -v python3)" dataagent/.claude/skills/opendataworks-methodology-dag/scripts/validate_methodology.py --all
```

### 需要本地环境（按 AGENTS.md 智能问数验证规则）

前置：MySQL `127.0.0.1:3316`、Redis `127.0.0.1:6379`、`.venv-py313`、
`alembic upgrade head`、`uvicorn main:app`。

在 Agent Studio 给某个 agent 同时勾选 `opendataworks-platform-tools` 与
`opendataworks-methodology-dag`，提交「最近 30 天各分层建表数环比增长」，核对：

- agent 先 `lookup_methodology.py` 命中 `table_growth_ratio`；
- 一次 `run_methodology.py` 调用返回结果，对比未启用该 skill 时的模型轮次数；
- 任务状态 `waiting -> running -> success`；
- `GET /api/v1/nl2sql/tasks/{task_id}/events` 返回终态事件流；
- 最终 assistant 消息落库；
- 前端 `sql_execution` 卡片正常渲染表格。

当前会话运行在远程云容器，Docker / MySQL / Redis 大概率不可用。若端到端冒烟无法执行，
按 AGENTS.md 要求明确说明哪一层已验证、哪条端到端路径未测，不描述为"已完整验证"。

## 回滚

- skill 未默认启用，现网零影响。删除 skill 目录即可完全回滚。
- 后端零改动，没有需要单独回滚的共享模块变更。

## 已知限制

- 注册表只覆盖高频问题；长尾仍走 ad-hoc 链路，这是设计意图而非缺口。
- `transform` 算子集合有意收窄，出现真实缺口时加算子，不引入表达式语言。
- 无跨请求缓存，相同方法论重复调用会重复打库。
- 无 DAG 可视化与 linter（论文列为采纳后的主要摩擦点，本次范围外）。
- `sql` 节点每次多一次解释器启动开销，相对模型往返可忽略。
