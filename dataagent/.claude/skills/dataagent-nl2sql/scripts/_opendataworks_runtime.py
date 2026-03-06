from __future__ import annotations

import json
import os
import re
import sys
from datetime import date, datetime
from decimal import Decimal
from typing import Any

import pymysql

READ_ONLY_PREFIXES = ("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN")


def env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


def odw_schema() -> str:
    return str(os.getenv("ODW_MYSQL_DATABASE", "opendataworks")).strip() or "opendataworks"


def connect_odw():
    return pymysql.connect(
        host=str(os.getenv("ODW_MYSQL_HOST", "localhost")).strip(),
        port=env_int("ODW_MYSQL_PORT", 3306),
        user=str(os.getenv("ODW_MYSQL_USER", "root")).strip(),
        password=str(os.getenv("ODW_MYSQL_PASSWORD", "")),
        database=odw_schema(),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )


def query_rows(conn, sql: str, params: tuple[Any, ...] | list[Any] | None = None) -> list[dict[str, Any]]:
    with conn.cursor() as cur:
        cur.execute(sql, params or [])
        return list(cur.fetchall() or [])


def serializable_value(value: Any) -> Any:
    if value is None or isinstance(value, (int, float, str, bool)):
        return value
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    return str(value)


def serializable_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {str(key): serializable_value(val) for key, val in dict(row).items()}
        for row in rows
    ]


def print_json(payload: dict[str, Any]):
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def error_payload(kind: str, message: str, **extra: Any) -> dict[str, Any]:
    payload = {"kind": kind, "error": message}
    payload.update(extra)
    return payload


def ensure_read_only(sql: str):
    statement = str(sql or "").strip()
    if not statement:
        raise ValueError("SQL 为空")
    upper = statement.lstrip().upper()
    if not upper.startswith(READ_ONLY_PREFIXES):
        raise ValueError("仅允许只读 SQL")
    if re.search(r"(^|\\s)(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|REPLACE)\\b", upper):
        raise ValueError("检测到非只读关键字")


def resolve_datasource(database: str, preferred_engine: str | None = None) -> dict[str, Any]:
    target_database = str(database or "").strip()
    if not target_database:
        raise ValueError("database 不能为空")

    schema = odw_schema()
    if target_database == schema:
        engine = "mysql"
        if preferred_engine and preferred_engine != engine:
            raise ValueError(f"database `{target_database}` 与 {preferred_engine} 引擎不匹配")
        return {
            "engine": engine,
            "database": target_database,
            "host": str(os.getenv("ODW_MYSQL_HOST", "localhost")).strip(),
            "port": env_int("ODW_MYSQL_PORT", 3306),
            "user": str(os.getenv("ODW_MYSQL_USER", "root")).strip(),
            "password": str(os.getenv("ODW_MYSQL_PASSWORD", "")),
            "source_type": "MYSQL",
            "cluster_id": None,
            "cluster_name": "platform-mysql",
            "resolved_by": "platform_runtime",
        }

    conn = connect_odw()
    try:
        rows = query_rows(
            conn,
            """
            SELECT
                dt.cluster_id,
                COALESCE(dc.cluster_name, CONCAT('cluster-', dt.cluster_id)) AS cluster_name,
                COALESCE(NULLIF(dc.source_type, ''), 'DORIS') AS source_type,
                dc.fe_host,
                dc.fe_port,
                dc.username,
                dc.password
            FROM data_table dt
            INNER JOIN doris_cluster dc ON dc.id = dt.cluster_id
            WHERE dt.deleted = 0
              AND dc.deleted = 0
              AND dt.db_name = %s
              AND (dt.status IS NULL OR dt.status <> 'deprecated')
            GROUP BY
              dt.cluster_id,
              dc.cluster_name,
              dc.source_type,
              dc.fe_host,
              dc.fe_port,
              dc.username,
              dc.password
            ORDER BY dt.cluster_id ASC
            LIMIT 2
            """,
            (target_database,),
        )
        if len(rows) > 1:
            raise ValueError(f"database `{target_database}` 命中了多个 cluster_id")

        row = dict(rows[0]) if rows else None
        resolved_by = "data_table"

        if row is None:
            rows = query_rows(
                conn,
                """
                SELECT
                    du.cluster_id,
                    COALESCE(dc.cluster_name, CONCAT('cluster-', du.cluster_id)) AS cluster_name,
                    COALESCE(NULLIF(dc.source_type, ''), 'DORIS') AS source_type,
                    dc.fe_host,
                    dc.fe_port,
                    dc.username,
                    dc.password
                FROM doris_database_users du
                INNER JOIN doris_cluster dc ON dc.id = du.cluster_id
                WHERE dc.deleted = 0
                  AND du.database_name = %s
                GROUP BY
                  du.cluster_id,
                  dc.cluster_name,
                  dc.source_type,
                  dc.fe_host,
                  dc.fe_port,
                  dc.username,
                  dc.password
                ORDER BY du.cluster_id ASC
                LIMIT 2
                """,
                (target_database,),
            )
            if len(rows) > 1:
                raise ValueError(f"database `{target_database}` 在 doris_database_users 中命中了多个 cluster_id")
            row = dict(rows[0]) if rows else None
            resolved_by = "doris_database_users"

        if row is None:
            raise ValueError(f"未在 opendataworks 中找到 database `{target_database}` 的数据源")

        engine = "mysql" if str(row.get("source_type") or "").upper() == "MYSQL" else "doris"
        if preferred_engine and preferred_engine != engine:
            raise ValueError(f"database `{target_database}` 与 {preferred_engine} 引擎不匹配")

        user = str(row.get("username") or "").strip()
        password = str(row.get("password") or "")

        if engine == "doris":
            readonly_rows = query_rows(
                conn,
                """
                SELECT readonly_username, readonly_password
                FROM doris_database_users
                WHERE cluster_id = %s
                  AND database_name = %s
                LIMIT 2
                """,
                (row.get("cluster_id"), target_database),
            )
            if len(readonly_rows) > 1:
                raise ValueError(f"database `{target_database}` 命中了多个只读账号")
            if readonly_rows:
                readonly_user = str(readonly_rows[0].get("readonly_username") or "").strip()
                if readonly_user:
                    user = readonly_user
                    password = str(readonly_rows[0].get("readonly_password") or "")
                    resolved_by = "readonly_user"

        return {
            "engine": engine,
            "database": target_database,
            "host": str(row.get("fe_host") or "").strip(),
            "port": int(row.get("fe_port") or (3306 if engine == "mysql" else 9030)),
            "user": user,
            "password": password,
            "source_type": str(row.get("source_type") or ""),
            "cluster_id": row.get("cluster_id"),
            "cluster_name": str(row.get("cluster_name") or ""),
            "resolved_by": resolved_by,
        }
    finally:
        conn.close()


def load_json_input(raw: str | None = None, file_path: str | None = None) -> Any:
    if raw:
        return json.loads(raw)
    if file_path:
        with open(file_path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    data = sys.stdin.read().strip()
    if not data:
        raise ValueError("未提供 JSON 输入")
    return json.loads(data)
