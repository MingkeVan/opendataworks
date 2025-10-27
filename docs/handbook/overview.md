# 产品概览

OpenDataWorks 是一个统一的数据资产门户，聚合了数据表建模、任务编排、血缘可视化与执行治理能力。GitHub 仓库、镜像命名均沿用 `opendataworks`，确保生态统一。

## 愿景与价值

- **统一资产视角**：让表、任务、血缘、执行状态在一个界面中被创建、被引用和被追踪。
- **降低调度门槛**：用表驱动任务定义，通过 Portal 一键下发到 DolphinScheduler（批）与 Dinky（流）。
- **治理即服务**：内置巡检规则、命名规范、领域划分，减少重复劳动并保留审计线索。
- **开箱即用**：提供数据库脚本、Docker Compose、站点文档与演示数据，开发者 30 分钟即可完成端到端验证。

## 角色画像

| 角色 | 关心点 | 对应能力 |
| --- | --- | --- |
| 数据开发 | 表/字段规范、SQL 模板、任务生命周期 | 表管理、任务建模、代码片段库 |
| 调度/运维 | 稳定部署、容器镜像、监控告警 | Docker Compose、systemd、健康检查、日志目录 |
| 数据治理/质量 | 命名规范、巡检结果、执行回溯 | 命名规则引擎、巡检规则、执行日志、血缘视图 |
| 管理者/产品 | 路线图、交付物、价值指标 | 阶段性目标、功能矩阵、质量报告 |

## 版本节奏

| 阶段 | 交付能力 | 主要内容 |
| --- | --- | --- |
| **Phase 1 (当前)** | 批任务统一管理 | 数据表/字段/任务建模、DolphinScheduler 集成、血缘图、执行监控、基础巡检 |
| **Phase 2** | 流任务扩展 | Dinky 集成、血缘自动生成、任务策略增强、数据质量监控、Cron 可视化 |
| **Phase 3** | 治理与协作 | RBAC、多租户、审批流、告警中心、指标看板、AI SQL 辅助 |

## 术语对齐

| 概念 | 说明 | 备注 |
| --- | --- | --- |
| **数据门户 (Portal)** | Spring Boot + Vue3 主应用，保存所有表/任务元数据 | repo: `opendataworks` |
| **DolphinScheduler Service** | FastAPI 适配层，屏蔽不同版本 API 差异 | 路径 `dolphinscheduler-service/` |
| **Dinky Gateway** | 预留的流任务接口，将在 Phase 2 解锁 | 目录 `dinky/` (上游源码) |
| **OpenDataWorks DB** | 统一数据库 `opendataworks`，默认账号 `opendataworks/opendataworks123` | 初始化脚本见 `database/mysql` |
| **业务域** | 组织视角 (tech、crm、trade …) | 写入 `business_domain` 表 |
| **数据域** | 数据主题视角 (ops、user …) | 与业务域多对一关联 |
| **数据分层** | ODS/DWD/DIM/DWS/ADS | 决定命名、统计周期、指标粒度 |

## 快速进场指南

1. **了解故事线**：阅读本文件 + [architecture.md](architecture.md)。
2. **初始化数据库**：执行 `scripts/dev/init-database.sh`（可一键建库/授权/导入示例数据）。
3. **启动三端服务**：按照 [development-guide.md](development-guide.md) 依次启动后端、Python 适配层、前端。
4. **加载演示案例**：登录前端使用 `admin/admin123`（示例账号）体验表-任务-血缘的全链条。
5. **扩展/定制**：参考 [operations-guide.md](operations-guide.md) 建立自己的部署方式，再查看 [features](features/) 下的专题文档做二次开发。
