from __future__ import annotations

import pytest

from core.lf_models import LogicForm
from core.lf_validator import LfValidationError, validate_logic_form


def _base_kwargs():
    return {
        "constraints": {"global": {"row_limit_max": 1000}},
        "table_fields_map": {"ods_order": {"order_id", "total_amount", "order_time"}},
        "known_databases": {"doris_ods"},
        "known_metrics": {"order_amount"},
    }


def test_validate_reject_without_limit():
    lf = LogicForm(
        action="query",
        database="doris_ods",
        table="ods_order",
        select=[{"field": "order_id"}],
        limit=None,
    )
    with pytest.raises(LfValidationError):
        validate_logic_form(lf, **_base_kwargs())


def test_validate_reject_unknown_metric():
    lf = LogicForm(
        action="query",
        database="doris_ods",
        table="ods_order",
        select=[{"field": "$metric.unknown"}],
        limit=100,
    )
    with pytest.raises(LfValidationError):
        validate_logic_form(lf, **_base_kwargs())
