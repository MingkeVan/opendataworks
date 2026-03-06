from __future__ import annotations

import argparse
import time

import pymysql

from _opendataworks_runtime import (
    ensure_read_only,
    env_int,
    error_payload,
    print_json,
    resolve_datasource,
    serializable_rows,
)


def main():
    parser = argparse.ArgumentParser(description="Execute read-only SQL against a resolved datasource")
    parser.add_argument("--database", required=True)
    parser.add_argument("--engine", default="")
    parser.add_argument("--sql", required=True)
    parser.add_argument("--limit", type=int, default=env_int("DATAAGENT_QUERY_LIMIT", 100))
    args = parser.parse_args()

    database = str(args.database or "").strip()
    preferred_engine = str(args.engine or "").strip().lower() or None
    sql = str(args.sql or "").strip()
    limit = max(1, int(args.limit or 100))

    try:
        ensure_read_only(sql)
        resolved = resolve_datasource(database, preferred_engine=preferred_engine)

        started_at = time.perf_counter()
        conn = pymysql.connect(
            host=resolved["host"],
            port=int(resolved["port"]),
            user=resolved["user"],
            password=resolved["password"],
            database=resolved["database"],
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            read_timeout=60,
            write_timeout=60,
        )
        try:
            with conn.cursor() as cur:
                cur.execute(sql)
                rows = list(cur.fetchmany(limit + 1) or [])
        finally:
            conn.close()

        duration_ms = int((time.perf_counter() - started_at) * 1000)
        has_more = len(rows) > limit
        preview_rows = rows[:limit]
        serialized_rows = serializable_rows(preview_rows)
        columns = list(serialized_rows[0].keys()) if serialized_rows else []

        print_json(
            {
                "kind": "sql_execution",
                "tool_label": "SQL 执行",
                "engine": resolved["engine"],
                "database": resolved["database"],
                "sql": sql,
                "columns": columns,
                "rows": serialized_rows,
                "row_count": len(serialized_rows),
                "has_more": has_more,
                "duration_ms": duration_ms,
                "summary": f"返回 {len(serialized_rows)} 行结果",
                "error": None,
            }
        )
    except Exception as exc:
        print_json(
            error_payload(
                "sql_execution",
                str(exc),
                tool_label="SQL 执行",
                database=database,
                engine=preferred_engine,
                sql=sql,
                columns=[],
                rows=[],
                row_count=0,
                has_more=False,
                duration_ms=0,
            )
        )


if __name__ == "__main__":
    main()
