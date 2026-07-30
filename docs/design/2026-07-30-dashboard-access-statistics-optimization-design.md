# Dashboard 访问统计优化设计

相关实施计划：[Dashboard 访问统计优化实施计划](../plans/2026-07-30-dashboard-access-statistics-optimization-plan.md)

## 背景

`GET /v1/dashboard/statistics` 与 `GET /v1/tables/{id}/access-stats` 当前会在请求线程中读取 Doris 审计表，最多拉取 20 万条包含完整 SQL 的记录，并在 Java 中逐条解析表引用。

当审计量达到每天数万条时，这种实现同时存在三类问题：

- 请求延迟、JDBC 传输量和 JVM 堆占用随审计量增长；
- 20 万条上限无法覆盖 30/90 天窗口，热点与冷表统计失真；
- 审计查询失败会被视为空结果，可能把表误判为长期未使用。

## 目标与范围

- 把原始审计读取和 SQL 解析移出 HTTP 请求。
- 后台默认每 10 分钟增量同步一次，并支持配置关闭。
- MySQL 保存去重状态、同步覆盖范围和按天汇总，两个访问统计接口只读汇总。
- 保持现有总访问口径，同时在汇总层区分读写。
- 历史覆盖不足、同步中、同步关闭或失败时，禁止输出不可靠的冷表结论。
- 同时合并 Dashboard 中重复的执行日志与巡检统计查询。

## 方案

### 数据流

1. 定时任务为每个活动 Doris 集群解析可用审计源。
2. 按时间窗口分批读取新审计记录，提取事件标识、用户、耗时和表引用。
3. 在一个 MySQL 事务中完成事件去重、每日表汇总、每日用户汇总和 checkpoint 推进。
4. Dashboard 与单表统计按日期窗口查询汇总表。
5. 同步成功后失效对应集群的进程内查询缓存。

### 数据模型

- `doris_audit_access_checkpoint`：集群同步游标、覆盖起点、状态、最近成功时间和错误。
- `doris_audit_processed_event`：短期保存事件键，保证重叠窗口和重试幂等。
- `table_access_daily`：按集群、日期、数据库、表汇总总访问/读/写/耗时/首次与最后访问。
- `table_access_user_daily`：按集群、日期、数据库、表、用户汇总访问次数与最后访问。

原始 SQL 不写入 MySQL。事件键优先使用 QueryId；缺失时使用稳定字段计算 SHA-256。

### 同步配置

配置前缀为 `doris.audit-access-sync`：

- `enabled=true`
- `fixed-delay-ms=600000`
- `safety-lag-minutes=2`
- `overlap-minutes=10`
- `batch-size=5000`
- `initial-history-days=90`
- `summary-retention-days=400`
- `processed-event-retention-days=7`

`enabled=false` 时不创建同步任务，也不访问 Doris。已有汇总仍可查询，但状态为 `DISABLED`，不输出冷表。

### 状态与覆盖语义

接口新增统一字段：

- `tableAccessSyncStatus`：`READY`、`BACKFILLING`、`DEGRADED`、`DISABLED`、`UNAVAILABLE`
- `tableAccessCoverageStart`
- `tableAccessCoverageComplete`
- `tableAccessLastSyncedAt`

Dashboard 的完整覆盖窗口为 90 天；单表统计为 `max(30, recentDays, trendDays)`。只有 `READY` 且覆盖完整时才计算冷表。从未访问的表还必须早于冷表阈值创建。

### 失败处理

- 批次失败时 MySQL 事务整体回滚，不推进 checkpoint。
- 已有成功汇总时以 `DEGRADED` 返回旧数据；没有数据时返回 `UNAVAILABLE`。
- 回填或覆盖不足时允许展示明确标注的部分热点/趋势，冷表始终为空。
- 新实现不保留请求时扫描原始审计表的回退路径。

## 接口与兼容性

两个现有 URL、既有字段和默认参数保持不变。新增状态字段为向后兼容扩展；前端缺少这些字段时仍按旧响应渲染。

## 部署与回退

Flyway 只创建门户 MySQL 表，不连接 Doris。应用启动后由后台任务逐步回填。迁移为加法式；回退旧应用时新增表可以保留。

当前部署为单后端实例。若未来横向扩容，必须先引入集群级同步租约或调度 Leader。
