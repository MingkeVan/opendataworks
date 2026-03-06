# 运行时元数据与数据源说明

先结论：只有在 `SKILL.md + 00 + 10 + 11 + 20/21/22` 仍然不能消除具体疑问时，才需要阅读本页或执行脚本。

## 何时需要本页

- 需要确定候选数据库、表、字段
- 需要确认上下游血缘
- 需要确认目标数据库落在 MySQL 还是 Doris
- 需要解释为什么脚本顺序必须先 metadata 再 datasource 再 SQL

## 推荐脚本入口

- [`scripts/inspect_metadata.py`](../scripts/inspect_metadata.py)
- [`scripts/resolve_datasource.py`](../scripts/resolve_datasource.py)
- [`scripts/query_opendataworks_metadata.py`](../scripts/query_opendataworks_metadata.py)

## 使用原则

- 数据源账号密码只在脚本内部使用，不要回写到最终回答。
- `resolve_datasource.py` 只负责确认引擎与数据源元信息。
- `run_sql.py` 会在执行前再次解析数据源，因此不要把 datasource 结果当成最终凭证输出。

## 重点平台表

### 表与字段

- `data_table`
  - 库、表、集群归属
- `data_field`
  - 字段名、字段类型、字段说明

### 血缘

- `data_lineage`
  - 上游表、下游表、血缘类型

### 数据源

- `doris_cluster`
  - 引擎类型、连接主机、端口、默认账号
- `doris_database_users`
  - 数据库级只读账号

## 原始查询示例

### 表与字段

```python
import pymysql

conn = pymysql.connect(
    host=host,
    port=port,
    user=user,
    password=password,
    database="opendataworks",
    charset="utf8mb4",
    cursorclass=pymysql.cursors.DictCursor,
)

with conn.cursor() as cur:
    cur.execute(
        """
        SELECT dt.id, dt.cluster_id, dt.db_name, dt.table_name, dt.table_comment,
               df.field_name, df.field_type, df.field_comment
        FROM data_table dt
        LEFT JOIN data_field df ON df.table_id = dt.id AND df.deleted = 0
        WHERE dt.deleted = 0
          AND (dt.status IS NULL OR dt.status <> 'deprecated')
        ORDER BY dt.db_name, dt.table_name, df.field_order, df.id
        """
    )
    rows = cur.fetchall()
```

### 血缘

```python
with conn.cursor() as cur:
    cur.execute(
        """
        SELECT dl.id, dl.lineage_type,
               ut.db_name AS upstream_db, ut.table_name AS upstream_table,
               dt.db_name AS downstream_db, dt.table_name AS downstream_table
        FROM data_lineage dl
        LEFT JOIN data_table ut ON ut.id = dl.upstream_table_id AND ut.deleted = 0
        LEFT JOIN data_table dt ON dt.id = dl.downstream_table_id AND dt.deleted = 0
        WHERE dl.deleted = 0
        ORDER BY dl.id
        """
    )
    rows = cur.fetchall()
```

### 数据源

```python
with conn.cursor() as cur:
    cur.execute(
        """
        SELECT dt.db_name, dt.cluster_id, dc.source_type, dc.fe_host, dc.fe_port, dc.username, dc.password,
               du.readonly_username, du.readonly_password
        FROM data_table dt
        LEFT JOIN doris_cluster dc ON dc.id = dt.cluster_id AND dc.deleted = 0
        LEFT JOIN doris_database_users du ON du.cluster_id = dt.cluster_id AND du.database_name = dt.db_name
        WHERE dt.deleted = 0
          AND dt.db_name = %s
        LIMIT 20
        """,
        ("doris_ods",),
    )
    rows = cur.fetchall()
```
