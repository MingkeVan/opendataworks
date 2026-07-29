# 可复用分析方法论声明式 DAG Skill Design

## Context

### 论文来源

本设计源自 LinkedIn 的经验论文 *QDAG: Declarative Composition of Reusable Analytics
Methodologies at LinkedIn*（arXiv 2606.05662, cs.DB, 2026-06-04）。

论文的核心论断：**一个"分析方法论"几乎从来不是一条 SQL**，而是一个小型 dataflow 程序。
以论文给的三个生产例子为例：

- **人数增长**：先查一次当期各国人数；再查上期，但 `WHERE` 被约束到第一次查询返回的那些国家；
  最后 join 两个结果集算增长率。
- **Top 技能**：先用一条查询选出 Top-N 技能；再用这些技能作参数发起第二轮查询算各自增长率——
  查询之间有数据依赖，中间夹一个抽取实体的变换。
- **帖子唯一曝光**：并行发两条聚合查询（分维度计数与总量），丢掉非正数行，相除得到占比分布。

这类逻辑今天被写成 OLAP 查询外面的**命令式胶水代码**：手拼 SQL 字符串、手写并发编排、
后处理 JSON、条件分支。论文观察到三个反复出现的问题：

1. **编写成本**：从"我有 SQL"到"我有一个正确、并行、带条件分支的接口"，贵在编排那一步。
2. **口径漂移**：同一个方法论在不同接口和团队里被重新实现，副本各自演化，本该一致的定义悄悄不再一致。
3. **各团队各造框架**：每条业务线自建一套查询组合框架（一个 Scala、好几个 Java），重复投入，跨团队无法对齐。

QDAG 的回答是**把方法论从代码变成数据**：声明成一张有向无环图，节点是带类型的执行步骤，
边是数据依赖，交给一个共享引擎执行。作者不再决定跑什么、什么顺序、什么并行、什么跳过、
什么复用——引擎决定。

### 论文的关键设计

**模型（论文 III）**。每个节点消费并产出单个 JSON 值；恰好一个节点被指定为 `target`，
执行从 target 出发，只走到它的传递依赖为止。隐式输入 `$QDAG_INPUT` 注入每次调用，
同一张图因此可以参数化。节点分三组：

- **query**：`PINOT`（模板化 OLAP SQL）、`RESTLI`（下游服务调用）
- **compute**：`SQLITE`（把依赖结果装进内存 SQLite 跑完整 SQL）、`JOIN`、`JQ`、`TRANSFORM`、`LITERAL`
- **control**：`CONDITIONAL`（谓词二选一）、`CALL`（调用另一张图）

`CALL` 是复用单元：两个产品都 CALL 同一张规范图，定义就不可能悄悄分叉。图从注册表按名解析，
所以对共享定义的修复会传播到所有调用方。

**变换子语言（论文 IV）**。用 jq 做 JSON 变换，外面套一层可选的 YAML 宏层，源到源编译回 jq。
论文明确声明这只是人体工学装置，不是新语言，每个宏都有一行 jq 展开。

**动态 SQL 与谓词 DSL（论文 V）**。SQL 模板有两种占位符：

- `{{jq}}` **受检值**：结果必须是标量或标量数组；标量被加引号，数组展开成逗号列表，
  于是**值永远无法逃出它的字面量位置**。
- `{{! jq}}` **非受检片段**：用于注入列名或 SQL 片段（例如动态分组维度），原样拼接、
  作者自负安全，但用 Apache Calcite 解析以尽早拒绝畸形 SQL。

对于"从可选请求参数拼 WHERE"这个经典注入高危场景，论文提供一个小的**谓词 DSL**
（`eq`/`lt`/`in`/`between`/`and`/`or`/`not`），编译成校验过的片段并**丢弃 null 子谓词**，
于是缺失的过滤条件自然消失。注入问题变成数据构造问题。

**执行引擎（论文 VI）**。build system 求值模型的直接移植。每个节点映射到
`Lazy<Future<Value>>`，请求 target 的结果会 force 它的 future，进而递归 force 依赖：

