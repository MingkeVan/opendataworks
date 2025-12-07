# OpenDataWorks 手册

> 本目录集中了一线研发、运维、数据治理同学在 OpenDataWorks（仓库 `opendata-works`）项目中的最佳实践。无论你负责产品规划、数据库初始化、测试治理还是部署运维，都可以在这里找到唯一可信来源 (Single Source of Truth)。

## 导航

| 主题 | 内容 | 路径 |
| --- | --- | --- |
| 产品与规划 | 愿景、价值、版本节奏、术语对齐 | [overview.md](overview.md) |
| 架构与组件 | 系统边界、服务拆分、DolphinScheduler/Dinky 集成、运行态观察 | [architecture.md](architecture.md) |
| 数据模型 & SQL | 命名规范、核心表说明、初始化/种子/测试 SQL、变更策略 | [data-model-and-sql.md](data-model-and-sql.md) |
| 开发指南 | 环境要求、数据库初始化脚本、前后端一键启动、调试排查 | [development-guide.md](development-guide.md) |
| 运维部署 | Docker Compose、离线包、系统服务 (systemd) 、镜像构建、配置清单 | [operations-guide.md](operations-guide.md) |
| 测试与质量 | 手工验证脚本、工作流生命周期回归、SQL 支持矩阵、浏览器测试、缺陷追踪 | [testing-guide.md](testing-guide.md) |
| 特性专题 | Doris 统计增强、任务执行状态、表单优化、图表实现等专题说明 | [features/](features) |
| 历史记录 | 修复报告、浏览器测试结果、工单复盘 | [../reports](../reports) |

## 使用建议

1. **入门三步**: 阅读 `overview` → 初始化数据库 (`scripts/dev/init-database.sh`) → 参考 `development-guide` 启动三套服务。
2. **遇到环境差异**: `data-model-and-sql` 汇总了所有数据库脚本以及变更策略，优先查看该文档再改 SQL。
3. **上线/巡检**: `operations-guide` 与 `testing-guide` 覆盖部署、巡检、连通性验证的流程，可直接复用。
4. **专题文档**: 老的 `docs/design|guides|deployment` 目录均已合并进本手册，如需历史版本可在 Git 历史中查看。
