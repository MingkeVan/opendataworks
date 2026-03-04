# 智能助理 NL2LF2SQL 技术设计（面向现有 Assistant 架构）

> 目标：在不改 DataStudio 主页面的前提下，把当前“规则拼 SQL”升级为“NL -> LF -> SQL”链路，显式利用 `data_table` 元数据与 `data_lineage` 血缘信息，提高问数准确率、可解释性和可控性。

## 1. 现状与问题

### 1.1 当前链路（已实现）
- 当前智能问数核心在 `AssistantOrchestratorService.buildSqlDraft`，主要是：
  - 识别是否是 SQL 文本；
  - 非 SQL 场景做简单兜底（如 `SELECT 1` 或 `SELECT * FROM table LIMIT 200`）。
- 代码位置：
  - `backend/src/main/java/com/onedata/portal/service/assistant/AssistantOrchestratorService.java:400`

### 1.2 元数据利用现状
- `data_table`：已接入助理 `LF Grounding`，用于 SQL 表绑定与上下文消歧（`database` 维度）。
- `data_lineage`：已接入助理 `LF Grounding`，用于生成 join 提示与多输入无路径澄清信号。
- 代码位置：
  - `backend/src/main/java/com/onedata/portal/service/assistant/nl2lf/DefaultLfGroundingService.java`
  - `backend/src/main/java/com/onedata/portal/service/SqlTableMatcherService.java`
  - `backend/src/main/java/com/onedata/portal/service/DataTaskService.java`

### 1.3 核心缺口
- 问数链路缺少“结构化语义中间层”，导致：
  - 对多表、聚合、口径、时间粒度表达不稳定；
- `data_table` / `data_lineage` 已接入但仍是规则化利用，字段级语义绑定与 join path 打分仍待增强；
  - 审批与 skill 规则难以在“语义层”做强校验。

## 2. 目标与边界

### 2.1 目标
1. 引入 LF（Logical Form）作为中间表示，形成 `NL -> LF -> SQL`。
2. 在 LF Grounding 阶段强制接入元数据：
   - `data_table` 用于表/字段候选与消歧；
   - `data_lineage` 用于 join path 推荐与风险提示。
3. 保持与现有执行链兼容：
   - `need-confirm / yolo` 策略不变；
   - SQL analyze/approval/execute 风控链路不变。

### 2.2 非目标（本期）
- 不做通用 SQL 全语法覆盖；
- 不引入复杂自训练模型平台；
- 不改 DataStudio 页面，仅改助理后端与助理面板交互。

## 3. 目标架构

```text
User NL
  -> Intent + Context
  -> LF Planner (LLM/规则混合)
  -> LF Grounding (data_table/data_lineage)
  -> LF Validator (skills/policy)
  -> LF SQL Compiler (deterministic)
  -> SQL Analyze (existing)
  -> Approval Gate (existing)
  -> SQL Execute (existing)
  -> Summary + Chart + TaskDraft
```

## 4. LF Schema（建议 v1）

## 4.1 顶层结构

```json
{
  "version": "v1",
  "intent": "query|bi_query|task_generation",
  "context": {
    "sourceId": 1,
    "database": "ods",
    "timezone": "Asia/Shanghai"
  },
  "entities": [],
  "dimensions": [],
  "metrics": [],
  "filters": [],
  "joins": [],
  "groupBy": [],
  "orderBy": [],
  "limit": {
    "profile": "text_answer|bi_sampling|manual_execute|validation",
    "value": 500
  },
  "chartIntent": {},
  "taskIntent": {},
  "clarification": {},
  "confidence": {
    "overall": 0.0,
    "tableBinding": 0.0,
    "columnBinding": 0.0,
    "joinPath": 0.0
  },
  "trace": {
    "llmReasoning": "",
    "metadataHits": []
  }
}
```

## 4.2 字段定义
- `entities`
  - 含候选表与已绑定表：`tableRef`, `tableId`, `dbName`, `alias`, `role(main|lookup|bridge)`。
- `dimensions`
  - 维度列定义：`columnRef`, `granularity(day|week|month|none)`, `alias`。