- **只构建需要的**：future 从未被 force 的节点不执行；条件分支未选中的那一侧及其整个子图代价为零。
- **最多构建一次**：lazy future 被 memoize，共享依赖沿多条路径被 force 也只计算一次。
- **并行免费**：节点结果是 future，独立分支自动并发，作者不写任何并发代码。
- 一次请求 O(V+E) 次 force，延迟由**关键路径**而非图规模决定。
- **执行模式**：同一个库既能在网关远程跑，也能嵌入客户端本地跑；**mock 模式**返回作者提供的表，
  不碰任何数据存储——方法论变成一个普通单元测试。

**声明式结构买到了什么（论文 VII）**。执行前静态校验（依赖存在、target 存在、模板可解析、
DFS 判环并报出具体环路径）；每节点可观测性对作者免费；以及"图即成本表达式"——
链式相加、并行取 max、条件按分支概率混合，理论上可在运行前估算延迟分布。论文诚实地把最后一项
标注为**尚未实现的方向**，并指出两个诚实的注意点：百分位不线性可组合，必须传播分布而非百分位；
分支概率与 CALL 倍数依赖工作负载，必须从 trace 估计。

**生产经验（论文 VIII）**。500+ 主机、100+ 用例；编排开销 P50 5–10ms、P99 约 50ms，
且**不随方法论数量增长**。采纳方报告集成速度快约 60%（作者自评为方向性证据，不是受控实验）。
三条教训：最大收益不是性能而是**可测试性与对齐**；模型被接受后摩擦全部转移到**工件周边的工具**
（DAG 可视化、YAML linter、输出 schema 校验）；把专门问题留在**接缝**后面（关系 join 交给
嵌入式 SQL 引擎、外部系统藏在带类型的查询节点后）让核心保持小，代价是硬保证归接缝所有而非 QDAG。

**定位（论文 IX）**。不是联邦查询——不做跨源优化和下推，Calcite 只当解析器和校验器；
不是 Airflow/Dagster 类调度器——那是吞吐导向的离线批处理，QDAG 是单请求、进程内、毫秒级；
比语义层**更低一层**——dbt MetricFlow / LookML / Cube / Malloy 把指标建模成关系式并编译成
单条 SQL，表达不了运行时条件、任意 JSON 变换、下游服务调用，以及"多条查询带中间处理的排序执行"。

## Problem

把论文的三个问题投射到本仓库的智能问数链路，全部成立，而且更严重。

**1. 每个方法论都是现场重新发明的。** 问数是 LLM agent 每次现场写 SQL。标准链路写死在
`dataagent/.claude/skills/opendataworks-platform-tools/reference/30-tool-recipes.md:14`：

```
语义确认 → SQL 生成 → SQL 验证 → run_sql.py 执行 → 结果收口
```

每一步都是一次模型轮次。论文里"先取 Top-N 再按 Top-N 二次查询"这种依赖查询，在这里要来回好几轮：
生成第一条 SQL、验证、执行、读结果、生成第二条 SQL、验证、执行、再合并。

**2. 口径漂移的来源变了，但没有消失。** 论文里漂移来自"两个团队各写一遍"；这里来自
"**同一个模型两次生成的 SQL 不一样**"。这更难察觉，因为没有两份可以 diff 的源码。

**3. 语义资产已经存在，但都不可执行。**

- `opendataworks-business-knowledge/assets/metrics.json` 只有单表达式指标，例如
  `{"metric_key": "failed_publish_cnt", "formula": "SUM(CASE WHEN workflow_publish_record.status = 'failed' THEN 1 ELSE 0 END)"}`。
  这正是论文说的"语义层表达不了多步组合"那一层。
- `ontology-modeling-assistant/scripts/ontology_schema.py:116` 的 `QueryFunction`
  （`function_name` / `intent` / `grain` / `params` / `output_fields` / `notes`）在
  `ontology-modeling-assistant/SKILL.md:67` 被明确定义为"语义交接契约，**不得把它当成可直接运行的 SQL**"。
  语义与执行之间有一道刻意留下的空隙，今天由模型即兴填补。
- `relation_kind` 里已经有 `caliber_rule`（口径规则，`ontology_schema.py:34`）——仓库已经把口径
  当成一等概念，但没有任何机制**强制**它。

