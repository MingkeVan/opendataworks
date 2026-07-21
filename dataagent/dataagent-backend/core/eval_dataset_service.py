"""Evaluation dataset import/export service.

Uses ``eval_contract`` for validation and canonical serialization (matching
the ``tools/dataagent-evals/dataset/manage.py`` contract byte-for-byte).
"""
from __future__ import annotations

import uuid
from typing import Any

from core.eval_contract import canonical_bytes, dataset_hash, parse_jsonl, validate
from core.eval_store import get_eval_store


def import_dataset(name: str, data: bytes, *, created_by: str = "") -> dict[str, Any]:
    """Parse JSONL bytes, validate, persist dataset + cases. Returns dataset row."""
    rows = parse_jsonl(data)
    if not rows:
        raise ValueError("empty dataset")
    result = validate(rows)
    if not result["valid"]:
        raise ValueError("; ".join(result["errors"][:20]))

    dataset_id = f"ds-{uuid.uuid4().hex[:12]}"
    store = get_eval_store()
    ds = store.create_dataset(
        dataset_id=dataset_id,
        name=name,
        case_count=result["case_count"],
        dataset_hash=result["dataset_hash"],
        created_by=created_by,
    )
    store.replace_cases(dataset_id, rows, result["dataset_hash"])
    return ds


def export_dataset(dataset_id: str) -> tuple[str, bytes]:
    """Return ``(filename, jsonl_bytes)`` for download."""
    store = get_eval_store()
    ds = store.get_dataset(dataset_id)
    if not ds:
        raise ValueError(f"dataset not found: {dataset_id}")
    rows = store.get_case_full_json(dataset_id)
    data = canonical_bytes(rows)
    safe_name = str(ds.get("name") or dataset_id).replace("/", "_").replace(" ", "_")
    return f"{safe_name}.jsonl", data


def refresh_dataset_hash(dataset_id: str) -> str:
    """Recompute hash from current DB cases and update the dataset row."""
    store = get_eval_store()
    rows = store.get_case_full_json(dataset_id)
    h = dataset_hash(rows)
    with store._conn() as conn:
        store._refresh_dataset_stats(conn, dataset_id, h, len(rows))
    return h


def validate_cases(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Thin wrapper exposing contract validation to routes."""
    return validate(rows)
