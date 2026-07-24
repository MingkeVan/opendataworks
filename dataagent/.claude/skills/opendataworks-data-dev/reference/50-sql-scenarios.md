# 场景 SQL 模板（数据加工）

常见数据开发场景的 SQL 起手式。先用 `portal_search_tables` / `portal_get_table_ddl` 核实来源表与目标表结构,再套用模板;**所有 DML 落任务前必须经 `portal_analyze_sql` 验证通过**(见 SKILL.md 建任务步骤)。

约定:分层 `ods/dwd/dws/dim/ads`;刷新 `di`=按日增量、`df`=按日全量;时间/分区占位(如 `${bizdate}`)按平台调度参数约定替换。目标表若不存在,先用 `portal_create_table` 建表(不要建"DDL 任务")。

## 1. 每日增量加工（di,写入当日分区）

来源:上游明细/日志;目标:`dwd` 明细分区表。

```sql
INSERT OVERWRITE TABLE `dwd`.`dwd_user_order_di` PARTITION (dt = '${bizdate}')
SELECT o.order_id, o.user_id, o.amount, o.status
FROM `ods`.`ods_order_log_di` o
WHERE o.dt = '${bizdate}';
```

## 2. 每日全量快照（df,重刷全表/当日快照)

来源:业务全量表;目标:`dwd`/`dim` 全量表。

```sql
INSERT OVERWRITE TABLE `dim`.`dim_user_df` PARTITION (dt = '${bizdate}')
SELECT u.user_id, u.nickname, u.city, u.reg_time
FROM `ods`.`ods_user_df` u
WHERE u.dt = '${bizdate}';
```

## 3. 聚合汇总（dws,GROUP BY 指标）

来源:`dwd` 明细;目标:`dws` 轻度汇总表。

```sql
INSERT OVERWRITE TABLE `dws`.`dws_user_order_1d` PARTITION (dt = '${bizdate}')
SELECT user_id,
       COUNT(1)        AS order_cnt,
       SUM(amount)     AS order_amt
FROM `dwd`.`dwd_user_order_di`
WHERE dt = '${bizdate}'
GROUP BY user_id;
```

## 4. 多表关联（join 拉宽）

来源:多张 `dwd`/`dim`;目标:宽表。

```sql
INSERT OVERWRITE TABLE `dwd`.`dwd_order_wide_di` PARTITION (dt = '${bizdate}')
SELECT o.order_id, o.user_id, u.city, o.amount
FROM `dwd`.`dwd_user_order_di` o
LEFT JOIN `dim`.`dim_user_df` u
  ON o.user_id = u.user_id AND u.dt = '${bizdate}'
WHERE o.dt = '${bizdate}';
```

## 5. 主键去重 / upsert(UNIQUE KEY 表)

目标为 Doris `UNIQUE KEY` 表时,同键写入即覆盖,直接 INSERT 即可(无需自行去重逻辑)。

```sql
INSERT INTO `dwd`.`dwd_user_profile`  -- UNIQUE KEY(user_id)
SELECT user_id, nickname, city, update_time
FROM `ods`.`ods_user_profile_di`
WHERE dt = '${bizdate}';
```

## 验证要点(落任务前)

- `portal_analyze_sql` 解析成功,输入/输出表可解析、无 error 级风险。
- 目标表已存在(否则先 `portal_create_table`);字段与来源可对齐。
- SELECT 逻辑可用 `portal_query_readonly` 只读试跑抽样核对;**含写操作的整段 SQL 不试跑**,只落任务定义。