**4. 论文的"减少编排代码"在这里翻译成"减少模型轮次"。** 这直接对上 AGENTS.md 用整节篇幅描述的
智能问数超时链路问题："Before increasing timeout, first reduce unnecessary turns, duplicate reads,
path guessing, and repeated tool retries. Raise timeout only after the execution path is already
simplified."确定性地一次执行完整个方法论，正是"先简化执行路径"。

**5. 跨引擎组合今天做不到。** 平台同时接 MySQL 与 Doris，`run_sql.py --engine <mysql|doris>`
一次只打一个引擎。两个引擎的结果需要 join 时，现在没有任何机制。

## Goal

1. 引入一个新 skill，把"多步分析方法论"变成**可注册、可校验、可执行、可回归**的声明式 JSON 工件。
2. 命中已注册方法论时，**一次工具调用**返回确定性结果与固定口径；未命中时无损回落现有 ad-hoc 链路。
3. 给现有语义资产补上可执行体：本体的 `query_functions` 声明语义契约，方法论工件提供执行体。
4. 让方法论可以在**不碰数据库**的情况下被单元测试（论文的 mock 模式）。
5. 提供跨引擎组合能力（内存 SQLite 节点）。
6. **零新增运行时依赖，零前端改动，零现网行为变化。**

## Non-Goals

- 不做跨请求缓存。论文的 Caffeine + 分布式 blob 两级缓存是运维设施，本仓库量级用不上；
  只保留请求内 memoization。
- 不做基于图结构的延迟/成本建模。论文自己标注为未实现方向。
- 不引入 jq、YAML 宏层或任何新的表达式语言。
- 不引入 Calcite 或新的 SQL 解析器。
- 不实现差分隐私节点、`RESTLI` 下游服务节点。
- 不改 `prompts/data_agent_system_prompt.md`。
- 不写 alembic 迁移，不默认对内置问数助手启用。
- 不做 DAG 可视化 UI（论文把它列为采纳后最想要的工具，但那是后续话题）。

## Design

新 skill：`dataagent/.claude/skills/opendataworks-methodology-dag/`。

```
SKILL.md
assets/
  methodology.schema.json
  registry/
    table_growth_ratio.json
    top_owner_task_growth.json
    workflow_publish_trend.json
reference/
  10-model.md
  20-authoring.md
  30-invocation.md
  40-output-contract.md
scripts/
  methodology_schema.py
  binding.py
  engine.py
  lookup_methodology.py
  validate_methodology.py
  run_methodology.py
tests/
  test_methodology_dag_schema.py
  test_methodology_dag_binding.py
  test_methodology_dag_validate.py
  test_methodology_dag_engine.py
  test_methodology_dag_registry.py
  evals/evals.json
```

### 工件格式：JSON 而非 YAML

论文用 YAML，并在结论里把 YAML 的坑列为促使他们做 linter 和可视化的原因。本仓库
`requirements.txt` 没有 PyYAML，而既有语义资产（`ontology.json`、`metrics.json`、
`chart-template/*.json`）全是 JSON。因此工件用 JSON，由 pydantic 模型校验并导出
JSON Schema——完全对齐 `ontology-modeling-assistant/scripts/ontology_schema.py` 的做法
（`FIELD_DICTIONARY` + `model_json_schema()` + `x-field-dictionary`）。

### 节点类型：6 种（论文 9 种的裁剪）

| type | group | 作用 | 论文对应 |
|---|---|---|---|
| `sql` | query | 模板化只读 SQL，经 platform-tools `run_sql.py` 执行 | `PINOT` |
| `sqlite` | compute | 依赖结果装入内存 SQLite 跑完整 SQL（跨引擎 join、集合运算）| `SQLITE` |
| `transform` | compute | 声明式 `filter` / `derive` / `sort` / `limit` / `rename` | `JQ` + `TRANSFORM` |
| `literal` | compute | 常量表（维度映射等）| `LITERAL` |
| `conditional` | control | 谓词二选一，未选中分支及其子图不执行 | `CONDITIONAL` |
| `call` | control | 调用另一个已注册方法论，调用链判环 | `CALL` |

去掉 `RESTLI`（平台无此需求）与 `JOIN`（`sqlite` 已覆盖，论文自己也说简单合并才走轻节点，
这里选择只留一个 join 通路而不是两个）。

