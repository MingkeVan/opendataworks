# 后端核心服务拆分设计（WorkflowService 优先）

- 日期: 2026-06-16
- 关联报告: `docs/reports/2026-06-16-main-full-code-review.md`（后端 4.1「上帝类」发现）
- 关联计划: `docs/plans/2026-06-16-code-review-remediation-plan.md`（P2-1）
- 影响栈: 后端（Java · Spring Boot 2.7 · MyBatis-Plus）。不涉及前端、DataAgent、部署。
- 性质: 行为保持型重构（无功能变更、无对外接口变更）

> 本文为设计文档，聚焦现状/问题/范围/方案/接口/权衡。可执行任务、文件清单、回滚步骤见配套计划 `docs/plans/2026-06-16-backend-service-decomposition-plan.md`（待产出）。

---

## 1. 现状

工作流域已有 11 个 `Workflow*` 服务，合计约 9278 行，说明此前已做过一部分拆分。但核心入口 `WorkflowService` 仍是最大的单体：

| 指标 | 现值 |
|------|------|
| 行数 | 2205 |
| 公有方法（对外 API 面） | 13 |
| 私有方法 | 85 |
| 注入依赖 | 15（5 Mapper + 多个 Service + ObjectMapper 等） |
| `@Transactional` | 8 |
| 调用方 | `WorkflowController` + 5 个同族服务 + `DataTaskService` |

`WorkflowService` 当前公有方法按职责可归为五类：

- 读取/查询：`list`、`getDetail`；`buildDefinitionJsonForExport` 虽是导出入口，但在 `definition_json` 缺失时会重建并写回工作流，应随定义组装职责单独迁移。
- 写入/CRUD：`createWorkflow`、`updateWorkflow`、`deleteWorkflow`（两个重载）
- 运行触发：`executeWorkflow`、`backfillWorkflow`
- 版本与元数据：`syncCurrentVersion`、`normalizeAndPersistMetadata`
- 调度引擎与拓扑：`switchSchedulerEngine`、`refreshTaskRelations`

85 个私有方法承载了真正的复杂度：定义 JSON 组装与规范化、任务绑定、拓扑解析、元数据回填等。

已有可复用的同族协作者（部分已存在但职责未完全归位）：

- `WorkflowVersionService`(88) / `WorkflowVersionOperationService`(1290)：版本相关
- `WorkflowTopologyService`(95)：拓扑
- `WorkflowInstanceCacheService`(105)：实例缓存
- `WorkflowPublishService`(1645) / `WorkflowDeployService`(663)：发布与部署
- `WorkflowRuntimeSyncService`(1365) / `WorkflowRuntimeDiffService`(476)：运行态同步与差异

## 2. 问题

- **单一职责被破坏**：一个类同时负责查询、CRUD、运行触发、版本/元数据规范化、调度引擎切换与拓扑刷新。
- **高耦合**：15 个注入依赖，难以独立实例化与单测，变更牵一发动全身。
- **变更回归风险高**：85 个私有方法交织，定义 JSON 组装/规范化逻辑与持久化混杂，发布链路与运行触发共享内部状态。
- **测试网薄弱**：Controller/服务级覆盖偏低（见评审报告），当前不具备安全重构所需的回归保护。
- **被多方依赖**：除控制器外，5 个同族服务 + `DataTaskService` 直接依赖其公有方法，任何签名变更都会扩散。

## 3. 范围

**本设计聚焦 `WorkflowService` 的内部职责拆分**，目标是把它收敛为一个**薄编排层（facade）**，把成块的私有逻辑下沉到聚焦的协作服务。

**明确不做（本设计之外）：**

- 不改变 `WorkflowService` 的公有方法签名与语义（保持调用方零改动）。
- 不改数据库 schema、不改 REST 接口、不改调度/发布对外行为。
- `InspectionService`（2061，规则注册表化）与 `DataTableController`（1187，逻辑下沉）作为**后续独立设计**，仅在此列为同类技术债的 roadmap，不在本文详细展开。

## 4. 方案

### 4.1 目标分层

把 `WorkflowService` 重构为「编排 + 若干聚焦协作者」：

```
WorkflowController / 同族服务 / DataTaskService
        │  （仍调用稳定的 WorkflowService 公有方法）
        ▼
WorkflowService（薄编排 facade：事务边界 + 组合调用）
        ├── JsonCanonicalizer            JSON 规范化 / 哈希（已完成的纯工具切片）
        ├── WorkflowQueryService        读取：list / getDetail（纯查询）
        ├── WorkflowCommandService      写入：create / update / delete（含级联）
        ├── WorkflowDefinitionAssembler 定义 JSON 组装、规范化、元数据回填（纯逻辑，无事务）
        ├── WorkflowTaskRelationService 任务绑定与 refreshTaskRelations
        └── WorkflowExecutionService    executeWorkflow / backfillWorkflow / switchSchedulerEngine
```

