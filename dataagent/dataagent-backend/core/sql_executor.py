from __future__ import annotations

"""
SQL 执行器 — 按目标数据源执行生成的 SQL 并返回结果
"""

import logging
from typing import Any

from core.tool_runtime import ToolRuntimeError, run_query
from models.schemas import SqlExecutionResult

logger = logging.getLogger(__name__)


def execute_sql(
    sql: str,
    database: str | None = None,
    limit: int | None = None,
) -> SqlExecutionResult:
    """按 skills 映射选择 MySQL / Doris 执行 SQL 并返回结果"""
    db = str(database or "").strip()
    max_rows = limit or 100

    if not sql.strip():
        return SqlExecutionResult(sql=sql, error="SQL 为空")
    if not db:
        return SqlExecutionResult(sql=sql, error="未提供目标 database，请让 Skill 或调用方显式指定库名")

    # 安全检查 — 禁止 DDL / DML
    sql_upper = sql.strip().upper()
    forbidden = ["DROP ", "TRUNCATE ", "DELETE ", "ALTER ", "CREATE ", "INSERT ", "UPDATE "]
    for kw in forbidden:
        if sql_upper.startswith(kw):
            return SqlExecutionResult(
                sql=sql,
                error=f"安全限制: 不允许执行 {kw.strip()} 语句",
            )

    try:
        tool_result = run_query(
            sql=sql,
            database=db,
            # 多取 1 行用于判断 has_more
            limit=max_rows + 1,
        )
        rows = tool_result.get("rows", [])
        duration_ms = int(tool_result.get("duration_ms", 0))

        has_more = len(rows) > max_rows
        if has_more:
            rows = rows[:max_rows]

        columns = list(rows[0].keys()) if rows else []

        # 转换不可序列化的类型
        serializable_rows = []
        for row in rows:
            clean: dict[str, Any] = {}
            for k, v in row.items():
                if v is None:
                    clean[k] = None
                elif isinstance(v, (int, float, str, bool)):
                    clean[k] = v
                else:
                    clean[k] = str(v)
            serializable_rows.append(clean)

        return SqlExecutionResult(
            sql=sql,
            columns=columns,
            rows=serializable_rows,
            row_count=len(serializable_rows),
            has_more=has_more,
            duration_ms=duration_ms,
        )

    except ToolRuntimeError as e:
        logger.error("Tool runtime error: %s", e)
        return SqlExecutionResult(
            sql=sql,
            error=f"工具调用失败: {str(e)}",
            duration_ms=0,
        )
    except Exception as e:
        logger.error("SQL execution error: %s", e)
        return SqlExecutionResult(
            sql=sql,
            error=f"执行错误: {str(e)}",
            duration_ms=0,
        )