`sqlite` 用的是 Python 标准库 `sqlite3`，零依赖。论文在未来工作里提到行存的 SQLite 对宽表
聚合不理想、DuckDB 更合适——在本仓库的中间结果规模（受 `DATAAGENT_QUERY_LIMIT`，默认 1000 行约束）
下这个权衡不成立，标准库足够。

### `sql` 节点委托 platform-tools

`sql` 节点**不自己连数据库**，而是子进程调用 platform-tools 的
`scripts/run_sql.py --database <db> --engine <engine> --sql <bound sql>` 并解析其 JSON 输出。
平台工具目录由脚本自行定位：默认取同级目录 `../opendataworks-platform-tools`，
宿主可用 `DATAAGENT_PLATFORM_SKILL_ROOT` 覆盖。

这样保住了 `30-tool-recipes.md:15` 的契约"`run_sql.py` 是唯一推荐的 SQL 执行入口"，
并且免费继承它的全部护栏：只读校验（`ensure_read_only`）、血缘 guard
（`run_sql.py:123` `enforce_lineage_first_guard`）、后端数据范围校验、结果字节守卫、
以及 `run_sql.py:25` `classify_sql_execution_failure` 的失败归因分类。这正是论文
"把专门问题留在接缝后面"那条教训的应用。

**硬前置**：本 skill 必须与 `opendataworks-platform-tools` 一起安装并启用。
`core/agent_runtime.py:199` `_build_workspace_allowed_roots` 也只在 platform-tools 被启用时
才把它的根目录加入允许列表。两处都找不到时 `run_methodology.py` 返回
`error_code=platform_tools_unavailable`，不静默降级。

### 技能包必须自包含

一个 skill bundle 装到哪都应该能跑，所以**技能内不写任何根路径**：

- 不写宿主注入的根路径变量（`${DATAAGENT_..._SKILL_ROOT}`）——那把技能绑死在这套 backend 上；
- 不写 `.claude/skills/<folder>/...`——那把技能绑死在 `topic_workspace.py` 的 staging 布局这个内部实现上；
- 不写仓库相对路径或 `/app/...` 部署绝对路径。

文档里的调用形式一律是 `cd <本技能目录> && python3 scripts/<name>.py ...`，
脚本自身用 `Path(__file__).resolve().parent.parent` 解析技能根（`registry.py:24`），
注册表、schema、同级技能全部由脚本定位。因此本 skill **不需要后端做任何配套改动**，
共享运行时模块零改动。

这条同样适用于对 platform-tools 的依赖：先找同级目录，环境变量只作为可选覆盖，
而不是必需输入。

### 占位符绑定与注入安全（论文 V 的直接移植）

- `{{ expr }}` **受检值**：必须求值为标量或标量数组。标量按引擎规则转义并加引号，
  数组展开为逗号分隔列表（供 `IN (...)` 使用）。值无法逃出字面量位置。
- `{{! expr }}` **非受检片段**：只允许标识符，必须匹配 `^[A-Za-z_][A-Za-z0-9_.]*$`，
  或命中参数声明里的 `values` 枚举。**比论文更严**——论文靠 Calcite 解析加作者自觉，
  这里直接用白名单堵死。
- `{{? name }}` **谓词片段**：引用本节点 `predicates` 里的同名谓词，编译成校验过的 SQL 片段。
  论文的谓词 DSL 没有专门的占位符形式，这里给它一个，是为了让「这段 WHERE 是数据构造出来的」
  在模板里一眼可见，而不是混在受检值里。
- **谓词 DSL**：`{"and": [{"field": "layer", "op": "eq", "value": "$params.layer"}]}`，
  支持 `eq`/`ne`/`lt`/`lte`/`gt`/`gte`/`in`/`between`/`like`/`and`/`or`/`not`。
  **null 子谓词直接丢弃**——参数没传，过滤条件就消失，模板里不需要写分支。
  `in` 的 `values` 可以是 `"$pluck(current.rows, 'layer')"`，上游返回 0 行时整段消失，
  所以不会拼出非法的 `IN ()`；这也是注册表里链式依赖节点收敛范围的标准写法。
- 表达式求值器用 `ast.parse(mode="eval")` 加节点类型白名单，**不使用 `eval`/`exec`**。
  只支持字段路径、字面量、比较、布尔运算和一小组白名单函数。
