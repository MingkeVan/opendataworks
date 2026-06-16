# 代码评审修复计划

- 日期: 2026-06-16
- 关联报告: `docs/reports/2026-06-16-main-full-code-review.md`
- 范围: 将全量评审发现转化为可执行的分阶段修复任务
- 分支约定: 每个工作流（workstream）独立开分支、独立验证、独立合并，避免大杂烩 PR

> 说明: 本计划覆盖的小型/局部修复（配置外部化、日志补全、Lint 接入等）可直接执行。
> 标注 **[需先写设计]** 的大型重构（上帝类拆分、巨型组件拆分）属中/大变更，按 AGENTS.md 须先在 `docs/design/` 产出对应设计文档再实施，本计划只列范围与切分思路，不在此阶段直接改业务代码。

---

## 优先级与排期总览

| 阶段 | 主题 | 性质 | 风险 | 依赖 |
|------|------|------|------|------|
| P0 | 凭据外部化、异常日志补全 | 小、低风险 | 低 | 无 |
| P1 | 前端工程化门禁（Lint/格式化）、DataAgent 超时与异常分流 | 小～中 | 低～中 | P0 |
| P2 | 上帝类 / 巨型组件拆分 | 大重构 **[需先写设计]** | 高 | P1（先补测试网） |
| P3 | 技术债清理（注释代码、魔法字符串、docstring、仓库卫生）| 小 | 低 | 无 |

每阶段产出独立可合并，P0/P1/P3 之间无强依赖，可并行推进；P2 必须在相应模块补齐测试网后再动。

---

## P0 — 关键修复（生产前，小且低风险）

### P0-1 后端数据库凭据外部化
- 对应发现: H-1
- 目标: 数据库 `url`/`username`/`password` 改为环境变量注入，与既有 `${AUTH_JWT_SECRET:...}` 风格一致
- 触及文件:
  - `backend/src/main/resources/application.yml:11-13`
  - `deploy/.env.example`、`deploy/docker-compose.dev.yml`、`deploy/docker-compose.prod.yml`（补充 `DB_URL/DB_USERNAME/DB_PASSWORD` 透传）
  - `deploy/README.md`（说明新增环境变量）
- 做法: `url: ${DB_URL:jdbc:mysql://localhost:3306/...}`、`username: ${DB_USERNAME:opendataworks}`、`password: ${DB_PASSWORD:opendataworks123}`，保留默认值以不破坏本地启动
- 验证: `mvn -pl backend -am compile`；本地以默认值与显式 env 两种方式各启动一次确认数据源装配成功
- 回退: 单文件还原；默认值保留意味着即便回退也不影响既有部署
- 影响面: 部署行为（中），需同步更新 deploy 文档（已含在触及文件内）

### P0-2 后端静默异常补日志
- 对应发现: H-2
- 目标: 26 处 `catch (... ignored)` 至少补 TRACE/DEBUG 日志与一行原因注释；明显应上抛的改为受检处理
- 触及文件（示例，全量以 `grep -rnE "catch\s*\([^)]*ignored" backend/src/main` 为准）:
  - `service/DataQueryService.java:139,144`
  - `service/DolphinSchedulerService.java:702`
  - `service/InspectionService.java:1584`
  - `service/WorkflowService.java:567,670,887`
- 做法: 统一为 `catch (XxxException e) { log.trace("<原因>，已忽略", e); }`；逐处判断是否“真应忽略”，不可忽略的归并到上层处理
- 验证: `mvn -pl backend -am test`（至少编译 + 受影响 Service 既有单测通过）
- 回退: 纯增量日志，按文件还原即可
- 影响面: 局部（低）