- `metrics`
  - 指标定义：`agg(sum|count|avg|max|min|count_distinct)`, `columnRef`, `alias`, `semanticType(amount|count|ratio)`。
- `filters`
  - 过滤定义：`columnRef`, `operator`, `value`, `valueType`, `connector`。
- `joins`
  - 连接定义：`leftTableId`, `rightTableId`, `joinType`, `conditions[]`, `source(lineage|heuristic|llm)`。
- `clarification`
  - 需要澄清时返回：`required`, `questions[]`, `blockingReason`。

## 4.3 最小可执行 LF 示例

```json
{
  "version": "v1",
  "intent": "bi_query",
  "context": { "sourceId": 1, "database": "ods", "timezone": "Asia/Shanghai" },
  "entities": [
    { "tableRef": "ods.order_detail", "tableId": 101, "alias": "t1", "role": "main" }
  ],
  "dimensions": [
    { "columnRef": "t1.dt", "granularity": "day", "alias": "日期" }
  ],
  "metrics": [
    { "agg": "sum", "columnRef": "t1.pay_amount", "alias": "支付金额", "semanticType": "amount" }
  ],
  "filters": [
    { "columnRef": "t1.dt", "operator": "between", "value": ["2026-02-01", "2026-02-29"], "valueType": "date", "connector": "and" }
  ],
  "joins": [],
  "groupBy": ["t1.dt"],
  "orderBy": [{ "field": "t1.dt", "direction": "asc" }],
  "limit": { "profile": "bi_sampling", "value": 2000 },
  "chartIntent": {
    "preferredType": "line",
    "xField": "日期",
    "yFields": ["支付金额"]
  },
  "clarification": { "required": false, "questions": [] },
  "confidence": { "overall": 0.92, "tableBinding": 0.99, "columnBinding": 0.9, "joinPath": 1.0 },
  "trace": {
    "llmReasoning": "按日期聚合支付金额并展示趋势",
    "metadataHits": [
      { "type": "table", "tableId": 101, "reason": "名称匹配" },
      { "type": "column", "column": "pay_amount", "reason": "金额语义" }
    ]
  }
}
```

## 5. 编译器接口定义（Java）

## 5.1 新增包建议
- `backend/src/main/java/com/onedata/portal/service/assistant/nl2lf/`

## 5.2 核心接口

```java
public interface LfPlanner {
    LogicalForm draft(NlQueryInput input);
}

public interface LfGroundingService {
    LogicalForm ground(LogicalForm draft, MetadataContext metadataContext);
}

public interface LfValidator {
    LfValidationResult validate(LogicalForm lf, PolicyContext policyContext);
}

public interface LfSqlCompiler {
    CompiledSql compile(LogicalForm lf);
}

public interface LfClarificationService {
    ClarificationResult buildQuestions(LogicalForm lf, LfValidationResult validationResult);
}
```

## 5.3 关键 DTO
- `NlQueryInput`
  - `content`, `sourceId`, `database`, `mode`, `limitProfile`, `manualLimit`。
- `LogicalForm`
  - 对应本方案 LF schema。
- `LfValidationResult`
  - `valid`, `blocked`, `errors[]`, `warnings[]`, `needClarification`。
- `CompiledSql`
  - `sql`, `params`, `explain`, `lineageUsed(boolean)`。

## 5.4 Metadata Provider

```java
public interface MetadataProvider {
    List<TableMeta> findTables(String database, String keyword);
    List<ColumnMeta> listColumns(Long tableId);
    List<JoinPath> suggestJoinPaths(List<Long> tableIds);
}
```

实现建议：
- `DataTableMetadataProvider`：基于 `data_table` + 列元数据源。
- `LineageJoinPathProvider`：基于 `data_lineage` 图做 join path 评分。

## 6. 对现有 Assistant 的改造清单

## 6.1 `AssistantOrchestratorService` 改造点
- 当前：`buildSqlDraft(content)` 直接输出 SQL。
- 改造：
  1. `buildLfDraft(content, context)`；
  2. `groundLf(lfDraft)`；
  3. `validateLf(lf)`；
  4. `compileSqlFromLf(lf)`；
  5. 再进入现有 `toolExecutor.analyzeSql/executeSql`。

