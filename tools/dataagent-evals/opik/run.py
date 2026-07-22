#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import nullcontext
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from threading import Lock
from typing import Any

try:
    import opik
    from opik import Opik
    from opik.evaluation import evaluate as opik_evaluate
    from opik.evaluation.metrics import BaseMetric as OpikBaseMetric
    from opik.evaluation.metrics import ScoreResult
except Exception:  # pragma: no cover - dry-run works without the optional SDK
    opik = None  # type: ignore[assignment]
    Opik = None  # type: ignore[assignment]
    opik_evaluate = None  # type: ignore[assignment]
    OpikBaseMetric = object  # type: ignore[assignment]
    ScoreResult = None  # type: ignore[assignment]


REQUIRED_CASE_FIELDS = {
    "schema_version",
    "case_id",
    "category",
    "case_type",
    "suite_tags",
    "expected_semantics",
    "expected_time",
    "expected_tools",
    "expected_sql",
    "expected_result",
    "expected_answer",
    "limits",
    "scoring",
    "veto_rules",
}
TERMINAL_STATUSES = {"success", "finished", "failed", "error", "suspended", "cancelled", "canceled"}
SUCCESS_STATUSES = {"success", "finished"}
JUDGE_DEFAULT_TIMEOUT_SECONDS = 300
JUDGE_MAX_FINAL_ANSWER_CHARS = 6000
JUDGE_MAX_SQL_OUTPUTS = 20
JUDGE_MAX_SQL_OUTPUT_CHARS = 800
JUDGE_MAX_TOOL_EVENTS = 30
JUDGE_MAX_CHART_OUTPUTS = 5
GATES = {
    "average_score": 8.0,
    "intent_accuracy": 0.90,
    "ontology_accuracy": 0.90,
    "sql_tool_accuracy": 0.85,
    "result_consistency_rate": 0.90,
    "data_accuracy": 0.90,
    "reasoning_average": 4.0,
    "hallucination_rate": 0.05,
}
JUDGE_DIMENSIONS = (
    "intent",
    "ontology_entity",
    "relation_scope",
    "sql_or_tool_call",
    "result_consistency",
    "reasoning",
    "answer_quality",
)
DIMENSION_MAX = {
    "intent": 1.0,
    "ontology_entity": 1.0,
    "relation_scope": 1.0,
    "sql_or_tool_call": 2.0,
    "result_consistency": 2.0,
    "reasoning": 2.0,
    "answer_quality": 1.0,
}
EVALUATION_ENGINE = "opik"
ENGINE_VERSION = "2.1.32"
METRIC_SEMANTICS_VERSION = "2.1"
JUDGE_PROMPT_VERSION = "dataagent-v2-2026-07-20"
_DATAAGENT_AUTH_TOKEN = ""
_OPIK_PROJECT_NAME = ""


class EvalRunnerError(Exception):
    def __init__(self, message: str, *, exit_code: int = 2):
        super().__init__(message)
        self.exit_code = exit_code


class InfrastructureAbort(EvalRunnerError):
    def __init__(
        self,
        message: str,
        *,
        partial_results: list[dict[str, Any]] | None = None,
        details: dict[str, Any] | None = None,
    ):
        super().__init__(message, exit_code=2)
        self.partial_results = partial_results or []
        self.details = details or {}


class JudgeConfig:
    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        model: str,
        timeout_seconds: int = JUDGE_DEFAULT_TIMEOUT_SECONDS,
        max_tokens: int = 4096,
    ):
        self.base_url = base_url
        self.token = token
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.max_tokens = max_tokens


def _is_workspace_root(path: Path) -> bool:
    return (
        (path / "scripts").is_dir()
        or (path / "deploy").is_dir()
        or (path / "tools" / "dataagent-evals").is_dir()
    )


def _repo_or_package_root() -> Path:
    file_root = Path(__file__).resolve().parents[3]
    cwd = Path.cwd().resolve()
    for candidate in (cwd, *cwd.parents, file_root):
        if _is_workspace_root(candidate):
            return candidate
    return file_root


def _timestamp() -> str:
    return dt.datetime.now().strftime("%Y%m%d-%H%M%S")


def default_output_dir(root: Path) -> Path:
    return root / "reports" / "dataagent-evals" / _timestamp()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    root = _repo_or_package_root()
    parser = argparse.ArgumentParser(description="Run independent DataAgent Opik V2 evaluations.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8900", help="DataAgent backend base URL.")
    parser.add_argument(
        "--dataset",
        default=os.environ.get("DATAAGENT_EVAL_DATASET", ""),
        help="Required external private evaluation JSONL dataset path.",
    )
    parser.add_argument("--output-dir", default=str(default_output_dir(root)), help="Report output directory.")
    parser.add_argument("--case", action="append", dest="case_ids", default=[], help="Case ID to run. Can be repeated.")
    parser.add_argument("--agent-id", default=os.environ.get("DATAAGENT_EVAL_AGENT_ID", ""), help="Required DataAgent agent_id for non-dry-run evaluation tasks.")
    parser.add_argument("--provider-id", default="", help="Override DataAgent execution provider for evaluated tasks.")
    parser.add_argument("--model", default="", help="Override DataAgent execution model for evaluated tasks.")
    parser.add_argument("--timeout-seconds", type=int, default=900, help="Maximum wait per case.")
    parser.add_argument("--concurrency", type=int, default=1, help="Number of cases to run in parallel.")
    parser.add_argument("--judge-base-url", default=os.environ.get("DATAAGENT_EVAL_JUDGE_BASE_URL", ""), help="Anthropic-compatible judge base URL.")
    parser.add_argument("--judge-token", default=os.environ.get("DATAAGENT_EVAL_JUDGE_TOKEN", ""), help="Judge model API token.")
    parser.add_argument("--judge-model", default=os.environ.get("DATAAGENT_EVAL_JUDGE_MODEL", ""), help="Judge model name.")
    parser.add_argument(
        "--judge-timeout-seconds",
        type=int,
        default=int(os.environ.get("DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS", str(JUDGE_DEFAULT_TIMEOUT_SECONDS))),
        help="Judge request timeout.",
    )
    parser.add_argument(
        "--judge-max-tokens",
        type=int,
        default=int(os.environ.get("DATAAGENT_EVAL_JUDGE_MAX_TOKENS", "4096")),
        help="Judge response max tokens.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Validate dataset and output directory without service calls.")
    parser.add_argument("--environment-label", default=os.environ.get("DATAAGENT_EVAL_ENVIRONMENT_LABEL", "local"))
    parser.add_argument("--run-label", default=os.environ.get("DATAAGENT_EVAL_RUN_LABEL", ""))
    parser.add_argument("--history-root", default=os.environ.get("DATAAGENT_EVAL_HISTORY_ROOT", ""))
    parser.add_argument("--agent-snapshot-path", default=os.environ.get("DATAAGENT_EVAL_AGENT_SNAPSHOT_PATH", ""))
    parser.add_argument("--auth-token", default=os.environ.get("DATAAGENT_EVAL_AUTH_TOKEN", ""), help=argparse.SUPPRESS)
    parser.add_argument("--opik-base-url", default=os.environ.get("OPIK_BASE_URL", "http://127.0.0.1:5173/api"))
    parser.add_argument("--opik-project-name", default=os.environ.get("OPIK_PROJECT_NAME", "dataagent-evals"))
    parser.add_argument("--opik-dataset-name", default=os.environ.get("OPIK_DATASET_NAME", ""))
    parser.add_argument("--opik-experiment-name", default=os.environ.get("OPIK_EXPERIMENT_NAME", ""))
    return parser.parse_args(argv)


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise EvalRunnerError(f"dataset not found: {path}")
    cases: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                item = json.loads(text)
            except json.JSONDecodeError as exc:
                raise EvalRunnerError(f"invalid JSONL at {path}:{line_no}: {exc}") from exc
            if not isinstance(item, dict):
                raise EvalRunnerError(f"case at {path}:{line_no} is not a JSON object")
            cases.append(item)
    return cases


def _scoring_total(case: dict[str, Any]) -> float:
    scoring = case.get("scoring")
    if not isinstance(scoring, dict):
        return -1
    total = 0.0
    for key, maximum in DIMENSION_MAX.items():
        try:
            value = float(scoring.get(key))
        except Exception:
            return -1
        if value < 0 or value > maximum:
            return -1
        total += value
    try:
        if abs(float(scoring.get("total_score")) - total) > 0.001:
            return -1
    except Exception:
        return -1
    return total


def _case_turns(case: dict[str, Any]) -> list[str]:
    raw_turns = case.get("turns")
    turns: list[str] = []
    if isinstance(raw_turns, list):
        for turn in raw_turns:
            if isinstance(turn, str):
                text = turn.strip()
            elif isinstance(turn, dict):
                text = str(turn.get("content") or turn.get("question") or turn.get("message") or "").strip()
            else:
                text = ""
            if text:
                turns.append(text)
    if turns:
        return turns

    question = str(case.get("question") or "").strip()
    return [question] if question else []


def _case_question(case: dict[str, Any]) -> str:
    question = str(case.get("question") or "").strip()
    if question:
        return question
    return "\n".join(f"第{index}轮：{turn}" for index, turn in enumerate(_case_turns(case), start=1))


def load_dataset(path: Path, case_ids: list[str] | None = None) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    cases = _load_jsonl(path)
    seen: set[str] = set()
    duplicate_ids: list[str] = []
    missing_fields: list[dict[str, Any]] = []
    invalid_scoring: list[str] = []

    for item in cases:
        case_id = str(item.get("case_id") or "").strip()
        if case_id in seen:
            duplicate_ids.append(case_id)
        seen.add(case_id)
        missing = sorted(field for field in REQUIRED_CASE_FIELDS if field not in item)
        if missing:
            missing_fields.append({"case_id": case_id, "missing": missing})
        if not _case_turns(item):
            missing_fields.append({"case_id": case_id, "missing": ["question_or_turns"]})
        if abs(_scoring_total(item) - 10.0) > 0.001:
            invalid_scoring.append(case_id)
        if item.get("schema_version") != 2:
            missing_fields.append({"case_id": case_id, "missing": ["schema_version=2"]})

    if case_ids:
        requested = set(case_ids)
        cases = [item for item in cases if str(item.get("case_id") or "") in requested]
        found = {str(item.get("case_id") or "") for item in cases}
        missing_requested = sorted(requested - found)
        if missing_requested:
            raise EvalRunnerError(f"requested case id not found: {', '.join(missing_requested)}")

    stats = {
        "dataset_path": str(path),
        "dataset_id": path.stem.removesuffix("-core"),
        "dataset_hash": hashlib.sha256(path.read_bytes()).hexdigest(),
        "schema_version": 2,
        "case_ids": [str(item.get("case_id") or "") for item in cases],
        "total_cases": len(cases),
        "dataset_valid": not missing_fields and not duplicate_ids and not invalid_scoring,
        "unique_case_ids": not duplicate_ids,
        "duplicate_case_ids": duplicate_ids,
        "missing_fields": missing_fields,
        "scoring_total_valid": not invalid_scoring,
        "invalid_scoring_case_ids": invalid_scoring,
    }
    if not stats["dataset_valid"]:
        raise EvalRunnerError(f"dataset validation failed: {json.dumps(stats, ensure_ascii=False)}")
    return cases, stats


def _snapshot_hash(path_text: str) -> str | None:
    path = Path(str(path_text or "")).expanduser()
    if not path_text or not path.exists():
        return None
    digest = hashlib.sha256()
    files = [path] if path.is_file() else sorted(item for item in path.rglob("*") if item.is_file() and not item.name.startswith("."))
    for file in files:
        digest.update(str(file.relative_to(path) if path.is_dir() else file.name).encode("utf-8"))
        digest.update(file.read_bytes())
    return digest.hexdigest()