- 绑定后的完整 SQL 仍然过 `validate_sql.py` 与后端只读/数据范围校验。纵深防御，不替代任何一层。

### 引擎语义（论文 VI 的直接移植）

- 从 `target` 出发按需求值；不可达节点不执行。
- 每节点每次运行最多执行一次（memoize）；共享依赖只算一次。
- 独立分支用 `ThreadPoolExecutor` 并发。SQL 走子进程、I/O 密集，线程足够，
  避免为此引入 asyncio（保持脚本可以独立 `python xxx.py` 运行）。
- `conditional` 先算谓词再 force 选中分支；未选中分支是软依赖，永不执行。
- `call` 解析注册表里的另一个方法论，调用链判环。论文把递归列为"可表达但视作危险"，
  设计意图是拒绝调用图里的环——这里直接拒绝。
- **两级超时**，对齐 AGENTS.md 的智能问数超时规则：`--total-timeout`（单次运行总预算，默认 240s）
  与单节点超时（默认取 `DATAAGENT_SQL_READ_TIMEOUT_SECONDS`）。
- **mock 模式**：`--mock <file.json>` 用作者提供的节点结果替代真实执行。论文说这是实践中
  最受重视的性质；在本仓库它同时满足 AGENTS.md 对"targeted regression test"的要求，
  且让 CI 不需要数据库。

### 静态校验（论文 VII a）

`validate_methodology.py` 在执行前拒绝：schema 不合法、`target` 不存在、依赖引用不存在、
依赖图有环（DFS，**报出具体环路径**）、模板占位符不可解析、`call` 目标无法解析或调用图有环、
参数声明与模板引用不一致。绑定桩值后的 SQL 还会过一遍 `validate_sql.py`。
在手写胶水里本该是运行时异常的错误，在这里变成加载期拒绝。

### 输出契约：复用现有渲染，前端零改动

`dataagent-frontend/src/views/intelligence/ToolOutputRenderer.vue:95,350,607` 按
`kind === 'sql_execution'` 渲染 SQL、表格、行数与耗时。`run_methodology.py` 直接产出该 kind，
多余字段被渲染器忽略：

```json
{
  "kind": "sql_execution",
  "tool_label": "方法论执行",
  "methodology": {"id": "...", "version": "...", "caliber": "...", "params": {}},
  "sql": "<target 节点绑定后的 SQL>",
  "columns": [], "rows": [], "row_count": 0,
  "duration_ms": 0,
  "result_state": "success|empty_result|failed",
  "error_code": null, "failure_attribution": [], "retryable": false, "stop_reason": "",
  "trace": {"executed": 3, "pruned": 1, "nodes": [
    {"name": "current", "type": "sql", "status": "success", "duration_ms": 210, "row_count": 5}
  ]}
}
```

`trace` 是论文"每节点可观测性对作者免费"的落地：每个节点的类型、状态、耗时、行数、
是否被剪枝，作者不写任何埋点。

失败时透传失败节点由 `classify_sql_execution_failure` 产出的
`error_code` / `failure_attribution` / `stop_reason`，另加本层错误码：
`methodology_not_found`、`methodology_invalid`、`param_missing`、`param_rejected`、
`methodology_timeout`、`platform_tools_unavailable`。

### 三个注册方法论

覆盖论文的三种结构，全部基于平台自身元数据表（这些表已被
`business-knowledge/assets/metrics.json` 引用，保证存在）：

| 方法论 | 结构 | 论文对应 |
|---|---|---|
| `table_growth_ratio` | 链式依赖 + SQLite join；`current` 被两个节点共享，只执行一次 | Headcount growth |
| `top_owner_task_growth` | Top-N 选择 → 用选中实体参数化二次查询 | Top skills |
| `workflow_publish_trend` | 条件剪枝（有无维度过滤走不同口径）+ transform | Talent-pool report |

### Agent playbook

写在 SKILL.md，**不进系统提示词**——`tests/test_builtin_skill_content.py:41` 禁止系统提示词
出现 skill 专属 token，路由规则必须留在 skill 内。

