#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
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


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no}: {exc}") from exc
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_no}: expected JSON object")
            rows.append(row)
    return rows


def canonical_bytes(rows: list[dict[str, Any]]) -> bytes:
    return b"".join(
        (json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
        for row in rows
    )


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
        "dataset_hash": hashlib.sha256(canonical_bytes(rows)).hexdigest(),
        "valid": not errors,
        "errors": errors,
    }


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_bytes(rows))


def v1_to_v2(row: dict[str, Any]) -> dict[str, Any]:
    turns = row.get("turns") if isinstance(row.get("turns"), list) else []
    execution_required = str(row.get("expected_intent") or "").strip() not in {"定义解释", "definition"}
    return {
        "schema_version": 2,
        "case_id": row.get("case_id"),
        "category": row.get("category"),
        "case_type": "multiturn" if turns else ("definition" if not execution_required else "query"),
        "suite_tags": ["core", "multiturn" if turns else "single-turn"] + (["tool-execution"] if execution_required else ["definition"]),
        "question": row.get("question", ""),
        **({"turns": turns} if turns else {}),
        "expected_semantics": {
            "intent": row.get("expected_intent", ""),
            "ontology_object_ids": row.get("expected_ontology_objects") or [],
            "relation_ids": row.get("expected_relations") or [],
            "business_rules": row.get("expected_sql_or_tool_behavior") or [],
            "default_environment": "",
            "required_slots": [],
        },
        "expected_time": {"required": False, "field": "", "range": {}, "grain": "", "timezone": "Asia/Shanghai", "snapshot_strategy": "execution_date"},
        "expected_tools": {
            "required_steps": ["query_execute"] if execution_required else ["ontology_lookup"],
            "allowed_alternative_groups": [["mcp__portal__portal_query_readonly", "Bash:run_sql.py"]] if execution_required else [],
            "ordered": False,
            "min_calls": 1,
            "max_calls": 20,
        },
        "expected_sql": {
            "execution_required": execution_required,
            "tables": [], "fields": [],
            "predicates": row.get("required_sql_fragments") or [],
            "aggregations": [],
            "forbidden_patterns": row.get("forbidden_sql_patterns") or [],
        },
        "expected_result": {"allow_empty": False, "required_columns": [], "answer_result_fields": []},
        "expected_answer": {"required_points": row.get("expected_answer_points") or [], "boundary_notes": [], "units": [], "error_expression": []},
        "limits": {"max_wait_seconds": int(row.get("max_wait_seconds") or 900), "max_agent_turns": 30, "max_tool_calls": 30},
        "scoring": {
            "intent": 1, "ontology_entity": 1, "relation_scope": 1,
            "sql_or_tool_call": 2, "result_consistency": 2,
            "reasoning": 2, "answer_quality": 1, "total_score": 10,
        },
        "veto_rules": row.get("veto_rules") or [],
        "judge_guidance": str(row.get("judge_guidance") or ""),
    }


def generate(core: Path, manifest_path: Path, skill_path: Path | None, target_skill_id: str) -> None:
    rows = load_jsonl(core)
    result = validate(rows)
    if not result["valid"]:
        raise ValueError("\n".join(result["errors"]))
    prefix = core.stem.removesuffix("-core")
    suites = {
        "single-turn": [row for row in rows if row.get("case_type") != "multiturn"],
        "multiturn": [row for row in rows if row.get("case_type") == "multiturn"],
        "definition": [row for row in rows if row.get("case_type") == "definition"],
        "tool-execution": [row for row in rows if bool((row.get("expected_sql") or {}).get("execution_required"))],
    }
    for name, subset in suites.items():
        write_jsonl(core.with_name(f"{prefix}-{name}.jsonl"), subset)
    skill_hash = None
    if skill_path and skill_path.exists():
        digest = hashlib.sha256()
        for file in sorted(item for item in skill_path.rglob("*") if item.is_file() and not item.name.startswith(".")):
            digest.update(str(file.relative_to(skill_path)).encode())
            digest.update(file.read_bytes())
        skill_hash = digest.hexdigest()
    manifest = {
        "schema_version": 2,
        "dataset_id": prefix,
        "dataset_hash": result["dataset_hash"],
        "case_count": len(rows),
        "case_ids": result["case_ids"],
        "target_skill": {"id": target_skill_id, "snapshot_hash": skill_hash},
        "suites": {name: [row["case_id"] for row in subset] for name, subset in suites.items()},
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("validate")
    check.add_argument("--dataset", required=True)
    migrate = sub.add_parser("migrate-v1")
    migrate.add_argument("--input", required=True)
    migrate.add_argument("--output", required=True)
    build = sub.add_parser("generate")
    build.add_argument("--core", required=True)
    build.add_argument("--manifest", required=True)
    build.add_argument("--skill-path", default="")
    build.add_argument("--target-skill-id", default="", help="Target Skill ID; defaults to --skill-path basename.")
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            result = validate(load_jsonl(Path(args.dataset)))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0 if result["valid"] else 2
        if args.command == "migrate-v1":
            output = Path(args.output)
            if output.exists():
                raise ValueError(f"refusing to overwrite {output}")
            rows = [v1_to_v2(row) for row in load_jsonl(Path(args.input))]
            write_jsonl(output, rows)
            result = validate(rows)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0 if result["valid"] else 2
        skill_path = Path(args.skill_path) if args.skill_path else None
        target_skill_id = str(args.target_skill_id or (skill_path.name if skill_path else "unspecified")).strip()
        generate(Path(args.core), Path(args.manifest), skill_path, target_skill_id)
        return 0
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