def _write_dataset_snapshot(output_dir: Path, cases: list[dict[str, Any]]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    with (output_dir / "dataset-snapshot.jsonl").open("w", encoding="utf-8") as handle:
        for case in cases:
            handle.write(json.dumps(case, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")


def _dataagent_headers() -> dict[str, str]:
    headers = {"X-ODW-Client": "dataagent"}
    if _DATAAGENT_AUTH_TOKEN:
        headers["Authorization"] = f"Bearer {_DATAAGENT_AUTH_TOKEN}"
    return headers


def _opik_span(name: str, *, span_type: str = "tool", metadata: dict[str, Any] | None = None) -> Any:
    if opik is None or not _OPIK_PROJECT_NAME:
        return nullcontext()
    return opik.start_as_current_span(
        name=name,
        type=span_type,
        metadata=metadata or {},
        project_name=_OPIK_PROJECT_NAME,
    )


def dataagent_http_json(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    *,
    timeout: int = 30,
) -> dict[str, Any]:
    try:
        endpoint = urllib.parse.urlparse(url).path
        with _opik_span(
            f"dataagent.{method.lower()}.{endpoint.rsplit('/', 1)[-1] or 'root'}",
            metadata={"method": method, "endpoint": endpoint},
        ):
            return http_json(method, url, payload=payload, timeout=timeout, headers=_dataagent_headers())
    except EvalRunnerError as exc:
        if re.search(r"HTTP (?:401|403)\b", str(exc)):
            raise InfrastructureAbort(f"dataagent_auth_failed: {exc}") from exc
        raise


def http_json(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    *,
    timeout: int = 30,
    headers: dict[str, str] | None = None,
) -> dict[str, Any]:
    data = None
    request_headers = {"Accept": "application/json"}
    if headers:
        request_headers.update(headers)
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(url, data=data, method=method, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body else {}
    except TimeoutError as exc:
        raise EvalRunnerError(f"request timed out {url} after {timeout}s: {exc}", exit_code=2) from exc
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise EvalRunnerError(f"HTTP {exc.code} {url}: {body}", exit_code=2) from exc
    except urllib.error.URLError as exc:
        raise EvalRunnerError(f"request failed {url}: {exc}", exit_code=2) from exc
    except json.JSONDecodeError as exc:
        raise EvalRunnerError(f"invalid JSON response from {url}: {exc}", exit_code=2) from exc


def preflight(base_url: str, auth_token: str = "") -> dict[str, Any]:
    global _DATAAGENT_AUTH_TOKEN
    _DATAAGENT_AUTH_TOKEN = str(auth_token or "").strip()
    auth_config = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/auth/config", timeout=15)
    health = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/health", timeout=15)
    runtime_config = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/runtime-config", timeout=15)
    auth_enabled = bool(auth_config.get("auth_enabled") or auth_config.get("enabled"))
    identity: dict[str, Any] | None = None
    if auth_enabled:
        if not _DATAAGENT_AUTH_TOKEN:
            raise InfrastructureAbort("dataagent_auth_token_missing: DATAAGENT_EVAL_AUTH_TOKEN is required")
        identity = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/auth/me", timeout=15)
        roles = identity.get("roles") if isinstance(identity.get("roles"), list) else []
        is_admin = bool(identity.get("is_admin")) or "admin" in {str(role).lower() for role in roles}
        if not is_admin:
            raise InfrastructureAbort("dataagent_auth_not_admin: evaluation requires an administrator session")
    return {"auth": {"auth_enabled": auth_enabled, "identity_role": "admin" if identity else None}, "health": health, "runtime_config": runtime_config}


def _flatten_strings(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        parts: list[str] = []
        for item in value.values():
            parts.extend(_flatten_strings(item))
        return parts
    if isinstance(value, list):
        parts: list[str] = []
        for item in value:
            parts.extend(_flatten_strings(item))
        return parts
    if isinstance(value, (int, float, bool)):
        return [str(value)]
    return []


def _collect_tool_names(blocks: list[dict[str, Any]]) -> list[str]:
    names: list[str] = []
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        text = str(block.get("tool_name") or "").strip()
        if text and text not in names:
            names.append(text)
        if text == "Skill":
            skill_text = "\n".join(_flatten_strings(block.get("input")))
            if "opendataworks-business-knowledge" in skill_text and "Skill:opendataworks-business-knowledge" not in names:
                names.append("Skill:opendataworks-business-knowledge")
        if text == "Bash":
            command_text = "\n".join(_flatten_strings(block.get("input")))
            for script_name in ("run_sql.py", "lookup_ontology.py", "validate_sql.py", "build_chart_spec.py"):
                if script_name in command_text and f"Bash:{script_name}" not in names:
                    names.append(f"Bash:{script_name}")
    return names


def _looks_like_sql(text: str) -> bool:
    return bool(re.match(r"(?is)^\s*(?:select|with)\b", str(text or "")))


def _normalise_sql(text: str) -> str:
    return re.sub(r"\s+", " ", str(text or "").strip()).rstrip(";")


def _parse_json_text(value: str) -> Any | None:
    text = str(value or "").strip()
    if not text or text[0] not in "[{":
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return None


def _iter_structured_evidence(value: Any) -> list[Any]:
    values = [value]
    if isinstance(value, str):
        parsed = _parse_json_text(value)
        if parsed is not None:
            values.extend(_iter_structured_evidence(parsed))
        return values
    if isinstance(value, list):
        for item in value:
            values.extend(_iter_structured_evidence(item))
        return values
    if isinstance(value, dict):
        for item in value.values():
            values.extend(_iter_structured_evidence(item))
    return values


def _collect_structured_sql(value: Any) -> list[str]:
    sqls: list[str] = []
    for item in _iter_structured_evidence(value):
        if not isinstance(item, dict):
            continue
        for key, field in item.items():
            key_text = str(key or "").lower()
            if key_text in {"sql", "query"} and isinstance(field, str) and _looks_like_sql(field):
                sqls.append(_normalise_sql(field))
    return sqls


def _is_successful_sql_execution(block: dict[str, Any]) -> bool:
    if not isinstance(block, dict) or block.get("type") != "tool_use" or bool(block.get("is_error")):
        return False
    name = str(block.get("tool_name") or "")
    inputs = "\n".join(_flatten_strings(block.get("input")))
    accepted = (
        name in {"run_sql", "mcp__portal__portal_query_readonly"}
        or (name == "Bash" and "run_sql.py" in inputs)
    )
    return accepted and block.get("output") not in (None, "")


def _extract_sql_outputs(blocks: list[dict[str, Any]], final_answer: str = "") -> list[str]:
    sqls: list[str] = []
    for block in blocks:
        if not _is_successful_sql_execution(block):
            continue
        sqls.extend(_collect_structured_sql(block.get("input")))
        sqls.extend(_collect_structured_sql(block.get("output")))
    result: list[str] = []
    for text in sqls:
        if text and text not in result:
            result.append(text)
    return result


def _extract_chart_outputs(blocks: list[dict[str, Any]]) -> list[Any]:
    charts: list[Any] = []
    seen: set[str] = set()

    def add_chart(value: Any) -> None:
        try:
            key = json.dumps(value, sort_keys=True, ensure_ascii=False, default=str)
        except TypeError:
            key = repr(value)
        if key not in seen:
            seen.add(key)
            charts.append(value)

    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        for source in (block.get("input"), block.get("output")):
            for item in _iter_structured_evidence(source):
                if not isinstance(item, dict):
                    continue
                if item.get("kind") == "chart_spec":
                    add_chart(item)
                for key in ("chart", "chart_spec", "echarts", "spec"):
                    value = item.get(key)
                    if value is not None:
                        add_chart(value)
    return charts


def _truncate_for_judge(value: Any, *, max_text: int = 2000, max_rows: int = 20) -> Any:
    if value is None or isinstance(value, (int, float, bool)):
        return value
    if isinstance(value, str):
        text = value.strip()
        if len(text) <= max_text:
            return text
        return text[:max_text] + f"...[truncated {len(text) - max_text} chars]"
    if isinstance(value, list):
        return [_truncate_for_judge(item, max_text=max_text, max_rows=max_rows) for item in value[:max_rows]]
    if isinstance(value, dict):
        return {str(key): _truncate_for_judge(item, max_text=max_text, max_rows=max_rows) for key, item in value.items()}
    return str(value)


def _bounded_list_for_judge(value: Any, *, max_items: int) -> Any:
    if not isinstance(value, list) or len(value) <= max_items:
        return value
    head_count = max(1, max_items // 2)
    tail_count = max(1, max_items - head_count - 1)
    omitted = len(value) - head_count - tail_count
    return [
        *value[:head_count],
        {"kind": "truncated", "omitted_items": omitted},
        *value[-tail_count:],
    ]


def _compact_judge_payload(payload: dict[str, Any]) -> dict[str, Any]:
    compact: dict[str, Any] = {}
    for key, value in payload.items():
        if key == "case":
            compact[key] = _truncate_for_judge(value, max_text=1200, max_rows=30)
        elif key == "final_answer":
            compact[key] = _truncate_for_judge(value, max_text=JUDGE_MAX_FINAL_ANSWER_CHARS, max_rows=1)
        elif key == "tool_events":
            limited = _bounded_list_for_judge(value, max_items=JUDGE_MAX_TOOL_EVENTS)
            compact[key] = _truncate_for_judge(limited, max_text=400, max_rows=2)
        elif key == "sql_outputs":
            limited = _bounded_list_for_judge(value, max_items=JUDGE_MAX_SQL_OUTPUTS)
            compact[key] = _truncate_for_judge(limited, max_text=JUDGE_MAX_SQL_OUTPUT_CHARS, max_rows=1)
        elif key == "chart_outputs":
            limited = _bounded_list_for_judge(value, max_items=JUDGE_MAX_CHART_OUTPUTS)
            compact[key] = _truncate_for_judge(limited, max_text=500, max_rows=2)
        else:
            compact[key] = _truncate_for_judge(value, max_text=3000, max_rows=20)
    return compact


def _summarize_tool_events(blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    summarized: list[dict[str, Any]] = []
    seq = 0
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        seq += 1
        item: dict[str, Any] = {
            "seq_id": seq,
            "tool_name": str(block.get("tool_name") or ""),
        }
        if block.get("input") not in (None, ""):
            item["input"] = _truncate_for_judge(block.get("input"))
        if block.get("output") not in (None, ""):
            item["output"] = _truncate_for_judge(block.get("output"))
        if block.get("is_error"):
            item["is_error"] = True
        summarized.append({key: value for key, value in item.items() if value not in (None, "")})
    return summarized


def _collect_usage(task: dict[str, Any], message: dict[str, Any]) -> dict[str, Any]:
    usage: dict[str, Any] = {}
    if isinstance(task.get("usage"), dict):
        usage.update(task["usage"])
    if isinstance(message.get("usage"), dict):
        usage.update(message["usage"])
    return usage


def _aggregate_usage(tasks: list[dict[str, Any]], messages: list[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate usage once per task, preferring message usage over task usage."""
    usage_by_task: dict[str, dict[str, Any]] = {}
    for position, item in enumerate(tasks):
        if not isinstance(item, dict) or not isinstance(item.get("usage"), dict):
            continue
        key = str(item.get("task_id") or f"task:{position}")
        usage_by_task[key] = dict(item["usage"])
    for position, item in enumerate(messages):
        if not isinstance(item, dict) or not isinstance(item.get("usage"), dict):
            continue
        key = str(item.get("task_id") or f"message:{position}")
        usage_by_task.setdefault(key, {}).update(item["usage"])

    totals: dict[str, Any] = {}
    for usage in usage_by_task.values():
        for key, value in usage.items():
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                totals[key] = totals.get(key, 0) + value
            elif key not in totals:
                totals[key] = value
    return totals


def _dedupe(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value or "").strip()
        if text and text not in seen:
            result.append(text)
            seen.add(text)
    return result


def _normalize_sql_match_text(value: Any) -> str:
    text = str(value or "").lower().replace("`", "")
    text = re.sub(r"\s+", " ", text).strip()
    return re.sub(r"\s*(>=|<=|<>|!=|=|>|<)\s*", r"\1", text)


def _sql_fragment_matches(fragment: Any, sql_text: str, expected_sql: dict[str, Any]) -> bool:
    raw_fragment = str(fragment or "").strip()
    if not raw_fragment:
        return True
    normalized_fragment = _normalize_sql_match_text(raw_fragment)
    normalized_sql = _normalize_sql_match_text(sql_text)
    if normalized_fragment in normalized_sql:
        return True
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*", raw_fragment):
        base_name = normalized_fragment.rsplit(".", 1)[-1]
        if re.search(rf"(?<![A-Za-z0-9_]){re.escape(base_name)}(?![A-Za-z0-9_])", normalized_sql):
            return True
    aggregations = " ".join(str(item or "") for item in expected_sql.get("aggregations") or [])
    if normalized_fragment == "id" and re.search(r"\bcount\b", aggregations, re.I):
        return bool(re.search(r"\bcount\s*\(\s*\*\s*\)", normalized_sql, re.I))
    return False


def _add_months(value: dt.date, offset: int) -> dt.date:
    month_index = value.year * 12 + value.month - 1 + offset
    return dt.date(month_index // 12, month_index % 12 + 1, 1)


def _time_rule_check(expected_time: dict[str, Any], sql_text: str) -> dict[str, Any]:
    if not bool(expected_time.get("required")):
        return {"applicable": False, "passed": True, "reason": None}
    field = str(expected_time.get("field") or "").strip()
    normalized_sql = _normalize_sql_match_text(sql_text)
    field_present = bool(field) and bool(re.search(rf"(?<![A-Za-z0-9_]){re.escape(field.lower())}(?![A-Za-z0-9_])", normalized_sql))
    range_spec = expected_time.get("range") if isinstance(expected_time.get("range"), dict) else {}
    kind = str(range_spec.get("kind") or "").strip()
    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
    range_present = False
    expected_bounds: list[str] = []
    if kind == "calendar_month":
        start = _add_months(today.replace(day=1), int(range_spec.get("offset") or 0))
        end = _add_months(start, 1)
        expected_bounds = [start.isoformat(), end.isoformat()]
        range_present = all(bound in normalized_sql for bound in expected_bounds) or "date_format(current_date" in normalized_sql
    elif kind == "rolling_days":
        days = int(range_spec.get("value") or 0)
        if days > 0:
            start = today - dt.timedelta(days=days - 1)
            end = today + dt.timedelta(days=1)
            expected_bounds = [start.isoformat(), end.isoformat()]
            range_present = all(bound in normalized_sql for bound in expected_bounds)
            if not range_present and "current_date" in normalized_sql:
                range_present = bool(re.search(rf"interval\s+(?:{days}|{max(0, days - 1)})\s+day", normalized_sql))
    else:
        range_present = field_present
    passed = field_present and range_present
    return {
        "applicable": True,
        "passed": passed,
        "field": field,
        "range_kind": kind,
        "expected_bounds": expected_bounds,
        "reason": None if passed else ("time_field_missing" if not field_present else "time_range_mismatch"),
    }


def _select_relevant_query_evidence(
    blocks: list[dict[str, Any]],
    required_fragments: list[str],
    *,
    expected_sql: dict[str, Any] | None = None,
    preferred_width: int = 0,
) -> dict[str, Any] | None:
    candidates: list[dict[str, Any]] = []
    expected_sql = expected_sql or {}
    fragments = [str(item or "").strip() for item in required_fragments if str(item or "").strip()]
    for position, block in enumerate(blocks):
        if not _is_successful_sql_execution(block):
            continue
        sqls = _dedupe(_collect_structured_sql(block.get("input")) + _collect_structured_sql(block.get("output")))
        if not sqls:
            continue
        sql_text = "\n".join(sqls)
        normalized_sql = _normalize_sql_match_text(sql_text)
        semantic_matches = sum(1 for fragment in fragments if _sql_fragment_matches(fragment, sql_text, expected_sql))
        base_matches = sum(1 for fragment in fragments if "." in fragment and _normalize_sql_match_text(fragment).rsplit(".", 1)[-1] in normalized_sql and _normalize_sql_match_text(fragment) not in normalized_sql)
        rows = _query_rows_from_value(block.get("output"))
        row_width = max((len(row) for row in rows if isinstance(row, dict)), default=0) if isinstance(rows, list) else 0
        shape_rank = -abs(row_width - preferred_width) if preferred_width > 0 else 0
        candidates.append({
            "sql_text": sql_text,
            "rows": rows,
            "output_text": "\n".join(_flatten_strings(block.get("output"))),
            "rank": (semantic_matches, base_matches, shape_rank, position),
        })
    return max(candidates, key=lambda item: item["rank"]) if candidates else None


def _answer_references_result_values(rows: Any, fields: list[str], answer: str) -> bool:
    if not fields:
        return True
    if not isinstance(rows, list):
        return False
    dict_rows = [row for row in rows if isinstance(row, dict)]
    if len(dict_rows) != len(rows):
        return False
    if all(all(field in row for field in fields) for row in dict_rows):
        values = [row[field] for row in dict_rows for field in fields]
    elif fields and all(len(row) == len(fields) for row in dict_rows):
        values = [value for row in dict_rows for value in row.values()]
    else:
        return False
    answer_text = str(answer or "")
    for value in values:
        if value is None:
            continue
        rendered = str(value)
        if isinstance(value, (int, float)):
            if not re.search(rf"(?<![\d.]){re.escape(rendered)}(?![\d.])", answer_text):
                return False
        elif rendered.lower() not in answer_text.lower():
            return False
    return True


def _auto_failure_attribution(
    combined: str,
    *,
    missing_sql_fragments: list[str],
    missing_tool_names: list[str],
    wrong_domain_applicable: bool,
) -> list[str]:
    failures: list[str] = []
    if re.search(r"请.*执行.*SQL|供.*执行|无法直接执行|未注入.*SQL|没有\s*SQL\s*执行|SQL.*尚未执行", combined, re.I | re.S):
        failures.append("sql_only")
    if wrong_domain_applicable and re.search(r"OpenDataWorks\s*平台元数据|托管元数据|data_table|data_lineage|data_task|data_workflow|inspect_metadata\.py|get_lineage\.py", combined, re.I):
        failures.append("wrong_domain")
    if re.search(r"\{(?:target_date|TARGET_DATE|start_date|START_DATE|end_date|END_DATE|database_name|DATABASE_NAME|database_schema|DATABASE_SCHEMA|table_name|TABLE_NAME|period|PERIOD|timeDim|RULE_KEY)\}|占位符|TODO", combined):
        failures.append("placeholder_leak")
    if re.search(r"超时|timed?\s*out|timeout(?:_error| error| occurred)", combined, re.I):
        failures.append("tool_timeout")
    if re.search(r"未找到|没有找到|不存在|无匹配|空结果集|返回空", combined):
        failures.append("empty_result")
    if missing_sql_fragments:
        failures.append("missing_sql_fragment")
    if missing_tool_names:
        failures.append("missing_tool")
    return _dedupe(failures)


def auto_rule_check(case: dict[str, Any], *, final_answer: str, blocks: list[dict[str, Any]], sql_outputs: list[str], tool_names: list[str]) -> dict[str, Any]:
    expected_sql = case.get("expected_sql") if isinstance(case.get("expected_sql"), dict) else {}
    expected_time = case.get("expected_time") if isinstance(case.get("expected_time"), dict) else {}
    expected_tools = case.get("expected_tools") if isinstance(case.get("expected_tools"), dict) else {}
    expected_result = case.get("expected_result") if isinstance(case.get("expected_result"), dict) else {}
    required_fragments = (
        list(expected_sql.get("tables") or [])
        + list(expected_sql.get("fields") or [])
        + list(expected_sql.get("predicates") or [])
        + list(expected_sql.get("aggregations") or [])
    )
    preferred_width = len(expected_result.get("required_columns") or expected_result.get("answer_result_fields") or [])
    relevant_query = _select_relevant_query_evidence(blocks, required_fragments, expected_sql=expected_sql, preferred_width=preferred_width)
    assessment_text = str((relevant_query or {}).get("sql_text") or "\n".join(sql_outputs))
    time_check = (
        _time_rule_check(expected_time, assessment_text)
        if bool(expected_sql.get("execution_required")) or bool(sql_outputs)
        else {"applicable": False, "passed": True, "reason": "judge_only"}
    )
    missing_sql_fragments = [
        fragment
        for fragment in required_fragments
        if str(fragment or "").strip()
        and not _sql_fragment_matches(fragment, assessment_text, expected_sql)
        and not (bool(time_check.get("passed")) and "current_date" in str(fragment).lower())
    ]
    required_steps = list(expected_tools.get("required_steps") or [])
    groups = expected_tools.get("allowed_alternative_groups") or []
    missing_tool_names: list[str] = []
    if "ontology_lookup" in required_steps and not any(name in tool_names for name in ("Bash:lookup_ontology.py", "Skill")):
        missing_tool_names.append("ontology_lookup")
    if "business_knowledge_skill" in required_steps and "Skill:opendataworks-business-knowledge" not in tool_names:
        missing_tool_names.append("business_knowledge_skill")
    if "query_execute" in required_steps or bool(expected_sql.get("execution_required")):
        if not sql_outputs:
            missing_tool_names.append("query_execute")
    for group in groups:
        names = [str(name) for name in group] if isinstance(group, list) else []
        if names and not any(name in tool_names for name in names):
            missing_tool_names.append("|".join(names))
    forbidden_matches: list[str] = []
    for pattern in expected_sql.get("forbidden_patterns") or []:
        try:
            if re.search(str(pattern), assessment_text, re.I):
                forbidden_matches.append(str(pattern))
        except re.error:
            if str(pattern).lower() in assessment_text.lower():
                forbidden_matches.append(str(pattern))
    tool_output_text = str((relevant_query or {}).get("output_text") or "")
    relevant_rows = (relevant_query or {}).get("rows")
    empty_result = isinstance(relevant_rows, list) and len(relevant_rows) == 0
    unexpected_empty = empty_result and not bool(expected_result.get("allow_empty"))
    consistency_applicable = bool(sql_outputs) and bool(tool_output_text.strip())
    required_answer_fields = [str(item) for item in expected_result.get("answer_result_fields") or []]
    consistency_passed = _answer_references_result_values(relevant_rows, required_answer_fields, final_answer)
    platform_business_case = (
        str(case.get("category") or "") == "business-knowledge"
        or "local-smoke" in set(case.get("suite_tags") or [])
    )
    failure_attribution = _auto_failure_attribution(
        assessment_text + "\n" + final_answer,
        missing_sql_fragments=missing_sql_fragments,
        missing_tool_names=missing_tool_names,
        wrong_domain_applicable=not platform_business_case,
    )
    if bool(expected_result.get("allow_empty")):
        failure_attribution = [item for item in failure_attribution if item != "empty_result"]
    if bool(time_check.get("applicable")) and not bool(time_check.get("passed")):
        failure_attribution.append(str(time_check.get("reason") or "time_dimension_mismatch"))
    if forbidden_matches:
        failure_attribution.append("forbidden_sql_pattern")
    if unexpected_empty:
        failure_attribution.append("unexpected_empty_result")
    if not consistency_passed:
        failure_attribution.append("result_answer_inconsistent")
    execution_required = bool(expected_sql.get("execution_required"))
    sql_gate = (not execution_required) or bool(sql_outputs)
    tool_gate = not missing_tool_names
    return {
        "passed": not (
            missing_sql_fragments
            or missing_tool_names
            or forbidden_matches
            or unexpected_empty
            or (bool(time_check.get("applicable")) and not bool(time_check.get("passed")))
        ) and consistency_passed,
        "missing_sql_fragments": missing_sql_fragments,
        "forbidden_sql_patterns": forbidden_matches,
        "missing_tool_names": missing_tool_names,
        "triggered_veto_rules": [],
        "failure_attribution": _dedupe(failure_attribution),
        "hard_gates": {
            "required_tool_execution": tool_gate,
            "sql_execution_and_口径": sql_gate and not missing_sql_fragments and not forbidden_matches,
            "non_expected_empty_result": not unexpected_empty,
            "result_consistency": consistency_passed,
            "time_dimension": bool(time_check.get("passed")),
        },
        "result_consistency": {"applicable": consistency_applicable, "passed": consistency_passed},
        "time_dimension": time_check,
    }


def _apply_runtime_hard_gates(
    rule_check: dict[str, Any],
    case: dict[str, Any],
    *,
    task_completed: bool,
    agent_turn_count: int,
    tool_call_count: int,
) -> None:
    gates = rule_check.setdefault("hard_gates", {})
    gates["task_completed"] = task_completed
    rule_check["limit_violations"] = []
    if not task_completed:
        rule_check.setdefault("failure_attribution", []).append("task_not_completed")
    rule_check["failure_attribution"] = _dedupe(rule_check.get("failure_attribution") or [])
    if not task_completed:
        rule_check["passed"] = False


def _build_conversation_log(messages_response: dict[str, Any]) -> list[dict[str, Any]]:
    """Build a structured conversation log from topic messages for post-eval analysis."""
    log: list[dict[str, Any]] = []
    for msg in messages_response.get("items") or []:
        if not isinstance(msg, dict):
            continue
        entry: dict[str, Any] = {
            "role": str(msg.get("sender_type") or "unknown"),
            "content": str(msg.get("content") or ""),
        }
        if msg.get("task_id"):
            entry["task_id"] = str(msg["task_id"])
        blocks = msg.get("blocks")
        if isinstance(blocks, list) and blocks:
            entry["blocks"] = blocks
        if msg.get("created_at"):
            entry["created_at"] = str(msg["created_at"])
        log.append(entry)
    return log


def _fetch_topic_messages(base_url: str, topic_id: str) -> dict[str, Any]:
    """Read every persisted topic message in stable chronological order."""
    page_size = 500
    page_number = 1
    items: list[dict[str, Any]] = []
    total = 0
    while True:
        page = dataagent_http_json(
            "GET",
            f"{base_url}/api/v1/nl2sql/topics/{urllib.parse.quote(topic_id)}/messages"
            f"?page={page_number}&page_size={page_size}&order=asc",
            timeout=30,
        )
        batch = [item for item in page.get("items") or [] if isinstance(item, dict)]
        items.extend(batch)
        total = int(page.get("total") or len(items))
        if not batch or len(items) >= total or len(batch) < page_size:
            break
        page_number += 1
    return {
        "topic_id": topic_id,
        "page": 1,
        "page_size": page_size,
        "order": "asc",
        "total": total,
        "items": items,
    }


def _final_assistant_message(messages: dict[str, Any], task_id: str) -> dict[str, Any]:
    """Pick the last assistant message for ``task_id`` (Chat V2 projected blocks)."""
    candidates = []
    for message in messages.get("items") or []:
        if not isinstance(message, dict):
            continue
        if str(message.get("sender_type") or "") != "assistant":
            continue
        if task_id and message.get("task_id") not in {None, "", task_id}:
            continue
        candidates.append(message)
    if not candidates:
        for message in messages.get("items") or []:
            if isinstance(message, dict) and str(message.get("sender_type") or "") == "assistant":
                candidates.append(message)
    return candidates[-1] if candidates else {}


def _create_topic(base_url: str, case: dict[str, Any], agent_id: str) -> str:
    topic = dataagent_http_json(
        "POST",
        f"{base_url}/api/v1/nl2sql/topics",
        {"title": f"Eval {case['case_id']}", "agent_id": agent_id},
    )
    topic_id = str(topic.get("topic_id") or "").strip()
    if not topic_id:
        raise EvalRunnerError("topic creation response did not include topic_id")
    return topic_id


def _submit_task(base_url: str, topic_id: str, case: dict[str, Any], args: argparse.Namespace, content: str | None = None) -> str:
    payload: dict[str, Any] = {
        "topic_id": topic_id,
        "content": str(content if content is not None else _case_question(case)),
        "agent_id": str(args.agent_id or "").strip(),
        "execution_mode": "background",
    }
    if args.provider_id:
        payload["provider_id"] = args.provider_id
    if args.model:
        payload["model"] = args.model
    submitted = dataagent_http_json("POST", f"{base_url}/api/v1/nl2sql/tasks/deliver-message", payload)
    task_id = str(submitted.get("task_id") or "").strip()
    if not task_id:
        raise EvalRunnerError("task submission response did not include task_id")
    return task_id


def _is_recovered_task(task: dict[str, Any]) -> bool:
    error = task.get("error") if isinstance(task.get("error"), dict) else {}
    return str(task.get("task_status") or "").lower() == "suspended" and str(error.get("code") or "") == "task_recovered"


def _task_id_from_recovery_message(task: dict[str, Any]) -> str:
    error = task.get("error") if isinstance(task.get("error"), dict) else {}
    message = str(error.get("message") or "")
    match = re.search(r"\btask[-_][A-Za-z0-9]+\b", message)
    return match.group(0) if match else ""


def _resolve_recovered_task_id(base_url: str, topic_id: str, task: dict[str, Any]) -> str:
    parent_task_id = str(task.get("task_id") or "").strip()
    if topic_id:
        try:
            topic = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/topics/{urllib.parse.quote(topic_id)}", timeout=30)
        except EvalRunnerError:
            topic = {}
        current_task_id = str(topic.get("current_task_id") or "").strip()
        if current_task_id and current_task_id != parent_task_id:
            return current_task_id
    recovered_task_id = _task_id_from_recovery_message(task)
    if recovered_task_id and recovered_task_id != parent_task_id:
        return recovered_task_id
    return ""


def _poll_task(
    base_url: str,
    task_id: str,
    timeout_seconds: int,
    *,
    topic_id: str = "",
    observed_task_ids: list[str] | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Poll task status until terminal, following recovered/replacement tasks.

    Run evidence is no longer pulled from a per-poll event stream: after the task
    is terminal the runner reads the Chat V2 server-projected ``blocks`` from
    ``GET /topics/{id}/messages`` (the same projection the Chat V2 history uses).
    """
    deadline = time.time() + max(1, timeout_seconds)
    current_task_id = task_id
    seen_task_ids = {task_id}
    if observed_task_ids is not None and task_id not in observed_task_ids:
        observed_task_ids.append(task_id)
    errors: list[dict[str, Any]] = []
    last_task: dict[str, Any] = {}
    last_poll_error = ""
    while time.time() < deadline:
        try:
            last_task = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/tasks/{urllib.parse.quote(current_task_id)}", timeout=30)
            last_poll_error = ""
        except EvalRunnerError as exc:
            last_poll_error = str(exc)
            time.sleep(1.0)
            continue

        status = str(last_task.get("task_status") or "").lower()
        if status in TERMINAL_STATUSES:
            if _is_recovered_task(last_task):
                recovered_task_id = _resolve_recovered_task_id(base_url, topic_id, last_task)
                if recovered_task_id and recovered_task_id not in seen_task_ids:
                    current_task_id = recovered_task_id
                    seen_task_ids.add(recovered_task_id)
                    if observed_task_ids is not None and recovered_task_id not in observed_task_ids:
                        observed_task_ids.append(recovered_task_id)
                    continue
            return last_task, errors
        time.sleep(0.2)
    if last_poll_error:
        raise InfrastructureAbort(f"dataagent_poll_failed: {last_poll_error}")
    errors.append({"code": "timeout", "message": f"task did not finish within {timeout_seconds}s"})
    if not last_task:
        last_task = {"task_id": current_task_id, "task_status": "timeout"}
    else:
        last_task = dict(last_task)
        last_task["task_status"] = str(last_task.get("task_status") or "timeout")
    return last_task, errors


def _json_object_text(raw: str) -> str:
    text = str(raw or "").strip()
    if not text:
        return text
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines).strip()

    decoder = json.JSONDecoder()
    for index, char in enumerate(text):
        if char != "{":
            continue
        candidate = text[index:]
        try:
            parsed, end = decoder.raw_decode(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            return candidate[:end]

    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return text[start : end + 1]
    return text


def _normalize_float(value: Any, *, minimum: float = 0, maximum: float = 10) -> float:
    try:
        number = float(value)
    except Exception:
        number = minimum
    if number < minimum:
        return minimum
    if number > maximum:
        return maximum
    return number


def _normalize_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        text = value.strip().lower()
        if not text:
            return False
        if text in {"false", "0", "no", "n", "none", "null", "否", "无", "不存在"}:
            return False
        if text in {"true", "1", "yes", "y", "是", "有", "存在"}:
            return True
    return bool(value)


def _string_list(value: Any) -> list[str]:
    if isinstance(value, str):
        text = value.strip()
        return [text] if text else []
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for item in value:
        text = str(item or "").strip()
        if text:
            result.append(text)
    return result


def _normalize_judge_payload(data: dict[str, Any], *, raw_output: str = "") -> dict[str, Any]:
    dimensions: dict[str, float] = {}
    raw_dimensions = data.get("dimension_scores")
    if isinstance(raw_dimensions, dict):
        for key in JUDGE_DIMENSIONS:
            raw_value = raw_dimensions.get(key)
            if key == "result_consistency" and raw_value is None:
                raw_value = raw_dimensions.get("data_accuracy")
            dimensions[key] = _normalize_float(raw_value, minimum=0, maximum=DIMENSION_MAX[key])
    computed_score = sum(dimensions.values())
    raw_score = _normalize_float(data.get("score"), minimum=0, maximum=10)
    complete_dimensions = isinstance(raw_dimensions, dict) and all(
        key in raw_dimensions or (key == "result_consistency" and "data_accuracy" in raw_dimensions)
        for key in JUDGE_DIMENSIONS
    )
    inconsistent = complete_dimensions and abs(raw_score - computed_score) > 0.001
    return {
        "score": computed_score if complete_dimensions else raw_score,
        "dimension_scores": dimensions,
        "hallucination": _normalize_bool(data.get("hallucination")),
        "veto_rules_triggered": _string_list(data.get("veto_rules_triggered")),
        "failure_attribution": _dedupe(_string_list(data.get("failure_attribution")) + (["judge_score_inconsistent"] if inconsistent else [])),
        "comment": str(data.get("comment") or "").strip(),
        "judge_failed": _normalize_bool(data.get("judge_failed")) or inconsistent,
        "raw_output": raw_output,
    }


def _failed_judge(reason: str, *, raw_output: str = "", attribution: list[str] | None = None) -> dict[str, Any]:
    return {
        "score": 0,
        "dimension_scores": {},
        "hallucination": False,
        "veto_rules_triggered": [],
        "failure_attribution": attribution or ["judge_failed"],
        "comment": reason,
        "judge_failed": True,
        "raw_output": raw_output,
    }


def _merge_auto_failure_attribution(judge: dict[str, Any], rule_check: dict[str, Any]) -> dict[str, Any]:
    merged = dict(judge)
    merged["failure_attribution"] = _dedupe(
        list(rule_check.get("failure_attribution") or []) + list(judge.get("failure_attribution") or [])
    )
    return merged


def _case_for_judge(case: dict[str, Any]) -> dict[str, Any]:
    judge_case = dict(case)
    judge_case.pop("forbidden_sql_patterns", None)
    judge_case["veto_rules"] = [
        rule
        for rule in case.get("veto_rules") or []
        if "SQL 不带 schema 前缀" not in str(rule) and "SELECT *" not in str(rule)
    ]
    return judge_case


def _judge_system_prompt() -> str:
    return (
        "你是 DataAgent 在线问数评测裁判。只能基于请求中的 case、最终回答、工具事件、SQL/图表输出和自动规则检查打分。"
        "不要基于 SELECT *、schema 前缀或 SQL 风格合规性扣分；除非 SQL 风格直接导致未查到数据或结果错误。"
        "不要调用任何工具，不要编造事实。必须只输出一个 JSON 对象，字段为："
        "score, dimension_scores, hallucination, veto_rules_triggered, failure_attribution, comment。"
        "score 为各维度之和且范围 0 到 10；dimension_scores 包含 intent(1), ontology_entity(1), "
        "relation_scope(1), sql_or_tool_call(2), result_consistency(2), reasoning(2), answer_quality(1)。"
    )


def _judge_user_prompt(payload: dict[str, Any], *, repair: bool = False, previous_output: str = "") -> str:
    if repair:
        return (
            "上一次裁判输出不是合法 JSON。请只返回修复后的 JSON 对象，不要包含 Markdown 或解释。\n\n"
            f"上一次输出：\n{previous_output}\n\n"
            f"评测输入：\n{json.dumps(payload, ensure_ascii=False, indent=2)}"
        )
    return "请按 10 分制评估以下 DataAgent 问数回答，严格返回 JSON。\n\n" + json.dumps(payload, ensure_ascii=False, indent=2)


def _judge_message_content(payload: dict[str, Any], *, repair: bool = False, previous_output: str = "") -> str:
    return _judge_system_prompt() + "\n\n" + _judge_user_prompt(payload, repair=repair, previous_output=previous_output)


def _anthropic_messages_url(base_url: str) -> str:
    base = str(base_url or "").strip().rstrip("/")
    if not base:
        raise EvalRunnerError("judge base URL is required")
    if base.endswith("/v1/messages") or base.endswith("/messages"):
        return base
    return f"{base}/v1/messages"


def _extract_message_text(response: dict[str, Any]) -> str:
    parts: list[str] = []
    content = response.get("content")
    if isinstance(content, str):
        parts.append(content)
    elif isinstance(content, list):
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                value = item.get("text") or item.get("content") or item.get("result")
                if isinstance(value, str):
                    parts.append(value)
    if isinstance(response.get("result"), str):
        parts.append(response["result"])
    return "\n".join(part for part in parts if str(part or "").strip()).strip()


def call_judge_model(config: JudgeConfig, payload: dict[str, Any]) -> dict[str, Any]:
    raw_output = ""
    judge_payload = _compact_judge_payload(payload)
    headers = {
        "anthropic-version": "2023-06-01",
        "x-api-key": config.token,
        "Authorization": f"Bearer {config.token}",
    }
    max_attempts = 3
    for attempt in range(max_attempts):
        if attempt > 0:
            time.sleep(1)
        messages: list[dict[str, str]] = [
            {"role": "user", "content": _judge_message_content(judge_payload, repair=attempt > 0, previous_output=raw_output)},
            {"role": "assistant", "content": "{"},
        ]
        body = {
            "model": config.model,
            "max_tokens": config.max_tokens,
            "temperature": 0,
            "messages": messages,
        }
        try:
            response = http_json("POST", _anthropic_messages_url(config.base_url), body, timeout=config.timeout_seconds, headers=headers)
            raw_output = "{" + _extract_message_text(response)
            parsed = json.loads(_json_object_text(raw_output))
            if not isinstance(parsed, dict):
                raise ValueError("judge output is not a JSON object")
            return _normalize_judge_payload(parsed, raw_output=raw_output)
        except EvalRunnerError as exc:
            if attempt == max_attempts - 1:
                return _failed_judge(str(exc), raw_output=raw_output, attribution=["judge_failed", "judge_http_error"])
        except Exception as exc:
            if attempt == max_attempts - 1:
                return _failed_judge(f"裁判模型未返回合法 JSON: {exc}", raw_output=raw_output)
    return _failed_judge("裁判模型未返回合法 JSON", raw_output=raw_output)


def _judge_case(judge_config: JudgeConfig, case: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    with _opik_span(
        "dataagent.judge",
        span_type="llm",
        metadata={"case_id": case.get("case_id"), "judge_prompt_version": JUDGE_PROMPT_VERSION},
    ):
        judge = call_judge_model(judge_config, payload)
    judge["case_id"] = case.get("case_id")
    return judge


def _fetch_sdk_events(base_url: str, task_id: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    after_id = 0
    while True:
        page = dataagent_http_json(
            "GET",
            f"{base_url}/api/v1/nl2sql/tasks/{urllib.parse.quote(task_id)}/sdk-events?after_id={after_id}&limit=500",
            timeout=30,
        )
        batch = [
            {**item, "_evaluation_task_id": task_id}
            for item in page.get("records") or []
            if isinstance(item, dict)
        ]
        records.extend(batch)
        if not page.get("has_more") or not batch:
            break
        next_after = int(page.get("next_after_id") or batch[-1].get("seq_id") or after_id)
        if next_after <= after_id:
            break
        after_id = next_after
    return records


def _sdk_metrics(records: list[dict[str, Any]]) -> dict[str, int]:
    turns = {
        (str(item.get("_evaluation_task_id") or item.get("task_id") or ""), int(item.get("turn_index") or 0))
        for item in records
        if int(item.get("turn_index") or 0) > 0
    }
    tool_errors = sum(1 for item in records if item.get("record_type") == "tool_result" and bool((item.get("data") or {}).get("is_error")))
    recoveries = sum(
        1 for item in records
        if "recover" in str(item.get("record_type") or "").lower()
        or "recover" in str(item.get("event_type") or "").lower()
    )
    return {"agent_turn_count": len(turns), "tool_error_count": tool_errors, "recovery_count": recoveries}


def _parse_time(value: Any) -> dt.datetime | None:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None


def _seconds_between(left: Any, right: Any) -> float | None:
    start = _parse_time(left)
    end = _parse_time(right)
    if not start or not end:
        return None
    if start.tzinfo is None:
        start = start.replace(tzinfo=dt.timezone.utc)
    if end.tzinfo is None:
        end = end.replace(tzinfo=dt.timezone.utc)
    return round(max(0.0, (end - start).total_seconds()), 3)


def _sum_task_seconds(tasks: list[dict[str, Any]], left: str, right: str) -> float | None:
    values = [_seconds_between(task.get(left), task.get(right)) for task in tasks]
    present = [value for value in values if value is not None]
    return round(sum(present), 3) if present else None


def _query_rows_from_value(value: Any) -> list[dict[str, Any]] | None:
    for item in _iter_structured_evidence(value):
        if isinstance(item, dict) and isinstance(item.get("rows"), list):
            rows = item.get("rows") or []
            if all(isinstance(row, dict) for row in rows):
                return rows
    return None


def _actual_query_row_sets(blocks: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    row_sets: list[list[dict[str, Any]]] = []
    for block in blocks:
        if _is_successful_sql_execution(block):
            rows = _query_rows_from_value(block.get("output"))
            if rows is not None:
                row_sets.append(rows)
    return row_sets


def _canonical_rows(rows: list[dict[str, Any]], *, tolerance: float = 0.0) -> list[str]:
    normalized: list[str] = []
    for row in rows:
        clean: dict[str, Any] = {}
        for key, value in row.items():
            if isinstance(value, float) and tolerance > 0:
                digits = max(0, min(12, int(abs(__import__("math").log10(tolerance))) * -1)) if tolerance < 1 else 0
                clean[str(key)] = round(value, digits)
            else:
                clean[str(key)] = value
        normalized.append(json.dumps(clean, ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":")))
    return sorted(normalized)


def _reference_sql_failure(
    case: dict[str, Any],
    reference: dict[str, Any],
    sql: str,
    *,
    error_code: str,
    cause: str,
    partial_results: list[dict[str, Any]] | None = None,
) -> InfrastructureAbort:
    details = {
        "error_code": error_code,
        "case_id": str(case.get("case_id") or "unknown"),
        "database": str(reference.get("database") or ""),
        "engine": str(reference.get("engine") or ""),
        "sql": sql,
        "sql_sha256": hashlib.sha256(sql.encode("utf-8")).hexdigest(),
        "cause": str(cause or "unknown reference SQL failure"),
    }
    message = (
        f"{error_code}: case_id={details['case_id']}, database={details['database'] or 'N/A'}, "
        f"engine={details['engine'] or 'N/A'}, sql_sha256={details['sql_sha256']}, "
        f"sql={json.dumps(sql, ensure_ascii=False)}; cause={details['cause']}"
    )
    return InfrastructureAbort(message, partial_results=partial_results, details=details)


def _execute_reference_query(base_url: str, case: dict[str, Any], topic_id: str) -> dict[str, Any]:
    expected_result = case.get("expected_result") if isinstance(case.get("expected_result"), dict) else {}
    reference = expected_result.get("reference_query") if isinstance(expected_result.get("reference_query"), dict) else None
    if not reference:
        return {"applicable": False, "passed": None, "rows": None}
    sql = str(reference.get("sql") or "").strip()
    if not re.match(r"(?is)^\s*(?:select|with)\b", sql) or re.search(r"(?is)\b(?:insert|update|delete|drop|alter|truncate|create|grant|revoke)\b", sql):
        raise _reference_sql_failure(
            case,
            reference,
            sql,
            error_code="reference_sql_invalid",
            cause="only read-only SELECT/CTE SQL is allowed",
        )
    payload = {
        "sql": sql,
        "database": str(reference.get("database") or "").strip(),
        "engine": str(reference.get("engine") or "").strip() or None,
        "limit": int(reference.get("limit") or 1000),
        "timeout_seconds": int(reference.get("timeout_seconds") or 60),
        "topic_id": topic_id,
    }
    try:
        response = dataagent_http_json(
            "POST",
            f"{base_url}/api/v1/nl2sql/query/execute",
            payload,
            timeout=payload["timeout_seconds"] + 15,
        )
    except InfrastructureAbort as exc:
        raise _reference_sql_failure(
            case,
            reference,
            sql,
            error_code="reference_sql_failed",
            cause=str(exc),
            partial_results=exc.partial_results,
        ) from exc
    except EvalRunnerError as exc:
        raise _reference_sql_failure(
            case,
            reference,
            sql,
            error_code="reference_sql_failed",
            cause=str(exc),
        ) from exc
    rows = response.get("rows") if isinstance(response.get("rows"), list) else None
    result_state = str(response.get("result_state") or "success").lower()
    if rows is None or result_state not in {"success", "empty", "empty_result"}:
        detail = str(response.get("error") or response.get("message") or "").strip()
        suffix = f", detail={detail}" if detail else ""
        raise _reference_sql_failure(
            case,
            reference,
            sql,
            error_code="reference_sql_failed",
            cause=f"result_state={result_state}, rows_present={rows is not None}{suffix}",
        )
    return {"applicable": True, "passed": None, "rows": rows, "row_count": len(rows), "database": payload["database"]}


def _compare_reference(reference: dict[str, Any], actual_row_sets: list[list[dict[str, Any]]], case: dict[str, Any]) -> dict[str, Any]:
    if not reference.get("applicable"):
        return reference
    expected_result = case.get("expected_result") if isinstance(case.get("expected_result"), dict) else {}
    query = expected_result.get("reference_query") if isinstance(expected_result.get("reference_query"), dict) else {}
    tolerance = float(query.get("numeric_tolerance") or 0.0)
    mode = str(query.get("comparison_mode") or "unordered_rows")
    expected_rows = reference.get("rows") if isinstance(reference.get("rows"), list) else []
    def matches(actual_rows: list[dict[str, Any]]) -> bool:
        if mode == "scalar":
            expected_value = next(iter(expected_rows[0].values()), None) if expected_rows else None
            actual_value = next(iter(actual_rows[0].values()), None) if actual_rows else None
            try:
                return abs(float(expected_value) - float(actual_value)) <= tolerance
            except (TypeError, ValueError):
                return str(expected_value) == str(actual_value)
        if mode == "unordered_values":
            normalize_values = lambda rows: sorted(json.dumps(sorted((str(value) for value in row.values())), ensure_ascii=False) for row in rows)
            return normalize_values(expected_rows) == normalize_values(actual_rows)
        return _canonical_rows(expected_rows, tolerance=tolerance) == _canonical_rows(actual_rows, tolerance=tolerance)

    matched_rows = next((rows for rows in actual_row_sets if matches(rows)), None)
    return {
        **reference,
        "passed": matched_rows is not None,
        "comparison_mode": mode,
        "actual_row_count": None if matched_rows is None else len(matched_rows),
        "candidate_result_count": len(actual_row_sets),
    }


def _case_with_reference_empty_policy(case: dict[str, Any], reference: dict[str, Any]) -> dict[str, Any]:
    """Treat a successfully established empty truth set as an expected result."""
    if not reference.get("applicable") or int(reference.get("row_count") or 0) != 0:
        return case
    expected_result = case.get("expected_result") if isinstance(case.get("expected_result"), dict) else {}
    return {**case, "expected_result": {**expected_result, "allow_empty": True}}


def run_case(base_url: str, case: dict[str, Any], args: argparse.Namespace, judge_config: JudgeConfig) -> dict[str, Any]:
    submitted_clock: float | None = None
    e2e_seconds = 0.0
    errors: list[dict[str, Any]] = []
    topic_id = ""
    task_id = ""
    task: dict[str, Any] = {}
    message: dict[str, Any] = {}
    final_answer = ""
    conversation: list[dict[str, Any]] = []
    sdk_events: list[dict[str, Any]] = []
    task_ids: list[str] = []
    completed_tasks: list[dict[str, Any]] = []
    task_records: list[dict[str, Any]] = []
    message_items: list[dict[str, Any]] = []
    reference_result: dict[str, Any] = {"applicable": False, "passed": None, "rows": None}
    try:
        topic_id = _create_topic(base_url, case, str(args.agent_id or "").strip())
        limits = case.get("limits") if isinstance(case.get("limits"), dict) else {}
        case_timeout = min(max(1, args.timeout_seconds), int(limits.get("max_wait_seconds") or args.timeout_seconds or 900))
        final_task_id = ""
        for turn in _case_turns(case):
            if submitted_clock is None:
                submitted_clock = time.time()
            task_id = _submit_task(base_url, topic_id, case, args, turn)
            task, poll_errors = _poll_task(
                base_url,
                task_id,
                case_timeout,
                topic_id=topic_id,
                observed_task_ids=task_ids,
            )
            errors.extend(poll_errors)
            final_task_id = str(task.get("task_id") or task_id).strip() or task_id
            if final_task_id not in task_ids:
                task_ids.append(final_task_id)
            completed_tasks.append(task)
            status = str(task.get("task_status") or "").lower()
            if status and status not in SUCCESS_STATUSES:
                errors.append({"code": status, "message": json.dumps(task.get("error") or {}, ensure_ascii=False)})
                break
        for observed_task_id in task_ids:
            task_records.append(
                dataagent_http_json(
                    "GET",
                    f"{base_url}/api/v1/nl2sql/tasks/{urllib.parse.quote(observed_task_id)}",
                    timeout=30,
                )
            )
            sdk_events.extend(_fetch_sdk_events(base_url, observed_task_id))
        messages = _fetch_topic_messages(base_url, topic_id)
        message_items = [item for item in messages.get("items") or [] if isinstance(item, dict)]
        message = _final_assistant_message(messages, final_task_id)
        conversation = _build_conversation_log(messages)
        final_answer = str(message.get("content") or "").strip()
        e2e_seconds = round(time.time() - submitted_clock, 3) if submitted_clock is not None else 0.0
        reference_result = _execute_reference_query(base_url, case, topic_id)
    except InfrastructureAbort:
        raise
    except EvalRunnerError as exc:
        raise InfrastructureAbort(f"dataagent_run_failed: {exc}") from exc

    blocks = [
        block
        for topic_message in message_items
        for block in (topic_message.get("blocks") if isinstance(topic_message.get("blocks"), list) else [])
        if isinstance(block, dict)
    ]
    tool_names = _collect_tool_names(blocks)
    sql_outputs = _extract_sql_outputs(blocks, final_answer)
    chart_outputs = _extract_chart_outputs(blocks)
    usage = _aggregate_usage(task_records, message_items)
    sdk_counts = _sdk_metrics(sdk_events)
    tool_call_count = len([block for block in blocks if isinstance(block, dict) and block.get("type") == "tool_use"])
    expected_turn_count = len(_case_turns(case))
    task_completed = (
        len(completed_tasks) == expected_turn_count
        and all(str(item.get("task_status") or "").lower() in SUCCESS_STATUSES for item in completed_tasks)
    )
    rule_case = _case_with_reference_empty_policy(case, reference_result)
    rule_check = auto_rule_check(rule_case, final_answer=final_answer, blocks=blocks, sql_outputs=sql_outputs, tool_names=tool_names)
    _apply_runtime_hard_gates(
        rule_check,
        case,
        task_completed=task_completed,
        agent_turn_count=int(sdk_counts.get("agent_turn_count") or 0),
        tool_call_count=tool_call_count,
    )
    reference_result = _compare_reference(reference_result, _actual_query_row_sets(blocks), case)
    if reference_result.get("applicable") and not reference_result.get("passed"):
        rule_check["passed"] = False
        rule_check.setdefault("failure_attribution", []).append("reference_data_mismatch")
        rule_check.setdefault("hard_gates", {})["reference_data_accuracy"] = False
    elif reference_result.get("applicable"):
        rule_check.setdefault("hard_gates", {})["reference_data_accuracy"] = True
    judge_payload = {
        "case": _case_for_judge(case),
        "user_question": _case_question(case),
        "final_answer": final_answer,
        "task_status": str(task.get("task_status") or ""),
        "task_error": task.get("error") if isinstance(task.get("error"), dict) else None,
        "tool_events": _summarize_tool_events(blocks),
        "sql_outputs": sql_outputs,
        "chart_outputs": chart_outputs,
        "auto_rule_check": rule_check,
    }
    judge_started = time.time()
    judge = _judge_case(judge_config, case, judge_payload) if task_id else {
        "score": 0,
        "dimension_scores": {},
        "hallucination": False,
        "veto_rules_triggered": [],
        "failure_attribution": ["task_not_submitted"],
        "comment": "task was not submitted",
        "judge_failed": True,
    }
    judge_seconds = round(time.time() - judge_started, 3)
    judge = _merge_auto_failure_attribution(judge, rule_check)
    veto_rules = list(rule_check.get("triggered_veto_rules") or []) + list(judge.get("veto_rules_triggered") or [])
    rule_check.setdefault("hard_gates", {})["no_hallucination"] = not bool(judge.get("hallucination"))
    rule_check.setdefault("hard_gates", {})["no_veto"] = not bool(veto_rules)
    if bool(judge.get("hallucination")) or veto_rules:
        rule_check["passed"] = False
    case_passed = (
        not errors
        and str(task.get("task_status") or "").lower() in SUCCESS_STATUSES
        and bool(rule_check.get("passed", True))
        and float(judge.get("score") or 0) >= 8
        and not bool(judge.get("judge_failed"))
        and not bool(judge.get("hallucination"))
        and not veto_rules
    )
    timing = {
        "queue_wait_seconds": _sum_task_seconds(task_records, "created_at", "started_at"),
        "execution_seconds": _sum_task_seconds(task_records, "started_at", "finished_at"),
        "e2e_seconds": e2e_seconds,
        "judge_seconds": judge_seconds,
    }
    return {
        "evaluation_engine": EVALUATION_ENGINE,
        "engine_version": ENGINE_VERSION,
        "metric_semantics_version": METRIC_SEMANTICS_VERSION,
        "judge_prompt_version": JUDGE_PROMPT_VERSION,
        "case_id": case.get("case_id"),
        "category": case.get("category"),
        "question": _case_question(case),
        "turns": _case_turns(case),
        "agent_id": str(args.agent_id or "").strip(),
        "topic_id": topic_id,
        "task_id": str(task.get("task_id") or task_id),
        "task_status": str(task.get("task_status") or ""),
        "final_answer": final_answer,
        "tool_names": tool_names,
        "sql_outputs": sql_outputs,
        "chart_outputs": chart_outputs,
        "usage": usage,
        "duration_seconds": e2e_seconds,
        "timing": timing,
        "user_turn_count": len(_case_turns(case)),
        **sdk_counts,
        "tool_call_count": tool_call_count,
        "sql_execution_count": len([block for block in blocks if _is_successful_sql_execution(block)]),
        "reference_data_accuracy": {key: value for key, value in reference_result.items() if key != "rows"},
        "sdk_event_count": len(sdk_events),
        "auto_rule_check": rule_check,
        "judge": judge,
        "veto_rules_triggered": veto_rules,
        "case_passed": case_passed,
        "errors": errors,
        "conversation": conversation,
    }


class DataAgentOpikMetric(OpikBaseMetric):  # type: ignore[misc, valid-type]
    """Expose the V2 dimensions as native Opik Experiment feedback scores."""

    def __init__(self) -> None:
        super().__init__(name="dataagent_v2", track=False)

    @staticmethod
    def _result(case_result: Any = None, output: Any = None, **kwargs: Any) -> dict[str, Any]:
        for candidate in (case_result, kwargs.get("case_result"), output, kwargs.get("output")):
            if isinstance(candidate, dict) and isinstance(candidate.get("case_result"), dict):
                return candidate["case_result"]
            if isinstance(candidate, dict) and "case_id" in candidate and "judge" in candidate:
                return candidate
        return {}

    def score(self, case_result: Any = None, output: Any = None, **kwargs: Any) -> list[Any]:
        if ScoreResult is None:
            raise EvalRunnerError("opik==2.1.32 is required")
        result = self._result(case_result, output, **kwargs)
        judge = result.get("judge") if isinstance(result.get("judge"), dict) else {}
        dimensions = judge.get("dimension_scores") if isinstance(judge.get("dimension_scores"), dict) else {}
        scores = [
            ScoreResult(
                name=f"v2.{name}",
                value=float(dimensions.get(name) or 0),
                reason=str(judge.get("comment") or ""),
            )
            for name in JUDGE_DIMENSIONS
        ]
        reference = result.get("reference_data_accuracy") if isinstance(result.get("reference_data_accuracy"), dict) else {}
        consistency = (result.get("auto_rule_check") or {}).get("result_consistency") or {}
        scores.extend(
            [
                ScoreResult(name="v2.total", value=float(judge.get("score") or 0)),
                ScoreResult(name="v2.hard_gate", value=1.0 if result.get("case_passed") else 0.0),
                ScoreResult(
                    name="v2.result_consistency",
                    value=1.0 if consistency.get("passed") else 0.0,
                    reason="N/A" if not consistency.get("applicable") else "final answer vs Agent query result",
                ),
            ]
        )
        if reference.get("applicable"):
            scores.append(
                ScoreResult(
                    name="v2.data_accuracy",
                    value=1.0 if reference.get("passed") else 0.0,
                    reason="independent read-only reference SQL comparison",
                )
            )
        return scores


def _ensure_opik_available() -> None:
    if opik is None or Opik is None or opik_evaluate is None or ScoreResult is None:
        raise EvalRunnerError("Opik runner requires opik==2.1.32; install tools/dataagent-evals/opik/requirements.txt")
    installed = str(getattr(opik, "__version__", ""))
    if installed and installed != "2.1.32":
        raise EvalRunnerError(f"Opik SDK version mismatch: expected 2.1.32, got {installed}")


def _opik_dataset_items(cases: list[dict[str, Any]], dataset_hash: str) -> list[dict[str, Any]]:
    return [
        {
            "case_id": str(case.get("case_id") or ""),
            "category": str(case.get("category") or ""),
            "suite_tags": list(case.get("suite_tags") or []),
            "source_file_hash": dataset_hash,
            "case": case,
        }
        for case in cases
    ]


def _run_opik_experiment(
    base_url: str,
    cases: list[dict[str, Any]],
    args: argparse.Namespace,
    judge_config: JudgeConfig,
    dataset_stats: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    global _OPIK_PROJECT_NAME
    _ensure_opik_available()
    _OPIK_PROJECT_NAME = str(args.opik_project_name or "dataagent-evals").strip()
    dataset_hash = str(dataset_stats.get("dataset_hash") or "")
    dataset_id = str(dataset_stats.get("dataset_id") or "dataset")
    dataset_name = str(args.opik_dataset_name or "").strip() or f"{dataset_id}-{dataset_hash[:12]}"
    experiment_name = str(args.opik_experiment_name or "").strip() or (
        f"{str(args.run_label or 'run')}-{str(args.model or 'default')}-{_timestamp()}"
    )
    try:
        client = Opik(host=str(args.opik_base_url or "").rstrip("/"), project_name=_OPIK_PROJECT_NAME)
        dataset = client.get_or_create_dataset(
            name=dataset_name,
            description=f"DataAgent V2 dataset sha256={dataset_hash}",
        )
        dataset.insert(_opik_dataset_items(cases, dataset_hash))
    except Exception as exc:
        raise InfrastructureAbort(f"opik_dataset_write_failed: {exc}") from exc

    results_by_case: dict[str, dict[str, Any]] = {}
    results_lock = Lock()
    infrastructure_errors: list[str] = []

    def evaluation_task(case: dict[str, Any], case_id: str = "", **_: Any) -> dict[str, Any]:
        resolved_case_id = str(case.get("case_id") or case_id)
        try:
            with _opik_span(
                "dataagent.evaluation.case",
                span_type="general",
                metadata={
                    "case_id": resolved_case_id,
                    "metric_semantics_version": METRIC_SEMANTICS_VERSION,
                    "judge_prompt_version": JUDGE_PROMPT_VERSION,
                },
            ):
                result = run_case(base_url, case, args, judge_config)
            with results_lock:
                results_by_case[resolved_case_id] = result
            return {"output": str(result.get("final_answer") or ""), "case_result": result}
        except InfrastructureAbort as exc:
            with results_lock:
                infrastructure_errors.append(str(exc))
            raise

    tracked_task = opik.track(
        name="dataagent-evaluation-case",
        project_name=_OPIK_PROJECT_NAME,
    )(evaluation_task)
    experiment_config = {
        "evaluation_engine": EVALUATION_ENGINE,
        "engine_version": ENGINE_VERSION,
        "metric_semantics_version": METRIC_SEMANTICS_VERSION,
        "judge_prompt_version": JUDGE_PROMPT_VERSION,
        "dataset_hash": dataset_hash,
        "dataset_version": dataset_stats.get("schema_version"),
        "skill_snapshot_hash": dataset_stats.get("agent_snapshot_hash"),
        "model": str(args.model or ""),
        "judge_model": str(args.judge_model or ""),
        "concurrency": int(args.concurrency),
        "environment_label": str(args.environment_label or "local"),
        "auth_enabled": bool(dataset_stats.get("auth_enabled")),
    }
    try:
        opik_evaluate(
            dataset=dataset,
            task=tracked_task,
            scoring_metrics=[DataAgentOpikMetric()],
            experiment_name=experiment_name,
            project_name=_OPIK_PROJECT_NAME,
            experiment_config=experiment_config,
            task_threads=int(args.concurrency),
        )
        if hasattr(client, "flush"):
            client.flush()
    except Exception as exc:
        partial = [results_by_case[str(case.get("case_id") or "")] for case in cases if str(case.get("case_id") or "") in results_by_case]
        reason = infrastructure_errors[0] if infrastructure_errors else f"opik_experiment_failed: {exc}"
        raise InfrastructureAbort(reason, partial_results=partial) from exc
    missing = [str(case.get("case_id") or "") for case in cases if str(case.get("case_id") or "") not in results_by_case]
    if missing:
        partial = [results_by_case[str(case.get("case_id") or "")] for case in cases if str(case.get("case_id") or "") in results_by_case]
        raise InfrastructureAbort(f"opik_experiment_missing_results: {','.join(missing)}", partial_results=partial)
    ordered = [results_by_case[str(case.get("case_id") or "")] for case in cases]
    return ordered, {"opik_project_name": _OPIK_PROJECT_NAME, "opik_dataset_name": dataset_name, "opik_experiment_name": experiment_name}


def _run_cases(base_url: str, cases: list[dict[str, Any]], args: argparse.Namespace, judge_config: JudgeConfig) -> list[dict[str, Any]]:
    if args.concurrency <= 1:
        completed: list[dict[str, Any]] = []
        for case in cases:
            try:
                completed.append(run_case(base_url, case, args, judge_config))
            except InfrastructureAbort as exc:
                exc.partial_results = completed
                raise
        return completed

    results: list[dict[str, Any] | None] = [None] * len(cases)
    # Submit in bounded batches so an authentication/infrastructure failure stops
    # later cases instead of eagerly submitting the entire suite.
    for batch_start in range(0, len(cases), args.concurrency):
        batch = list(enumerate(cases[batch_start : batch_start + args.concurrency], start=batch_start))
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            future_to_index = {pool.submit(run_case, base_url, case, args, judge_config): index for index, case in batch}
            for future in as_completed(future_to_index):
                index = future_to_index[future]
                try:
                    results[index] = future.result()
                except InfrastructureAbort as exc:
                    exc.partial_results = [item for item in results if item is not None]
                    for pending in future_to_index:
                        pending.cancel()
                    raise
                except Exception as exc:
                    case = cases[index]
                    results[index] = {
                        "evaluation_engine": EVALUATION_ENGINE,
                        "engine_version": ENGINE_VERSION,
                        "case_id": case.get("case_id"), "category": case.get("category"),
                        "question": _case_question(case), "turns": _case_turns(case),
                        "agent_id": str(getattr(args, "agent_id", "") or "").strip(),
                        "task_status": "runner_error", "final_answer": "", "tool_names": [],
                        "sql_outputs": [], "chart_outputs": [], "usage": {}, "duration_seconds": 0,
                        "timing": {}, "auto_rule_check": {"passed": False, "failure_attribution": ["runner_crash"]},
                        "judge": {"score": 0, "judge_failed": True, "failure_attribution": ["runner_crash"]},
                        "veto_rules_triggered": [], "case_passed": False,
                        "errors": [{"code": "runner_crash", "message": str(exc)}],
                    }
                result = results[index]
                case_id = (result or {}).get("case_id") if result else "?"
                done = len([r for r in results if r is not None])
                print(f"[{done}/{len(cases)}] {case_id} done", file=sys.stderr)
    return [r for r in results if r is not None]


def _avg(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(float(value) for value in values)
    position = (len(ordered) - 1) * percentile
    low = int(position)
    high = min(len(ordered) - 1, low + 1)
    value = ordered[low] + (ordered[high] - ordered[low]) * (position - low)
    return round(value, 4)


def _ratio(numerator: float, denominator: float) -> dict[str, Any]:
    return {
        "numerator": round(numerator, 4),
        "denominator": round(denominator, 4),
        "value": round(numerator / denominator, 4) if denominator else None,
    }


def _numeric_usage(result: dict[str, Any], names: tuple[str, ...]) -> float | None:
    usage = result.get("usage") if isinstance(result.get("usage"), dict) else {}
    for name in names:
        if name not in usage or usage.get(name) is None:
            continue
        try:
            return float(usage.get(name))
        except Exception:
            continue
    return None


def build_summary(results: list[dict[str, Any]], dataset_stats: dict[str, Any], *, dry_run: bool = False) -> dict[str, Any]:
    if dry_run:
        return {
            **dataset_stats,
            "evaluation_engine": EVALUATION_ENGINE,
            "engine_version": ENGINE_VERSION,
            "metric_semantics_version": METRIC_SEMANTICS_VERSION,
            "judge_prompt_version": JUDGE_PROMPT_VERSION,
            "run_status": "dry_run",
            "dry_run": True,
            "passed": True,
            "recommendation": "dry-run",
        }
    total = len(results)
    accepted = [item for item in results if str(item.get("task_id") or "").strip()]
    completed = [item for item in accepted if str(item.get("task_status") or "").lower() in SUCCESS_STATUSES]
    effective = [item for item in results if bool(item.get("case_passed"))]
    scores = [float((item.get("judge") or {}).get("score") or 0) for item in results]
    dimensions = [item.get("judge", {}).get("dimension_scores") or {} for item in completed]
    intent = _ratio(sum(1 for dim in dimensions if float(dim.get("intent") or 0) >= 1), len(dimensions))
    ontology = _ratio(sum(1 for dim in dimensions if float(dim.get("ontology_entity") or 0) >= 1), len(dimensions))
    relation = _ratio(sum(1 for dim in dimensions if float(dim.get("relation_scope") or 0) >= 1), len(dimensions))
    tool_sql = _ratio(sum(1 for item in completed if bool((item.get("auto_rule_check") or {}).get("hard_gates", {}).get("sql_execution_and_口径"))), len(completed))
    time_cases = [item for item in completed if bool((item.get("auto_rule_check") or {}).get("time_dimension", {}).get("applicable"))]
    time_accuracy = _ratio(sum(1 for item in time_cases if bool((item.get("auto_rule_check") or {}).get("time_dimension", {}).get("passed"))), len(time_cases))
    answer = _ratio(sum(1 for dim in dimensions if float(dim.get("answer_quality") or 0) >= 1), len(dimensions))
    result_cases = [item for item in completed if bool((item.get("auto_rule_check") or {}).get("result_consistency", {}).get("applicable"))]
    result_consistency = _ratio(sum(1 for item in result_cases if bool((item.get("auto_rule_check") or {}).get("result_consistency", {}).get("passed"))), len(result_cases))
    reference_cases = [item for item in completed if bool((item.get("reference_data_accuracy") or {}).get("applicable"))]
    data_accuracy = _ratio(sum(1 for item in reference_cases if bool((item.get("reference_data_accuracy") or {}).get("passed"))), len(reference_cases))
    hallucination_rate = _avg([1.0 if bool(item.get("judge", {}).get("hallucination")) else 0.0 for item in results])
    veto_count = sum(len(item.get("veto_rules_triggered") or []) for item in results)
    judge_failed_count = sum(1 for item in results if bool(item.get("judge", {}).get("judge_failed")))
    assertion_pass = 0.0
    assertion_total = 0.0
    for item in completed:
        dim = (item.get("judge") or {}).get("dimension_scores") or {}
        checks = {
            "intent": float(dim.get("intent") or 0) >= 1,
            "ontology_entity": float(dim.get("ontology_entity") or 0) >= 1,
            "relation_scope": float(dim.get("relation_scope") or 0) >= 1,
            "sql_or_tool_call": bool((item.get("auto_rule_check") or {}).get("hard_gates", {}).get("required_tool_execution")),
            "result_consistency": bool((item.get("reference_data_accuracy") or {}).get("passed")) if (item.get("reference_data_accuracy") or {}).get("applicable") else bool((item.get("auto_rule_check") or {}).get("result_consistency", {}).get("passed")),
            "reasoning": float(dim.get("reasoning") or 0) >= 2,
            "answer_quality": float(dim.get("answer_quality") or 0) >= 1,
        }
        for key, maximum in DIMENSION_MAX.items():
            assertion_total += maximum
            if checks[key]:
                assertion_pass += maximum
    timing_metrics: dict[str, Any] = {}
    for name in ("queue_wait_seconds", "execution_seconds", "e2e_seconds", "judge_seconds"):
        values = [float((item.get("timing") or {}).get(name)) for item in results if isinstance((item.get("timing") or {}).get(name), (int, float))]
        timing_metrics[name] = {"average": round(_avg(values), 4) if values else None, "p50": _percentile(values, .50), "p90": _percentile(values, .90), "p95": _percentile(values, .95)}
    count_metrics: dict[str, Any] = {}
    count_sources = {
        "user_turn_count": lambda item: float(item.get("user_turn_count") or 0),
        "agent_turn_count": lambda item: float(item.get("agent_turn_count") or 0),
        "tool_call_count": lambda item: float(item.get("tool_call_count") or 0),
        "sql_execution_count": lambda item: float(item.get("sql_execution_count") or 0),
        "input_tokens": lambda item: _numeric_usage(item, ("input_tokens", "inputTokens")),
        "output_tokens": lambda item: _numeric_usage(item, ("output_tokens", "outputTokens")),
        "cache_tokens": lambda item: _numeric_usage(item, ("cache_read_input_tokens", "cache_tokens", "cacheTokens")),
    }
    for name, getter in count_sources.items():
        values = [value for item in results if isinstance((value := getter(item)), (int, float))]
        count_metrics[name] = {"average": round(_avg(values), 4) if values else None, "p95": _percentile(values, .95)}
    ratios = {
        "completion_rate": _ratio(len(completed), len(accepted)),
        "business_accuracy": _ratio(assertion_pass, assertion_total),
        "effective_pass_rate": _ratio(len(effective), total),
        "semantic_accuracy": _ratio(intent["numerator"] + ontology["numerator"] + relation["numerator"], intent["denominator"] + ontology["denominator"] + relation["denominator"]),
        "tool_sql_accuracy": tool_sql,
        "answer_accuracy": answer,
        "result_consistency_rate": result_consistency,
        "data_accuracy": data_accuracy,
        "time_accuracy": time_accuracy,
    }
    metrics = {
        "average_score": round(_avg(scores), 4),
        "intent_accuracy": intent["value"],
        "ontology_accuracy": ontology["value"],
        "relation_accuracy": relation["value"],
        "hallucination_rate": round(hallucination_rate, 4),
        **ratios,
        "timing": timing_metrics,
        "counts": count_metrics,
    }
    gates_passed = (
        bool(results)
        and ratios["completion_rate"]["value"] == 1.0
        and ratios["effective_pass_rate"]["value"] == 1.0
        and metrics["hallucination_rate"] <= GATES["hallucination_rate"]
        and veto_count == 0
        and judge_failed_count == 0
        and all(bool(item.get("case_passed")) for item in results)
    )
    return {
        **dataset_stats,
        "evaluation_engine": EVALUATION_ENGINE,
        "engine_version": ENGINE_VERSION,
        "metric_semantics_version": METRIC_SEMANTICS_VERSION,
        "judge_prompt_version": JUDGE_PROMPT_VERSION,
        "run_status": "completed",
        "dry_run": False,
        "total_cases": total,
        "passed_cases": sum(1 for item in results if bool(item.get("case_passed"))),
        "failed_cases": sum(1 for item in results if not bool(item.get("case_passed"))),
        "veto_count": veto_count,
        "judge_failed_count": judge_failed_count,
        "metrics": metrics,
        "gates": GATES,
        "passed": gates_passed,
        "recommendation": "建议上线" if gates_passed else "不建议上线",
    }


def write_outputs(output_dir: Path, results: list[dict[str, Any]], summary: dict[str, Any]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = output_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    if results:
        with (output_dir / "cases.jsonl").open("w", encoding="utf-8") as handle:
            for item in results:
                handle.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")
                (raw_dir / f"{item.get('case_id')}.json").write_text(
                    json.dumps(item, ensure_ascii=False, indent=2, sort_keys=True),
                    encoding="utf-8",
                )
    if results:
        conversations_dir = output_dir / "conversations"
        conversations_dir.mkdir(parents=True, exist_ok=True)
        for item in results:
            case_id = str(item.get("case_id") or "unknown")
            conversation_entry = {
                "case_id": case_id,
                "category": item.get("category"),
                "question": item.get("question"),
                "topic_id": item.get("topic_id"),
                "task_id": item.get("task_id"),
                "task_status": item.get("task_status"),
                "score": float((item.get("judge") or {}).get("score") or 0),
                "case_passed": bool(item.get("case_passed")),
                "conversation": item.get("conversation") or [],
            }
            (conversations_dir / f"{case_id}.json").write_text(
                json.dumps(conversation_entry, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
    (output_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    (output_dir / "report.md").write_text(render_report(summary, results), encoding="utf-8")
    run_payload = {
        key: summary.get(key)
        for key in (
            "evaluation_engine", "engine_version", "metric_semantics_version", "judge_prompt_version",
            "run_status", "run_label", "environment_label", "dataset_id", "dataset_hash", "schema_version",
            "case_ids", "agent_id", "agent_snapshot_hash", "model", "judge_model", "concurrency", "auth_enabled",
            "opik_project_name", "opik_dataset_name", "opik_experiment_name",
        )
    }
    (output_dir / "run.json").write_text(json.dumps(run_payload, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    report_text = render_report(summary, results)
    summary_json = html.escape(json.dumps(summary, ensure_ascii=False, indent=2))
    rows = "".join(
        f"<tr><td>{html.escape(str(item.get('case_id') or ''))}</td><td>{html.escape(str(item.get('category') or ''))}</td>"
        f"<td>{float((item.get('judge') or {}).get('score') or 0):.2f}</td><td>{'PASS' if item.get('case_passed') else 'FAIL'}</td></tr>"
        for item in results
    )
    document = f"""<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>DataAgent Eval</title>
<style>body{{font:14px system-ui;margin:32px;max-width:1200px}}table{{border-collapse:collapse;width:100%}}th,td{{border:1px solid #ddd;padding:8px;text-align:left}}th{{background:#f4f6f8}}pre{{background:#f6f8fa;padding:16px;overflow:auto}}</style>
<h1>DataAgent {html.escape(EVALUATION_ENGINE)} Evaluation V2</h1><p>{html.escape(str(summary.get('recommendation') or ''))}</p>
<table><thead><tr><th>case_id</th><th>category</th><th>score</th><th>gate</th></tr></thead><tbody>{rows}</tbody></table>
<h2>Summary JSON</h2><pre>{summary_json}</pre><h2>Markdown report</h2><pre>{html.escape(report_text)}</pre></html>"""
    (output_dir / "report.html").write_text(document, encoding="utf-8")


def _judge_config_from_args(args: argparse.Namespace) -> JudgeConfig:
    base_url = str(args.judge_base_url or "").strip()
    token = str(args.judge_token or "").strip()
    model = str(args.judge_model or "").strip()
    if not base_url or not token or not model:
        raise EvalRunnerError("judge config is required: --judge-base-url, --judge-token, --judge-model", exit_code=2)
    return JudgeConfig(
        base_url=base_url,
        token=token,
        model=model,
        timeout_seconds=max(1, int(args.judge_timeout_seconds or 120)),
        max_tokens=max(1, int(args.judge_max_tokens or 4096)),
    )


def render_report(summary: dict[str, Any], results: list[dict[str, Any]]) -> str:
    lines = [
        "# DataAgent 在线评测报告",
        "",
        f"- 数据集: `{summary.get('dataset_path', '')}`",
        f"- Agent: `{summary.get('agent_id', '')}`",
        f"- 用例数: {summary.get('total_cases', 0)}",
        f"- 结论: {summary.get('recommendation', '')}",
        "",
    ]
    if summary.get("dry_run"):
        lines.extend(
            [
                "## Dry Run",
                "",
                f"- case_id 唯一: {summary.get('unique_case_ids')}",
                f"- 评分总分有效: {summary.get('scoring_total_valid')}",
                f"- 数据集有效: {summary.get('dataset_valid')}",
                "",
            ]
        )
        return "\n".join(lines)
    metrics = summary.get("metrics") or {}
    def ratio_text(name: str) -> str:
        metric = metrics.get(name) if isinstance(metrics.get(name), dict) else {}
        value = metric.get("value")
        shown = "N/A" if value is None else f"{float(value):.2%}"
        return f"{shown} ({metric.get('numerator', 0)}/{metric.get('denominator', 0)})"
    lines.extend(
        [
            "## 核心指标",
            "",
            "| 指标 | 结果 |",
            "|---|---:|",
            f"| 完成率 | {ratio_text('completion_rate')} |",
            f"| 业务断言准确率 | {ratio_text('business_accuracy')} |",
            f"| 有效通过率 | {ratio_text('effective_pass_rate')} |",
            f"| 结果一致性 | {ratio_text('result_consistency_rate')} |",
            f"| 时间口径准确率 | {ratio_text('time_accuracy')} |",
            f"| 参考 SQL 数据准确率 | {ratio_text('data_accuracy')} |",
            f"| 幻觉率 | {float(metrics.get('hallucination_rate') or 0):.2%} |",
            "",
            "## 用例明细",
            "",
            "| case_id | 类别 | 分数 | 通过 | 失败归因 |",
            "|---|---|---:|---|---|",
        ]
    )
    for item in results:
        judge = item.get("judge") or {}
        attribution = ", ".join(judge.get("failure_attribution") or [])
        if item.get("errors"):
            attribution = attribution or ", ".join(error.get("code", "") for error in item.get("errors") or [])
        lines.append(
            f"| {item.get('case_id')} | {item.get('category')} | {float(judge.get('score') or 0):.2f} | "
            f"{'是' if item.get('case_passed') else '否'} | {attribution} |"
        )
    lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.concurrency < 1:
        print("--concurrency must be >= 1", file=sys.stderr)
        return 2
    if not str(args.dataset or "").strip():
        print("--dataset is required and must point to the private evaluation JSONL file", file=sys.stderr)
        return 2
    if not args.dry_run and not str(args.agent_id or "").strip():
        print("--agent-id is required for non-dry-run evaluation", file=sys.stderr)
        return 2
    root = _repo_or_package_root()
    dataset_path = Path(args.dataset)
    if not dataset_path.is_absolute():
        dataset_path = root / dataset_path
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = root / output_dir

    try:
        cases, dataset_stats = load_dataset(dataset_path, args.case_ids)
        dataset_stats.update({
            "run_label": str(args.run_label or "") or _timestamp(),
            "environment_label": str(args.environment_label or "local"),
            "agent_snapshot_hash": _snapshot_hash(str(args.agent_snapshot_path or "")),
            "agent_id": str(args.agent_id or "").strip(),
            "model": str(args.model or ""),
            "judge_model": str(args.judge_model or ""),
            "concurrency": int(args.concurrency),
        })
        _write_dataset_snapshot(output_dir, cases)
        if args.dry_run:
            summary = build_summary([], dataset_stats, dry_run=True)
            write_outputs(output_dir, [], summary)
            print(f"eval outputs written to: {output_dir}")
            return 0

        base_url = str(args.base_url or "").rstrip("/")
        judge_config = _judge_config_from_args(args)
        preflight_payload = preflight(base_url, str(args.auth_token or ""))
        dataset_stats["preflight"] = preflight_payload
        dataset_stats["auth_enabled"] = bool((preflight_payload.get("auth") or {}).get("auth_enabled"))
        try:
            results, opik_metadata = _run_opik_experiment(base_url, cases, args, judge_config, dataset_stats)
            dataset_stats.update(opik_metadata)
        except InfrastructureAbort as exc:
            results = exc.partial_results
            summary = build_summary(results, dataset_stats) if results else build_summary([], dataset_stats, dry_run=True)
            summary.update({
                "dry_run": False,
                "run_status": "infra_failed",
                "passed": False,
                "recommendation": "基础设施失败",
                "infrastructure_error": str(exc),
                "infrastructure_details": exc.details or None,
            })
            write_outputs(output_dir, results, summary)
            print(str(exc), file=sys.stderr)
            return 2
        summary = build_summary(results, dataset_stats)
        write_outputs(output_dir, results, summary)
        print(f"eval outputs written to: {output_dir}")
        return 0 if summary.get("passed") else 1
    except EvalRunnerError as exc:
        if output_dir:
            failure_summary = {
                "evaluation_engine": EVALUATION_ENGINE, "engine_version": ENGINE_VERSION,
                "metric_semantics_version": METRIC_SEMANTICS_VERSION, "judge_prompt_version": JUDGE_PROMPT_VERSION,
                "run_status": "infra_failed", "dry_run": False, "passed": False,
                "recommendation": "基础设施失败", "infrastructure_error": str(exc),
            }
            write_outputs(output_dir, [], failure_summary)
        print(str(exc), file=sys.stderr)
        return exc.exit_code


if __name__ == "__main__":
    sys.exit(main())