1. `lookup_methodology.py --query "<用户问题>"` → 候选方法论 + intent + 参数槽位 + 口径。
2. 命中且参数齐全 → `run_methodology.py --id <id> --params '<json>'`，一次调用拿最终结果。
3. 参数缺失 → 只追问缺的槽位，不自行改口径。
4. 未命中 → 回落 platform-tools 既有链路。
5. 禁止为"跑通"临时改注册表口径；口径变更走 authoring 流程并升 `version`。

### 与既有语义资产的关系

方法论工件的 `ontology_ref` 指向本体的 `query_functions.function_name`。分工是论文的
"sits below the semantic abstraction"在本仓库的具体形态：

- **本体 / 业务知识**：声明这个业务概念是什么、口径是什么、有哪些参数槽位（语义层）。
- **方法论 DAG**：声明怎么算出来（执行层）。
- `metrics.json` 的单表达式指标继续留在原处；只有需要多步、依赖查询或跨引擎的才升级为方法论。

## Interfaces

### 新增

- skill 目录 `dataagent/.claude/skills/opendataworks-methodology-dag/`（全部新增文件）。
- 脚本调用契约，路径相对技能自身目录：
  - `cd <本技能目录> && python3 scripts/lookup_methodology.py --query "<问题>"`
  - `cd <本技能目录> && python3 scripts/run_methodology.py --id <id> --params '<json>'`
  - `cd <本技能目录> && python3 scripts/validate_methodology.py --all`
- 可选环境覆盖（全部有默认值，缺失不影响运行）：`DATAAGENT_PLATFORM_SKILL_ROOT`、
  `DATAAGENT_QUERY_LIMIT`、`DATAAGENT_SQL_READ_TIMEOUT_SECONDS`、
  `DATAAGENT_METHODOLOGY_TOTAL_TIMEOUT_SECONDS`。

### 不变

- **后端零改动**。技能自包含，不需要共享运行时导出任何新变量。
- `run_sql.py` / `validate_sql.py` / `build_chart_spec.py` 契约不变。
- 前端渲染不变。
- 系统提示词不变。
- 数据库 schema 不变，无迁移。
- 部署不变（skills 目录是 bind mount，`deploy/docker-compose.prod.yml:228`）。

## Tradeoffs

**用受限声明式 transform 替代 jq。** 代价是表达力弱于 jq：不能写任意 JSON 重塑。
理由是引入 jq 需要新依赖，而 YAML 宏层是一整套需要维护的源到源编译器——论文自己都强调那只是
人体工学装置。当前三个方法论的变换需求（过滤、派生列、排序、截断）完全覆盖。
若后续出现真实的表达力缺口，正确做法是给 `transform` 加算子，而不是引入语言。

**`sql` 节点走子进程而非进程内。** 每个 SQL 节点多一次 Python 解释器启动开销（约几十毫秒）。
论文关心 P99 50ms 的编排开销，这里不关心——本场景的基线是"多次模型往返"，量级是秒。
换来的是完整复用 `run_sql.py` 的全部安全与归因逻辑，不复制一行。

**注册表只覆盖问题分布的头部。** 论文的方法论由工程师手写且形态稳定；这里用户问题是开放的。
所以方法论注册表永远只能覆盖高频问题，长尾必须留给 agent 现场处理。设计上因此是**混合**的：
先查注册表，未命中就无损回落。这是与论文场景最本质的差异，也是为什么"未命中回落"必须是
一等路径而不是错误分支。

**不默认启用。** 现网问数行为零变化，代价是需要运维在 Agent Studio 手动勾选才生效。
`core/skill_admin_service.py:964` `_discovered_skill_folders()` 扫描任何带 `SKILL.md` 的
目录，所以新 skill 会自动出现在列表里。

**引擎并发用线程而非 asyncio。** 线程池对子进程 I/O 足够，且脚本保持可独立运行、可被
pytest 直接调用。代价是不能扩展到大量并发节点——本场景一张图通常 3–5 个节点，不成问题。

## Follow-up

- DAG 可视化与 linter。论文把这两项列为模型被接受后采纳方最想要的东西，
  并自评"早期投入不足"。本次不做。
- 从一次成功的 ad-hoc 问数**提升**为注册方法论的流程（agent 建议、人工审核、落注册表）。
  这是让注册表长大的关键机制，值得单独设计。
- 与 eval 框架打通：注册方法论的 mock 用例可以直接作为 `expected_sql` / `expected_result` 的来源。