建议新增 step：
- `lf_draft`、`lf_grounding`、`lf_validation`、`lf_compile`。

建议新增 artifact：
- `lf_draft`、`lf_grounded`、`lf_validation_report`、`sql_compiled`。

## 6.2 `AssistantToolExecutor` 改造点
- 新增方法：
  - `executeCompiledSql(runId, compiledSql, context, challenges, autoConfirm)`；
  - `resolveLimitByLf(lf, context)`（保留 `MAX_LIMIT=10000`）。

## 6.3 `AssistantChartService` 改造点
- 优先读取 `lf.chartIntent`，再回退当前启发式图表推断。
- 记录 `chart_reasoning` 中增加 `lfDriven=true/false`。

## 6.4 `AssistantSkillService` 扩展建议
新增 skill：
1. `lf_consistency_skill`：LF 字段完整性与冲突检查。
2. `metadata_binding_skill`：表字段绑定置信度阈值控制。
3. `join_path_skill`：必须给出 lineage 可解释路径或进入澄清。

## 7. 元数据与血缘利用策略

## 7.1 `data_table` 用法
- 表名/同义词匹配；
- 字段存在性校验；
- 字段语义标签（金额、时间、主键）辅助指标/维度识别。

## 7.2 `data_lineage` 用法
- 多表连接路径推荐与排序；
- 口径说明（上游来源链路）；
- 风险提示（跨域 join、弱关联 join）。

## 7.3 置信度与澄清策略
- `tableBinding < 0.8` 或 `joinPath < 0.7`：进入澄清问题。
- 无可解释 join path：禁止自动执行，仅返回 SQL 草稿+解释。

## 8. API 与数据落库建议

## 8.1 API 兼容策略
- 现有 `/v1/assistant/sessions/{id}/messages` 不变；
- `context` 可增加可选字段：`plannerMode = nl2lf2sql`（默认 `nl2lf2sql`，不保留 legacy 回退）。

## 8.2 落库策略
- 复用 `assistant_artifact` 存 LF 相关产物；
- 本期可不新增表，仅新增 `artifact_type` 枚举值。

## 9. 分阶段实施

1. Phase 1（1-2 周）
- 单表聚合 NL2LF2SQL：`select + where + group + order + limit`。
- `data_table` 绑定 + `data_lineage` join 提示（已落地）。

2. Phase 2（1-2 周）
- 多表 join + `data_lineage` path ranking。
- 增加澄清问句机制。

3. Phase 3（1 周）
- BI 图表意图走 LF；
- task_draft 从 LF 直接生成（替代当前 SQL 再解析）。

4. Phase 4（1 周）
- A/B 灰度：同一 `nl2lf2sql` 链路下按规则/提示词版本分流；
- 质量评估与回滚策略。

## 10. 测试与验收

## 10.1 单元测试
- `LfPlannerTest`：NL -> LF 结构正确性。
- `LfGroundingServiceTest`：表/字段绑定命中 `data_table`。
- `LfSqlCompilerTest`：LF 编译 SQL 语法正确。

## 10.2 集成测试
- 新增 `AssistantNl2Lf2SqlIntegrationTest`：
  - 问数请求 -> LF artifact -> SQL artifact -> execute success；
  - 模糊表名触发澄清；
  - lineage 缺失时给出阻断或 warning。

## 10.3 E2E 测试
- 新增 `AssistantControllerNl2Lf2SqlE2EIntegrationTest`：
  - SSE 事件包含 `lf_draft/lf_grounding/lf_compile`；
  - `need-confirm/yolo` 在 LF 驱动下行为一致。

## 11. 风险与回滚
- 风险：LF 过严导致“可答问题变少”。
- 对策：
- 优化 LF 校验阈值与澄清策略；
- 灰度启用 `nl2lf2sql` 子版本；
- 记录 LF 失败原因和人工修正率。

---

该方案落地后，智能问数将从“弱规则 SQL 草稿”升级为“元数据驱动、可解释、可审核”的 NL2LF2SQL 体系，并与现有助理审批/skills/执行链平滑兼容。
