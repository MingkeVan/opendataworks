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

## 后续修正（2026-07-31）

首次实现落地后的评审发现若干正确性与成本问题，按“不加表、不加迁移、不改接口”的原则做最小修正。

### 取消历史回填，只做增量累积

原实现启动时按 `initial-history-days`（默认 90 天）回溯审计表，并用两条探测查询确定可用起点。这是本特性对客户 Doris 唯一的重查询，且 Doris `audit_log` 自身的分区保留期通常远短于 90 天，实际回填量本来就有限。

现在覆盖起点即同步启动时间，`BACKFILLING` 退化为首轮几秒内的过渡态。冷热判断仍由 `coverageStart` 门控自动生效：约 30 天后热点排名可用，约 90 天后冷表结论开启，期间界面显示覆盖起点与暂停原因。

`initial-history-days` 属性、对应环境变量与 `queryAvailableStart()` 一并删除。副作用是每轮最多消费 10 分钟的审计日志，单轮执行预算与批量写入不再是瓶颈。

checkpoint 丢失时一律从当前安全上界重新开始。汇总表的最早日期只能证明那天存在一条记录，不能证明此后连续完整；据此声明覆盖会把中间未汇总的区间当成已覆盖，并可能给出不可靠的冷表结论。宁可重新攒满窗口。

不为存量 checkpoint 做自动升级推断。这是非核心统计，不值得为了无损升级引入状态迁移逻辑：发布时一次性清空 `doris_audit_access_checkpoint`，接受重新积累。

### 水位改用审计源时钟

安全上界原本取后端 JVM 的 `LocalDateTime.now()`，与审计表中的挂钟时间比较。后端与 Doris FE 之间的时区或时钟差一旦超过 overlap 窗口，水位会越过尚未读取的事件且永不回头，属于静默丢数据。

现在每轮在同一集群上执行一次 `SELECT NOW()`，以审计源自身的时间减去安全延迟作为上界；偏差超过 overlap 窗口时记录告警。使用不带精度参数的 `NOW()` 以覆盖较老的 Doris 版本。

水位保持单调：当源时间早于已保存水位（时钟回拨）时保留原游标并直接降级返回，不写回更早的水位。否则空批次会把水位回退，之后重复扫描；去重记录过期后还会重复累计。

### 解析阶段排除系统库

`DorisAuditSqlTableParser` 丢弃 `information_schema`、`__internal_schema`、`doris_audit_db__`、`mysql`、`sys`、`performance_schema` 下的引用。两种已知审计源布局都位于上述库中，因此同步任务不会再把自己每轮的审计查询写成永久热点。

### Dashboard 聚合窗口收敛

原查询按 `max(coldDays, summary-retention-days)` 回看约 400 天。更早的访问记录不会改变冷热分类：窗口内无记录的表 `lastAccessTime` 为空，随后回退到建表时间判断，结论一致。窗口改为热点自然日窗口与冷表阈值所在自然日中较早的一个，代价仅是极冷表的“最后访问时间”展示为空。

冷表阈值是精确时刻而非自然日，因此聚合起点必须取 `coldThreshold.toLocalDate()`：若按 `today - coldDays + 1` 取整，阈值当天晚些时候发生的访问会落在窗口外，把活跃表误判成冷表。

### 关闭同步不再停止数据清理

清理任务原先与同步任务共用一个 `@ConditionalOnProperty` bean，`DORIS_AUDIT_ACCESS_SYNC_ENABLED=false` 会连保留期一起停掉。现在 bean 无条件注册，仅在同步方法内部按开关短路，清理照常执行。同步入口补充顶层异常边界。

### 失败处理保持简单

同步失败时保留原有水位与覆盖起点，状态按既有规则标记 `DEGRADED`/`UNAVAILABLE`，下一轮从原游标继续。

已知取舍：从 `DEGRADED` 恢复时按非 `READY` 路径续读，不减去 overlap 窗口，故障前最后几分钟迟到的事件可能漏掉；追平前界面显示「历史回填中」。对访问频次统计而言这是噪音级偏差，暂不引入额外状态以换取精确性。

### 解析范围的边界

系统库之外，解析器屏蔽字符串字面量与注释，并排除 CTE 名称（仅限无库名限定的引用，带库名的同名表仍视为真实资产）。转义引号必须整体跳过，否则字面量会提前结束，残留引号开启的新“字面量”会把后面真正的表引用整段吞掉。

解析器到此为止：不做元数据表 allow-list，不引入完整 SQL AST，也不再扩展边缘语法。摄入期 allow-list 另有代价——只有事件消费时已纳管的表才有历史，之后纳管的存量表会因缺少汇总而回退到建表时间判断被误报为冷表，且审计日志过期后无法自愈。在线查询仍然只返回 `data_table` 中的资产，残留行不会出现在界面上。

### 其他

`DorisAuditAccessCheckpoint` 的主键显式声明为 `IdType.INPUT`，与仓库内其他非自增主键实体一致。
