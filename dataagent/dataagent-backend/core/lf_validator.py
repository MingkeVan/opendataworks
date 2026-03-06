from __future__ import annotations

"""
LF 校验器
"""

from typing import Any

from core.lf_models import LogicForm


class LfValidationError(ValueError):
    pass


def validate_logic_form(
    lf: LogicForm,
    *,
    constraints: dict[str, Any],
    table_fields_map: dict[str, set[str]],
    known_databases: set[str],
    known_metrics: set[str] | None = None,
) -> None:
    global_constraints = constraints.get("global") if isinstance(constraints, dict) else {}
    if not isinstance(global_constraints, dict):
        global_constraints = {}

    row_limit_max = _as_positive_int(global_constraints.get("row_limit_max")) or 1000

    if lf.action == "explain_only":
        return

    if not lf.table:
        raise LfValidationError("LF 缺少 table")
    if lf.table not in table_fields_map:
        raise LfValidationError(f"未知表: {lf.table}")

    if lf.database and known_databases and lf.database not in known_databases:
        raise LfValidationError(f"未知数据库: {lf.database}")

    if not lf.select:
        raise LfValidationError("LF 缺少 select 字段")

    if lf.limit is None:
        raise LfValidationError("LF 必须包含 limit")
    if lf.limit <= 0:
        raise LfValidationError("LF limit 必须大于 0")
    if lf.limit > row_limit_max:
        raise LfValidationError(f"LF limit 超过上限 {row_limit_max}")

    fields = table_fields_map.get(lf.table, set())
    for item in lf.select:
        if item.field.startswith("$metric."):
            metric_key = item.field.removeprefix("$metric.").strip()
            if not metric_key or not known_metrics or metric_key not in known_metrics:
                raise LfValidationError(f"未知指标: {item.field}")
            continue
        _ensure_field_exists(item.field, fields, f"select.field={item.field}")

    for item in lf.filters:
        _ensure_field_exists(item.field, fields, f"filters.field={item.field}")

    for item in lf.group_by:
        _ensure_field_exists(item, fields, f"group_by.field={item}")

    for item in lf.order_by:
        _ensure_field_exists(item.field, fields, f"order_by.field={item.field}")


def _as_positive_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        iv = int(value)
    except Exception:
        return None
    return iv if iv > 0 else None


def _ensure_field_exists(field: str, table_fields: set[str], hint: str):
    if field == "*":
        return
    if field not in table_fields:
        raise LfValidationError(f"{hint} 不存在")
