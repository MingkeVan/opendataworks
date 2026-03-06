from __future__ import annotations

from core.lf_compiler import compile_logic_form_to_sql
from core.lf_models import LogicForm


def test_compile_mysql_sql():
    lf = LogicForm(
        action="query",
        database="opendataworks",
        table="ods_order",
        select=[{"field": "total_amount", "agg": "sum", "alias": "total_amount"}],
        filters=[{"field": "order_time", "op": ">=", "value": "2026-01-01"}],
        group_by=[],
        order_by=[{"field": "total_amount", "direction": "desc"}],
        limit=100,
    )

    sql = compile_logic_form_to_sql(lf, engine="mysql")
    assert "SELECT SUM(`total_amount`) AS `total_amount`" in sql
    assert "FROM `ods_order`" in sql
    assert "LIMIT 100" in sql


def test_compile_explain_only_returns_empty_sql():
    lf = LogicForm(action="explain_only", notes="no query")
    assert compile_logic_form_to_sql(lf, engine="doris") == ""