### P0-3 DataAgent 权限回调兜底补日志与分流
- 对应发现: 4.2 中项（权限回调多层 `except Exception` 无日志）
- 目标: 权限决策/ask-user 兜底路径补 `logger.exception(...)`，并按异常类型（超时/连接/存储 vs 逻辑错误）返回恰当 allow/deny
- 触及文件: `dataagent/dataagent-backend/core/task_executor.py:466,475,478,488,519,541,603,632`
- 做法: 在每个静默 `except Exception` 处补结构化日志（带 `task_id/topic_id`）；区分 `TimeoutError/ConnectionError/数据库异常` 与应用逻辑异常
- 验证: `pytest dataagent/dataagent-backend/tests/test_task_executor.py`；为“权限兜底异常被记录”新增 1 条针对性回归用例
- 回退: 局部还原
- 影响面: 局部（低），但属高风险代码区，改动需保守、保持原有 allow/deny 默认语义不变

---

## P1 — 高优先（工程化门禁与健壮性）

### P1-1 前端引入 Lint / 格式化门禁
- 对应发现: H-3
- 目标: 建立自动化质量门禁，遏制风格漂移与 `console.*` 残留
- 触及文件:
  - 新增 `frontend/.eslintrc.cjs`（`plugin:vue/vue3-recommended`）、`frontend/.prettierrc.json`、`frontend/.eslintignore`
  - `frontend/package.json`（新增 `lint`/`lint:fix`/`format` 脚本与 devDependencies）
- 做法: 首版规则**以告警为主、不强失败**，先 `lint` 出基线；`no-console` 设为 warn，分批清理 127 处 `console.*`（替换为统一日志封装或删除）
- 验证: `export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use && npm --prefix frontend run lint`
- 回退: 删除新增配置与脚本
- 影响面: 仅工程化（中），不改运行逻辑；`console.*` 清理可拆为后续小 PR 渐进推进

### P1-2 DataAgent 集中式 SQL 超时解析
- 对应发现: 4.2 中项（SQL 读超时未按 execution_mode 统一解析）
- 目标: 提供 `resolve_sql_timeout(cfg, execution_mode)`，所有 `TaskExecutionInput` 构造点统一调用，消除回落到短默认值的缺口
- 触及文件:
  - `dataagent/dataagent-backend/config.py`（或就近的 timeout 解析模块）新增函数
  - `core/task_executor.py`、`core/task_coordinator.py`、`core/task_submission_service.py` 各构造点改为调用
- 做法: 镜像既有 `_resolve_max_turns()` 写法；交互/后台分别取对应配置项
- 验证: `pytest dataagent/dataagent-backend/tests/`；新增“交互 vs 后台 SQL 超时选择”回归用例
- 回退: 局部还原
- 影响面: 跨若干模块的内部契约（中），属超时链调整，须按 AGENTS.md 超时规则一并复核 agent 超时 / 反代超时不被新值倒挂

### P1-3 前端 JSON.parse / localStorage 容错
- 对应发现: 4.3 中项
- 目标: 消除数据损坏导致的静默崩溃
- 触及文件: `views/datastudio/DataStudioNew.vue:1341,1373`、`views/workflows/WorkflowDetail.vue:1499`、`views/settings/DataSourceManagement.vue:503,516`
- 做法: 抽一个 `safeJsonParse(raw, fallback)` 工具置于 `utils/`，所有读取点改用；`localStorage` 读取加 try-catch 与（必要时）schema 版本号
- 验证: 为 `safeJsonParse` 增加 Vitest 单测；`npm --prefix frontend run test`
- 回退: 局部还原
- 影响面: 局部（低）

---

## P2 — 大型重构（**[需先写设计]**，先补测试网再动）

> 以下两项属高风险大重构，按 AGENTS.md 须先在 `docs/design/` 产出 `YYYY-MM-DD-<topic>-design.md` 并配套 `docs/plans/` 细化执行计划，**且必须先为目标模块补足回归测试**，再分批小步重构。此处仅记录范围与切分思路。