- 已存在的 `WorkflowVersionService`/`WorkflowTopologyService`/`WorkflowInstanceCacheService` 等**继续复用**，把 `WorkflowService` 内与之重复的私有逻辑迁移过去而非新建。
- `JsonCanonicalizer` 已作为首个低风险纯逻辑切片落地；`WorkflowQueryService` 已承接 `list/getDetail` 纯查询路径；`WorkflowTaskRelationService` 已承接任务绑定还原、taskId 收集、关系硬删重建和 `refreshTaskRelations`；`WorkflowExecutionService` 已承接 `executeWorkflow/backfillWorkflow` 的运行触发与执行日志状态流转。`buildDefinitionJsonForExport` 的缺失定义回填逻辑、`switchSchedulerEngine` 的定义运行态绑定重写逻辑待 `WorkflowDefinitionAssembler` 抽取后迁移。剩余 CRUD 等有状态路径需在 MySQL 8 + DolphinScheduler 环境中继续差分验证。

### 4.2 迁移策略（行为保持、增量、可回退）

1. **先补测试网**：为 `WorkflowService` 的 13 个公有方法补特征化（characterization）测试，锁定当前输入输出与副作用，作为重构的回归基线。
2. **一次一职责**：按上表逐个抽取，每次只迁移一类职责，保持公有方法签名不变、内部改为委托新协作者。
3. **每步独立提交、独立验证**，可单独回退。
4. **优先顺序**：JsonCanonicalizer（已完成）→ QueryService（已完成）→ TaskRelationService（已完成）→ ExecutionService → CommandService（事务最重，最后做）。

### 4.3 事务边界处理

- `@Transactional` 保留在 `WorkflowService` 编排层（写入用例的事务起点），协作者默认无独立事务，随编排层事务传播，避免拆分后出现嵌套事务/回滚语义漂移。
- 涉及外部调用（DolphinScheduler OpenAPI）的步骤保持现有「先持久化后远程」的顺序，不在事务内等待远程结果。

## 5. 接口

- **对外（公有 API）**：`WorkflowService` 的 13 个公有方法签名、返回类型、异常与事务语义**保持不变**。`WorkflowController`、5 个同族服务、`DataTaskService` 无需改动。
- **对内（新协作者）**：新服务为 `@Service` + `@RequiredArgsConstructor`，方法粒度对应被抽取的私有逻辑；`WorkflowDefinitionAssembler` 暴露纯方法（无 Mapper 依赖或仅只读），便于单测。
- **依赖方向**：编排层 → 协作者 → Mapper；协作者之间不互相反向依赖，避免环。

## 6. 权衡

- **收益**：单类职责清晰、依赖收敛、可独立单测、降低变更回归面；为后续 `InspectionService`/`DataTableController` 提供可复用范式。
- **成本/风险**：纯结构重构无新功能；最大风险是行为漂移与事务语义变化。通过「测试网先行 + 一次一职责 + facade 保持公有 API」三重约束控制。
- **暂不拆得更细**：不引入仓储（Repository）抽象层或 CQRS 等更激进结构，避免过度设计；MyBatis-Plus 直用模式在本轮保留。
- **替代方案对比**：
  - 「保持现状只补注释」——不解决根因，否决。
  - 「一次性大重构」——回归风险高、不可控，否决。
  - 「facade + 增量下沉」（本方案）——风险可控、可回退，采纳。

## 7. 验证

- 重构前：补充并通过 `WorkflowService` 公有方法特征化测试。
- 每步：`mvn -pl backend -am test`（至少受影响范围），保证既有 + 新增测试全绿。
- 重点回归：创建/更新/删除（级联）、发布链路、运行触发与回填、调度引擎切换、`refreshTaskRelations`。
- 若本地可起环境，按需对发布/运行链路做一次冒烟。

## 8. 回退

- 每个职责抽取为独立提交，问题时按提交回退。
- 由于公有 API 不变、调用方不改，回退不影响 `WorkflowController` 与依赖服务。

## 9. 后续（同类技术债 roadmap，另行设计）

- `InspectionService`(2061)：引入 `InspectionRuleRegistry` + 策略模式承载各检查规则。
- `DataTableController`(1187)：导出/审计/同步逻辑下沉至服务，控制器回归薄。
