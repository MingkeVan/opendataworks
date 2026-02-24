# Changelog

本文件记录 OpenDataWorks 的对外版本变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Changed
- 待补充。

## [0.7.0] - 2026-02-24

### Added
- 新增 MinIO 环境管理能力。
- 新增 Schema 备份配置、备份快照与恢复能力。
- 新增自动备份任务与配置项，支持备份流程集成。

### Changed
- 配置管理页面接入 MinIO 管理与 Schema 备份管理面板。

## [0.6.2] - 2026-02-24

### Changed
- 支持 `update-only` SQL lineage 更新策略。
- 加严 Dolphin 边关系校验逻辑，提升运行时同步一致性检查。

## [0.6.1] - 2026-02-13

### Changed
- 对齐 workflow version compare 与 export parity sync 逻辑。
- 增强版本比对持久化与运行时同步相关测试覆盖。

## [0.6.0] - 2026-02-13

### Added
- 完成 runtime sync 与版本治理端到端能力（预览、执行、差异、记录等）。

### Changed
- 工作流版本管理、回滚与比对链路增强。

## [0.5.0] - 2026-02-12

### Added
- DataStudio 新增 SQL 信息面板与语句级风险执行能力。

## [0.4.2] - 2026-02-12

### Fixed
- 修复分区元数据同步与超时相关问题。
- 统一分区命名字段，减少前后端字段不一致问题。

## [0.4.1] - 2026-02-12

### Changed
- 改进 DataStudio 血缘交互体验。
- 优化 Doris 元数据同步流程。

## [0.4.0] - 2026-02-11

### Added
- 新增以任务表为中心的血缘图视图能力。

### Changed
- 优化任务 SQL 自动解析与高亮交互。

### Fixed
- 支持手动 SQL 重新分析与表详情相关修复。

## 早期版本

- `0.3.7`、`0.3.6`、`0.3.5`、`0.3.4`、`0.3.3`、`0.3.2`、`0.3.1`、`0.3.0`
- `0.2.1`、`0.2.0`、`0.1.0`、`0.0.5`、`0.0.4`、`0.0.3`、`0.0.2`、`0.0.1`

以上早期版本请参考 GitHub Releases 与 Git tags 历史记录。

[Unreleased]: https://github.com/MingkeVan/opendataworks/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/MingkeVan/opendataworks/releases/tag/v0.7.0
[0.6.2]: https://github.com/MingkeVan/opendataworks/releases/tag/v0.6.2
[0.6.1]: https://github.com/MingkeVan/opendataworks/releases/tag/v0.6.1
[0.6.0]: https://github.com/MingkeVan/opendataworks/releases/tag/v0.6.0
[0.5.0]: https://github.com/MingkeVan/opendataworks/releases/tag/v0.5.0
[0.4.2]: https://github.com/MingkeVan/opendataworks/releases/tag/v0.4.2
[0.4.1]: https://github.com/MingkeVan/opendataworks/releases/tag/v0.4.1
[0.4.0]: https://github.com/MingkeVan/opendataworks/releases/tag/v0.4.0
