from __future__ import annotations

"""
Tool Runtime
- native: 本地直接执行 MySQL / Doris 工具
- mcp_http: 通过 HTTP 转发到 MCP 网关
"""

import json
import logging
import time
import urllib.error
import urllib.request
from typing import Any

import pymysql

from config import get_settings

logger = logging.getLogger(__name__)

_READ_ONLY_PREFIXES = ("SELECT", "SHOW", "DESC", "DESCRIBE", "EXPLAIN", "WITH")


class ToolRuntimeError(RuntimeError):
    pass


def list_tools() -> list[dict[str, Any]]:
    return [
        {
            "name": "mysql.query",
            "description": "查询 MySQL（只读）",
            "input": {
                "database": "schema 名称",
                "sql": "SELECT/SHOW/DESC/EXPLAIN 语句",
                "params": "SQL 参数列表（可选）",
                "limit": "结果上限（可选）",
            },
        },
        {
            "name": "doris.query",
            "description": "查询 Doris（只读）",
            "input": {
                "database": "database 名称（可选）",
                "sql": "SELECT 语句",
                "limit": "fetch 行数上限（可选）",
            },
        },
    ]


def invoke_tool(tool_name: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    payload = payload or {}
    cfg = get_settings()
    mode = (cfg.tool_runtime_mode or "native").strip().lower()

    if mode == "mcp_http":
        return _invoke_via_mcp_http(tool_name, payload)
    if mode != "native":
        raise ToolRuntimeError(f"Unsupported tool_runtime_mode: {cfg.tool_runtime_mode}")

    if tool_name == "mysql.query":
        return _native_mysql_query(payload)
    if tool_name == "doris.query":
        return _native_doris_query(payload)
    raise ToolRuntimeError(f"Unknown tool: {tool_name}")


def _invoke_via_mcp_http(tool_name: str, payload: dict[str, Any]) -> dict[str, Any]:
    cfg = get_settings()
    endpoint = (cfg.mcp_http_endpoint or "").strip()
    if not endpoint:
        raise ToolRuntimeError("mcp_http_endpoint is empty")

    req_body = json.dumps(
        {"tool_name": tool_name, "input": payload},
        ensure_ascii=False,
    ).encode("utf-8")
    req = urllib.request.Request(
        endpoint,
        data=req_body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )

    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=cfg.mcp_http_timeout_seconds) as resp:
            content = resp.read().decode("utf-8")
        duration_ms = int((time.time() - start) * 1000)
        body = json.loads(content) if content else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="ignore") if hasattr(e, "read") else str(e)
        raise ToolRuntimeError(f"MCP HTTP error: {e.code} {detail}") from e
    except Exception as e:
        raise ToolRuntimeError(f"MCP invoke failed: {e}") from e

    if isinstance(body, dict):
        if body.get("ok") is False or body.get("success") is False:
            raise ToolRuntimeError(body.get("error") or body.get("message") or "MCP tool failed")
        data = body.get("data", body.get("result", body))
    else:
        data = body

    logger.info("Tool invoke via mcp_http, tool=%s, duration_ms=%d", tool_name, duration_ms)
    if isinstance(data, dict):
        return data
    return {"result": data, "duration_ms": duration_ms}


def _native_mysql_query(payload: dict[str, Any]) -> dict[str, Any]:
    cfg = get_settings()
    sql = str(payload.get("sql") or "").strip()
    if not sql:
        raise ToolRuntimeError("mysql.query sql is empty")
    _ensure_read_only(sql)

    database = str(payload.get("database") or cfg.mysql_database or "").strip()
    params = payload.get("params") or []
    limit = _to_positive_int(payload.get("limit"))

    conn = None
    start = time.time()
    try:
        conn = pymysql.connect(
            host=cfg.mysql_host,
            port=cfg.mysql_port,
            user=cfg.mysql_user,
            password=cfg.mysql_password,
            database=database if database else None,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            connect_timeout=10,
            read_timeout=60,
            write_timeout=30,
        )
        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()

        has_more = False
        if limit and len(rows) > limit:
            rows = rows[:limit]
            has_more = True

        return {
            "rows": rows,
            "row_count": len(rows),
            "has_more": has_more,
            "duration_ms": int((time.time() - start) * 1000),
        }
    except Exception as e:
        raise ToolRuntimeError(f"mysql.query failed: {e}") from e
    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass


def _native_doris_query(payload: dict[str, Any]) -> dict[str, Any]:
    cfg = get_settings()
    sql = str(payload.get("sql") or "").strip()
    if not sql:
        raise ToolRuntimeError("doris.query sql is empty")
    _ensure_read_only(sql)

    database = str(payload.get("database") or cfg.doris_database or "").strip()
    limit = _to_positive_int(payload.get("limit"))

    conn = None
    start = time.time()
    try:
        conn = pymysql.connect(
            host=cfg.doris_host,
            port=cfg.doris_port,
            user=cfg.doris_user,
            password=cfg.doris_password,
            database=database if database else None,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            connect_timeout=10,
            read_timeout=60,
            write_timeout=30,
        )
        with conn.cursor() as cur:
            cur.execute(sql)
            if limit:
                rows = cur.fetchmany(limit)
            else:
                rows = cur.fetchall()

        return {
            "rows": rows,
            "row_count": len(rows),
            "duration_ms": int((time.time() - start) * 1000),
        }
    except Exception as e:
        raise ToolRuntimeError(f"doris.query failed: {e}") from e
    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass


def _ensure_read_only(sql: str):
    upper_sql = sql.lstrip().upper()
    if upper_sql.startswith(_READ_ONLY_PREFIXES):
        return
    raise ToolRuntimeError("Only read-only SQL is allowed for tool runtime")


def _to_positive_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        iv = int(value)
        return iv if iv > 0 else None
    except Exception:
        return None
