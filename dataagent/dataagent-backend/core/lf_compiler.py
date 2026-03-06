from __future__ import annotations

"""
LF -> SQL 编译器
"""

from typing import Any

from core.lf_models import LfFilterItem, LogicForm

SUPPORTED_ENGINES = {"mysql", "doris"}
SUPPORTED_AGG = {"sum", "avg", "count", "max", "min"}


class LfCompileError(ValueError):
    pass


def compile_logic_form_to_sql(lf: LogicForm, *, engine: str) -> str:
    engine = (engine or "").strip().lower()
    if engine not in SUPPORTED_ENGINES:
        raise LfCompileError(f"Unsupported engine: {engine}")

    if lf.action == "explain_only":
        return ""
    if not lf.table:
        raise LfCompileError("LF 缺少 table")
    if not lf.select:
        raise LfCompileError("LF 缺少 select")
    if lf.limit is None:
        raise LfCompileError("LF 缺少 limit")

    select_clause = ", ".join(_compile_select_expr(x) for x in lf.select)
    sql_parts = [
        f"SELECT {select_clause}",
        f"FROM {_quote_ident(lf.table)}",
    ]

    if lf.filters:
        where_clause = " AND ".join(_compile_filter_expr(x) for x in lf.filters)
        sql_parts.append(f"WHERE {where_clause}")

    if lf.group_by:
        group_clause = ", ".join(_quote_ident(x) for x in lf.group_by)
        sql_parts.append(f"GROUP BY {group_clause}")

    if lf.order_by:
        order_clause = ", ".join(
            f"{_quote_ident(x.field)} {x.direction.upper()}" for x in lf.order_by
        )
        sql_parts.append(f"ORDER BY {order_clause}")

    sql_parts.append(f"LIMIT {int(lf.limit)}")
    return "\n".join(sql_parts)


def _compile_select_expr(item) -> str:
    field = _quote_ident(item.field)
    agg = (item.agg or "").strip().lower()
    if agg:
        if agg not in SUPPORTED_AGG:
            raise LfCompileError(f"Unsupported aggregate: {item.agg}")
        if item.field == "*" and agg != "count":
            raise LfCompileError("只有 count 支持 *")
        expr = f"{agg.upper()}({field})"
    else:
        expr = field
    if item.alias:
        return f"{expr} AS {_quote_ident(item.alias)}"
    return expr


def _compile_filter_expr(item: LfFilterItem) -> str:
    field = _quote_ident(item.field)
    op = item.op
    value = item.value

    if op == "between":
        if not isinstance(value, list) or len(value) != 2:
            raise LfCompileError("between 需要 [start, end]")
        return f"{field} BETWEEN {_quote_value(value[0])} AND {_quote_value(value[1])}"

    if op in {"in", "not_in"}:
        if not isinstance(value, list) or not value:
            raise LfCompileError(f"{op} 需要非空数组")
        values = ", ".join(_quote_value(v) for v in value)
        operator = "IN" if op == "in" else "NOT IN"
        return f"{field} {operator} ({values})"

    operator_map = {
        "=": "=",
        "!=": "!=",
        ">": ">",
        ">=": ">=",
        "<": "<",
        "<=": "<=",
        "like": "LIKE",
    }
    operator = operator_map.get(op)
    if not operator:
        raise LfCompileError(f"Unsupported op: {op}")
    return f"{field} {operator} {_quote_value(value)}"


def _quote_ident(name: str) -> str:
    raw = (name or "").replace("`", "").strip()
    if not raw:
        raise LfCompileError("Identifier is empty")
    if raw == "*":
        return raw
    return f"`{raw}`"


def _quote_value(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value).replace("'", "''")
    return f"'{text}'"
