# 工作流自动编排实施计划（Phase 1-3）

> 依据《workflow-auto-orchestration.md》与技术设计文档，拆解首期可交付范围，明确阶段目标、主要任务、责任角色与验收标准。该计划聚焦“定义-发布-执行历史”闭环，支持多工作流与任务管理。

## 1. 里程碑拆解
| 阶段 | 时间（建议） | 目标 | 关键成果 | 依赖 |
| --- | --- | --- | --- | --- |
| Phase 1 - 数据模型与 API | Week 1-2 | 落地核心表结构、MyBatis 实体/Mapper、基础 CRUD API；完成工作流/任务列表最小可用界面 | DB Migration (`data_workflow` 等)、WorkflowService、WorkflowController、任务列表过滤 | DBA 评审、前端交互草图 |
| Phase 2 - 版本 & 发布链路 | Week 3-4 | 打通版本快照、发布记录、Dolphin 发布/上线/下线接口；完成发布审批流集成 | WorkflowVersionService、WorkflowPublishService、Dolphin OpenAPI Client、审批节点配置 | Dolphin Token、网关连通性 |
| Phase 3 - 执行历史与巡检 | Week 5 | 实现 ExecutionHistorySync、工作流详情最近 10 次执行、基础巡检告警 | 同步任务（Scheduler + REST 查询）、缓存表视图、IM 告警模板 | 监控平台对接 |

### 1.1 进度追踪
| 阶段/任务 | 状态 | 说明 |
| --- | --- | --- |
| Phase 1 - DB Schema & API | ✅ 完成 | 表结构、实体、Mapper、Workflow/Task API 以及任务筛选增强均已落地；前端界面仍需开发 |
| Phase 2 - 版本 & 发布 | ✅ 完成 | 版本/发布链路打通，`deploy` 直接推送 Dolphin，审批可选；上线/下线走 OpenAPI |
| Phase 3 - 执行历史 & 巡检 | ⚙️ 进行中 | ExecutionHistorySync Job + `workflow_instance_cache` 已上线，巡检/告警能力尚未实现 |
| Frontend Workflow UI | ⚙️ 进行中 | 需同步 recentInstances、发布记录、`requireApproval` 控件并完成 UI 适配 |
| 审批流集成 | ✅ 可选 | API 已支持审批/回调，当前无需审批可直接 deploy，上线环境可视需求接入 |
| ExecutionHistory 展示 | ⚙️ 进行中 | 后端已缓存 Dolphin 最近实例；仅需前端展示/刷新入口，执行逻辑完全依赖 Dolphin |
| 巡检与 IM 告警 | ⏳ 未启动 | 待结合 cached instances / workflow 数据制定规则与告警通知 |

## 2. 任务分解
### 2.1 Phase 1
1. **数据库设计**
   - 新增 `data_workflow`、`workflow_task_relation`、`workflow_version`、`workflow_publish_record`、`workflow_instance_cache` 表及索引；
   - 评审字段约束、默认值、JSON 字段兼容性。
2. **后端实现**
   - 实体/Mapper/Service/Controller 代码骨架；
   - 工作流列表、详情、创建、更新、删除 API；
   - 任务列表按工作流/上下游查询 API（依赖 `task_dependency_view`）。
3. **前端/交互**
   - 工作流列表 & 任务列表视图；
   - 表单校验、任务绑定交互。
4. **验收**：可通过 API 创建/更新工作流，查看任务绑定及版本信息。

### 2.2 Phase 2
1. **版本快照**：生成结构快照、Diff 摘要、回滚接口。
2. **发布服务**：封装 Dolphin Java Gateway + REST API，支持 `deploy/online/offline`；写入 `workflow_publish_record`。
3. **审批集成**：与现有审批流打通，新增 `workflow_publish`、`workflow_migrate` 操作类型。
4. **验收**：从平台创建工作流 → 审批 → 发布上线 → Dolphin 中可见并可调度。

### 2.3 Phase 3
1. **执行历史同步**：通过 Dolphin API 拉取实例状态写入缓存（仅读取，不再自建调度），供前端展示最近执行。
2. **巡检**：任务归属缺失、跨工作流依赖未确认等规则落地。
3. **监控告警**：Prometheus + IM，覆盖发布失败、实例连续失败。
4. **验收**：工作流详情展示最近执行历史，巡检可触达责任人。

## 3. 角色与责任
| 角色 | 职责 |
| --- | --- |
| 架构/后端负责人 | 把控整体方案、代码评审、与 Dolphin 团队对齐 API | 
| 后端开发 | DB 脚本、Service/Controller 开发、集成 Dolphin API |
| 前端开发 | Workflow/Task 列表、详情、表单 UI | 
| 测试/QA | 制定用例、联调审批/发布/执行、回归巡检 | 
| 运维 | 环境配置、Dolphin Token & Gateway、监控接入 | 

## 4. 风险与缓解
- **Dolphin API 变更**：与调度团队建立提前通知机制，封装客户端统一适配；
- **JSON 字段兼容**：对 `definition_json`、`node_attrs` 等字段提供回退策略，必要时使用 TEXT 存储；
- **跨团队协同**：设立周会同步进度，重要需求使用文档与 issue 跟踪；
- **人力冲突**：关键岗位设置备份，确保交付不因单人离岗阻塞。

> 该计划将在实施过程中滚动更新，结合燃尽图与周报追踪每个阶段的完成度，确保“多工作流 + 发布 + 执行历史”能力按期上线。
