"""Evaluation V2 dataset contract — validation and canonical serialization.

Mirrors ``tools/dataagent-evals/dataset/manage.py`` (WEIGHTS, REQUIRED,
validate, canonical_bytes). The two implementations are kept in sync by a
regression test that asserts byte-level equality on the smoke dataset.
"""
from __future__ import annotations

import hashlib
import json
from typing import Any


WEIGHTS = {
    "intent": 1,
    "ontology_entity": 1,
    "relation_scope": 1,
    "sql_or_tool_call": 2,
    "result_consistency": 2,
    "reasoning": 2,
    "answer_quality": 1,
}

REQUIRED = {
    "schema_version", "case_id", "category", "case_type", "suite_tags",
    "expected_semantics", "expected_time", "expected_tools", "expected_sql",
    "expected_result", "expected_answer", "limits", "scoring", "veto_rules",
}


def canonical_bytes(rows: list[dict[str, Any]]) -> bytes:
    return b"".join(
        (json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
        for row in rows
    )


def dataset_hash(rows: list[dict[str, Any]]) -> str:
    return hashlib.sha256(canonical_bytes(rows)).hexdigest()


def validate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    errors: list[str] = []
    seen: set[str] = set()
    for index, row in enumerate(rows, 1):
        case_id = str(row.get("case_id") or f"line-{index}")
        missing = sorted(REQUIRED - set(row))
        if missing:
            errors.append(f"{case_id}: missing {','.join(missing)}")
        if row.get("schema_version") != 2:
            errors.append(f"{case_id}: schema_version must be 2")
        if case_id in seen:
            errors.append(f"{case_id}: duplicate case_id")
        seen.add(case_id)
        if not str(row.get("question") or "").strip() and not row.get("turns"):
            errors.append(f"{case_id}: question or turns is required")
        scoring = row.get("scoring") if isinstance(row.get("scoring"), dict) else {}
        for key, maximum in WEIGHTS.items():
            if scoring.get(key) != maximum:
                errors.append(f"{case_id}: scoring.{key} must equal {maximum}")
        dimension_sum = sum(float(scoring.get(key, -1000)) for key in WEIGHTS)
        if dimension_sum != float(scoring.get("total_score", -1)) or dimension_sum != 10:
            errors.append(f"{case_id}: scoring total must equal dimension sum 10")
        tools = row.get("expected_tools") if isinstance(row.get("expected_tools"), dict) else {}
        if int(tools.get("min_calls") or 0) > int(tools.get("max_calls") or 0):
            errors.append(f"{case_id}: expected_tools min_calls exceeds max_calls")
    return {
        "schema_version": 2,
        "case_count": len(rows),
        "case_ids": [str(row.get("case_id") or "") for row in rows],
        "dataset_hash": dataset_hash(rows),
        "valid": not errors,
        "errors": errors,
    }


def parse_jsonl(data: bytes) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_no, line in enumerate(data.decode("utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"line {line_no}: {exc}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"line {line_no}: expected JSON object")
        rows.append(row)
    return rows