### P2-1 后端上帝类拆分
- 对应发现: 后端 4.1 中项
- 目标: 将 `WorkflowService`(2197) 等按职责拆为聚焦服务
- 切分思路:
  - `WorkflowService` → 定义/版本/拓扑/发布 已部分存在独立服务，明确边界并迁移委托逻辑
  - `InspectionService` → 引入 `InspectionRuleRegistry` + 策略模式承载各检查规则
  - `DataTableController`(1187) → 导出/审计/同步逻辑下沉至 Service，控制器回归“薄”
- 前置条件: 先为待拆服务补单测/集成测试形成回归网（否则拆分回归风险高）
- 设计文档话题建议 slug: `backend-service-decomposition`

### P2-2 前端巨型组件拆分
- 对应发现: 前端 4.3 高项
- 目标: `DataStudioNew.vue`(6108) 等拆为 < 800 行/组件
- 切分思路: 侧栏 / 编辑器 / 结果网格 / 右面板分离（部分已有 `DataStudioResultGrid.vue`、`DataStudioRightPanel.vue`，完成抽取）；被跨视图复用的 `TaskEditDrawer` 等从 `views/` 迁入 `components/`，消除“视图导入视图”的耦合
- 前置条件: 先为目标组件补关键路径组件测试
- 设计文档话题建议 slug: `frontend-megacomponent-split`

---

## P3 — 技术债清理（小、低风险，可穿插进行）

| 任务 | 触及文件 | 验证 |
|------|----------|------|
| 删除注释掉的“数据量突增检测”逻辑（实现或移除）| `backend/.../InspectionService.java:880-927` | `mvn -pl backend -am compile` |
| 后端魔法字符串集中为枚举/常量 | `WorkflowService.java:81-86` 等 | 编译 + 既有单测 |
| MyBatis `log-impl` 改 SLF4J（避免生产 SQL 打 stdout）| `backend/.../application.yml:43` | 本地启动观察日志走 SLF4J |
| DataAgent 核心契约补 docstring | `core/agent_runtime.py`、`core/task_executor.py` | 文档审阅 |
| 清理 `bak/` 陈旧文档（归档到 `docs/` 或删除）| `bak/frontend-development-guide.md` | 链接/引用检查 |
| 补“双 Agent 平台共存”ADR 与 README 模块结构说明 | 新增 `docs/design/...` 或 README | 文档审阅 |
| 前端 API import 风格统一为 `@/` 别名、超时魔法数集中 | `frontend/src/api/*.js`、新增 `src/config/timeouts.js` | `npm --prefix frontend run lint`/`test` |

---

## 验证与质量门禁（统一约定）

- 后端: `mvn -pl backend -am test`（最小相关范围）；改 DolphinScheduler/工作流链路时跑相应集成测试
- DataAgent: `pytest dataagent/dataagent-backend/tests/<touched>`；触及超时/权限的改动须加针对性回归用例
- 前端: 先 `nvm use`，再 `npm --prefix frontend run lint` / `run test`
- 跨层（P1-2 等）: 按 AGENTS.md，若条件具备应补一次本地端到端冒烟（MySQL/Redis/后端/前端），否则在 PR 中显式说明未跑全链路的层

## 风险与回退总则

- 每个 workstream 单独分支、单独 PR，保证可独立回退。
- P0 全部保留默认值/纯增量日志，回退零副作用。
- P2 不在补齐测试网前启动；一旦启动，按“一次一类职责”小步提交，每步可独立回退。
- 超时类改动（P1-2）须同时复核 agent 超时、SQL 超时、反代 read/send 超时的相对大小，避免反代超时倒挂掩盖后端真实失败。

## 建议推进顺序

1. 先并行落地 **P0-1 / P0-2 / P0-3**（关键且零风险回退）。
2. 接着 **P1-1**（建立前端门禁，后续清理才有抓手）与 **P1-2 / P1-3**。
3. 穿插完成 **P3** 低风险清理。
4. 最后为 **P2** 各项先写设计文档 + 补测试网，再分批重构。
