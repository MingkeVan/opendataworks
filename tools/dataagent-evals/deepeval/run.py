#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import itertools
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any

# Keep DeepEval fully offline for intranet deployments. These flags must be set
# before importing deepeval so the import never triggers telemetry, update
# checks, or Confident AI cloud coupling. The runner drives the judge metric
# itself and does not depend on any deepeval.com / Confident AI service.
#
# The telemetry opt-out is FORCED rather than set via setdefault: DeepEval keeps
# telemetry ON for any falsy/empty value ("", "0", "false", "no"), so a stray
# value already present in the container / CI / compose environment would
# silently reopen the Sentry, PostHog and anonymous-IP cloud connections. On an
# intranet those outbound requests are dropped and block until socket timeout,
# which is exactly the "hangs after every case has finished" symptom. This tool
# is offline by contract, so it must not be re-enabled by the ambient
# environment. The progress bar is cosmetic and stays overridable.
os.environ["DEEPEVAL_TELEMETRY_OPT_OUT"] = "YES"
os.environ["DEEPEVAL_UPDATE_WARNING_OPT_OUT"] = "YES"
os.environ.setdefault("DEEPEVAL_DISABLE_PROGRESS_BAR", "YES")

try:
    from deepeval import evaluate as deepeval_evaluate
except Exception:  # pragma: no cover - exercised in environments without deepeval
    deepeval_evaluate = None

try:
    from deepeval.metrics import BaseMetric
except Exception:  # pragma: no cover
    BaseMetric = object  # type: ignore[assignment]

try:
    from deepeval.test_case import LLMTestCase
except Exception:  # pragma: no cover
    LLMTestCase = None  # type: ignore[assignment]


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
GATES = {
    "average_score": 8.0,
    "intent_accuracy": 0.90,
    "ontology_accuracy": 0.90,
    "sql_tool_accuracy": 0.85,
    "result_consistency_rate": 0.90,
    "data_accuracy": 0.90,
    "reasoning_average": 1.6,
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
DIMENSION_MAX = {"intent": 1.0, "ontology_entity": 1.0, "relation_scope": 1.0, "sql_or_tool_call": 2.0, "result_consistency": 2.0, "reasoning": 2.0, "answer_quality": 1.0}
EVALUATION_ENGINE = "deepeval"
ENGINE_VERSION = "2.0.0"
METRIC_SEMANTICS_VERSION = "2.2"
JUDGE_PROMPT_VERSION = "dataagent-v2-2026-07-23"
_DATAAGENT_AUTH_TOKEN = ""


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


@dataclass(frozen=True)
class JudgeConfig:
    base_url: str
    token: str
    model: str
    timeout_seconds: int = 120
    max_tokens: int = 4096


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
    return root / "reports" / "dataagent-evals" / f"deepeval-{_timestamp()}"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    root = _repo_or_package_root()
    parser = argparse.ArgumentParser(description="Run DataAgent evaluations with DeepEval.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8900", help="DataAgent backend base URL.")
    parser.add_argument(
        "--dataset",
        default=os.environ.get("DATAAGENT_EVAL_DATASET", ""),
        help="Required evaluation V2 JSONL dataset path.",
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
    parser.add_argument("--judge-timeout-seconds", type=int, default=int(os.environ.get("DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS", "120")), help="Judge request timeout.")
    parser.add_argument("--judge-max-tokens", type=int, default=int(os.environ.get("DATAAGENT_EVAL_JUDGE_MAX_TOKENS", "4096")), help="Judge response max tokens.")
    parser.add_argument("--dry-run", action="store_true", help="Validate dataset and output directory without service calls.")
    parser.add_argument("--environment-label", default=os.environ.get("DATAAGENT_EVAL_ENVIRONMENT_LABEL", "local"))
    parser.add_argument("--run-label", default=os.environ.get("DATAAGENT_EVAL_RUN_LABEL", ""))
    parser.add_argument("--history-root", default=os.environ.get("DATAAGENT_EVAL_HISTORY_ROOT", ""))
    parser.add_argument("--agent-snapshot-path", default=os.environ.get("DATAAGENT_EVAL_AGENT_SNAPSHOT_PATH", ""))
    parser.add_argument("--auth-token", default=os.environ.get("DATAAGENT_EVAL_AUTH_TOKEN", ""), help=argparse.SUPPRESS)
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
        "engine": "deepeval",
        "dataset_path": str(path),
        "dataset_id": path.stem,
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


def http_json(method: str, url: str, payload: dict[str, Any] | None = None, *, timeout: int = 30, headers: dict[str, str] | None = None) -> dict[str, Any]:
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
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise EvalRunnerError(f"HTTP {exc.code} {url}: {body}", exit_code=2) from exc
    except urllib.error.URLError as exc:
        raise EvalRunnerError(f"request failed {url}: {exc}", exit_code=2) from exc
    except json.JSONDecodeError as exc:
        raise EvalRunnerError(f"invalid JSON response from {url}: {exc}", exit_code=2) from exc


def _dataagent_headers() -> dict[str, str]:
    headers = {"X-ODW-Client": "dataagent"}
    if _DATAAGENT_AUTH_TOKEN:
        headers["Authorization"] = f"Bearer {_DATAAGENT_AUTH_TOKEN}"
    return headers


def dataagent_http_json(method: str, url: str, payload: dict[str, Any] | None = None, *, timeout: int = 30) -> dict[str, Any]:
    try:
        return http_json(method, url, payload=payload, timeout=timeout, headers=_dataagent_headers())
    except EvalRunnerError as exc:
        if re.search(r"HTTP (?:401|403)\b", str(exc)):
            raise InfrastructureAbort(f"dataagent_auth_failed: {exc}") from exc
        raise


def preflight(base_url: str, auth_token: str = "") -> dict[str, Any]:
    global _DATAAGENT_AUTH_TOKEN
    _DATAAGENT_AUTH_TOKEN = str(auth_token or "").strip()
    auth_config = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/auth/config", timeout=15)
    health = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/health", timeout=15)
    runtime_config = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/runtime-config", timeout=15)
    auth_enabled = bool(auth_config.get("auth_enabled") or auth_config.get("enabled"))
    identity = None
    if auth_enabled:
        if not _DATAAGENT_AUTH_TOKEN:
            raise InfrastructureAbort("dataagent_auth_token_missing: DATAAGENT_EVAL_AUTH_TOKEN is required")
        identity = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/auth/me", timeout=15)
        roles = identity.get("roles") if isinstance(identity.get("roles"), list) else []
        if not (bool(identity.get("is_admin")) or "admin" in {str(role).lower() for role in roles}):
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
            tool_input = block.get("input")
            skill_name = ""
            if isinstance(tool_input, dict):
                skill_name = str(
                    tool_input.get("skill")
                    or tool_input.get("skill_name")
                    or tool_input.get("name")
                    or ""
                ).strip()
            if skill_name:
                names.append(f"Skill:{skill_name}")
        if text == "Bash":
            command_text = "\n".join(_flatten_strings(block.get("input")))
            for script_name in re.findall(r"(?<![A-Za-z0-9_.-])([A-Za-z0-9_.-]+\.py)\b", command_text):
                names.append(f"Bash:{script_name}")
    return _dedupe(names)


def _strip_leading_sql_comments(text: str) -> str:
    value = str(text or "")
    while True:
        stripped = value.lstrip()
        if stripped.startswith("--"):
            newline = stripped.find("\n")
            value = "" if newline < 0 else stripped[newline + 1:]
            continue
        if stripped.startswith("/*"):
            end = stripped.find("*/", 2)
            value = "" if end < 0 else stripped[end + 2:]
            continue
        return stripped


def _looks_like_sql(text: str) -> bool:
    return bool(re.match(r"(?is)^(?:select|with)\b", _strip_leading_sql_comments(text)))


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


def _is_successful_sql_execution(block: dict[str, Any]) -> bool:
    if not isinstance(block, dict) or block.get("type") != "tool_use" or bool(block.get("is_error")):
        return False
    name = str(block.get("tool_name") or "")
    inputs = "\n".join(_flatten_strings(block.get("input")))
    return (name in {"run_sql", "mcp__portal__portal_query_readonly"} or (name == "Bash" and "run_sql.py" in inputs)) and block.get("output") not in (None, "")


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


def _summarize_query_evidence(blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    for block in blocks:
        if not _is_successful_sql_execution(block):
            continue
        sqls = _dedupe(
            _collect_structured_sql(block.get("input"))
            + _collect_structured_sql(block.get("output"))
        )
        rows = _query_rows_from_value(block.get("output"))
        structured = next(
            (
                item
                for item in _iter_structured_evidence(block.get("output"))
                if isinstance(item, dict) and isinstance(item.get("rows"), list)
            ),
            {},
        )
        columns = list(structured.get("columns") or [])
        if not columns and rows:
            columns = _dedupe([str(key) for row in rows for key in row])
        evidence.append(
            {
                "tool_name": str(block.get("tool_name") or ""),
                "sql": "\n".join(sqls),
                "row_count": len(rows) if isinstance(rows, list) else structured.get("row_count"),
                "columns": columns,
                "result_state": structured.get("result_state") or ("success" if rows is not None else None),
                "row_preview": rows[:3] if isinstance(rows, list) else [],
            }
        )
    return evidence


def _collect_usage(task: dict[str, Any], message: dict[str, Any]) -> dict[str, Any]:
    usage: dict[str, Any] = {}
    if isinstance(task.get("usage"), dict):
        usage.update(task["usage"])
    if isinstance(message.get("usage"), dict):
        usage.update(message["usage"])
    return usage


def _aggregate_usage(tasks: list[dict[str, Any]], messages: list[dict[str, Any]]) -> dict[str, Any]:
    """Sum usage once per task across a multi-turn/recovered evaluation case."""
    task_usage: dict[str, dict[str, Any]] = {}
    unscoped_usage: list[dict[str, Any]] = []
    for task in tasks:
        usage = task.get("usage")
        if not isinstance(usage, dict):
            continue
        task_id = str(task.get("task_id") or "").strip()
        if task_id:
            task_usage[task_id] = dict(usage)
        else:
            unscoped_usage.append(dict(usage))
    # A persisted assistant message is another projection of the same task, so
    # merge it into that task's usage instead of summing it a second time.
    for message in messages:
        usage = message.get("usage")
        if not isinstance(usage, dict):
            continue
        task_id = str(message.get("task_id") or "").strip()
        if task_id:
            task_usage.setdefault(task_id, {}).update(usage)
        else:
            unscoped_usage.append(dict(usage))

    totals: dict[str, Any] = {}
    for usage in [*task_usage.values(), *unscoped_usage]:
        for key, value in usage.items():
            if isinstance(value, bool):
                continue
            if isinstance(value, (int, float)):
                totals[key] = totals.get(key, 0) + value
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
    if isinstance(fragment, dict):
        alternatives = fragment.get("any_of")
        if isinstance(alternatives, list) and alternatives:
            return any(_sql_fragment_matches(item, sql_text, expected_sql) for item in alternatives)
        requirements = fragment.get("all_of")
        if isinstance(requirements, list) and requirements:
            return all(_sql_fragment_matches(item, sql_text, expected_sql) for item in requirements)
        return False
    if isinstance(fragment, list):
        return bool(fragment) and any(
            _sql_fragment_matches(item, sql_text, expected_sql) for item in fragment
        )
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


def _is_time_sql_fragment(fragment: Any, expected_time: dict[str, Any]) -> bool:
    normalized = _normalize_sql_match_text(fragment)
    field = str(expected_time.get("field") or "").strip().lower()
    if field and re.search(rf"(?<![A-Za-z0-9_]){re.escape(field)}(?![A-Za-z0-9_])", normalized):
        return True
    return bool(
        re.search(
            r"\b(?:current_date|current_timestamp|interval|date_format|date_trunc|"
            r"date_add|date_sub|last_day|timestampadd|timestampdiff)\b",
            normalized,
        )
    )


def _required_tool_step_satisfied(step: Any, tool_names: list[str], sql_outputs: list[str]) -> bool:
    normalized = str(step or "").strip().lower()
    if not normalized:
        return True
    if normalized == "query_execute":
        return bool(sql_outputs)
    tokens = [token for token in re.split(r"[^a-z0-9]+", normalized) if token]
    return any(
        tokens and all(token in str(name).lower() for token in tokens)
        for name in tool_names
    )


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
    shanghai_today = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
    range_present = False
    expected_bounds: list[str] = []
    if kind == "calendar_month":
        start = _add_months(shanghai_today.replace(day=1), int(range_spec.get("offset") or 0))
        end = _add_months(start, 1)
        expected_bounds = [start.isoformat(), end.isoformat()]
        range_present = all(bound in normalized_sql for bound in expected_bounds)
        if not range_present and "date_format(current_date" in normalized_sql:
            range_present = True
    elif kind in {"rolling_days", "rolling_calendar_days"}:
        days = int(range_spec.get("days") or range_spec.get("value") or 0)
        if days > 0:
            start = shanghai_today - dt.timedelta(days=days - 1)
            exclusive_end = shanghai_today + dt.timedelta(days=1)
            expected_bounds = [start.isoformat(), shanghai_today.isoformat()]
            range_present = (
                start.isoformat() in normalized_sql
                and (
                    shanghai_today.isoformat() in normalized_sql
                    or exclusive_end.isoformat() in normalized_sql
                )
            )
            if not range_present and "current_date" in normalized_sql:
                range_present = bool(re.search(rf"interval\s+(?:{days}|{max(0, days - 1)})\s+day", normalized_sql))
    elif kind == "calendar_month_comparison":
        current_start = shanghai_today.replace(day=1)
        previous_start = _add_months(current_start, -1)
        previous_end = current_start - dt.timedelta(days=1)
        expected_bounds = [
            previous_start.isoformat(),
            previous_end.isoformat(),
            current_start.isoformat(),
            shanghai_today.isoformat(),
        ]
        range_present = all(bound in normalized_sql for bound in expected_bounds)
        if not range_present:
            range_present = (
                "current_date" in normalized_sql
                and "interval 1 month" in normalized_sql
                and (
                    "date_format" in normalized_sql
                    or "last_day" in normalized_sql
                    or "date_trunc" in normalized_sql
                )
            )
    else:
        range_present = False
    passed = field_present and range_present
    return {
        "applicable": True,
        "passed": passed,
        "field": field,
        "field_present": field_present,
        "range_kind": kind,
        "expected_bounds": expected_bounds,
        "reason": None if passed else ("time_field_missing" if not field_present else "time_range_mismatch"),
    }


def _best_time_rule_check(expected_time: dict[str, Any], sql_outputs: list[str]) -> dict[str, Any]:
    if not bool(expected_time.get("required")):
        return {"applicable": False, "passed": True, "reason": None}
    checks = [_time_rule_check(expected_time, sql) for sql in sql_outputs]
    if not checks:
        return _time_rule_check(expected_time, "")
    passed = next((check for check in checks if bool(check.get("passed"))), None)
    if passed is not None:
        return passed
    return max(
        checks,
        key=lambda check: (
            bool(check.get("field_present")),
            check.get("reason") != "time_field_missing",
        ),
    )


def _tool_output_text(blocks: list[dict[str, Any]]) -> str:
    values: list[str] = []
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use":
            continue
        output = block.get("output")
        if isinstance(output, str):
            values.append(output)
        elif output is not None:
            values.extend(_flatten_strings(output))
    return "\n".join(values)


def _is_sql_tool_block(block: dict[str, Any]) -> bool:
    tool_name = str(block.get("tool_name") or "").lower()
    input_text = "\n".join(_flatten_strings(block.get("input"))) if block.get("input") is not None else ""
    input_text = input_text.lower()
    return (
        "run_sql" in tool_name
        or "validate_sql" in tool_name
        or "run_sql.py" in input_text
        or "validate_sql.py" in input_text
    )


def _sql_evidence_text(blocks: list[dict[str, Any]], sql_outputs: list[str]) -> str:
    values = list(sql_outputs)
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") != "tool_use" or not _is_sql_tool_block(block):
            continue
        output = block.get("output")
        if isinstance(output, str):
            values.append(output)
        elif output is not None:
            values.extend(_flatten_strings(output))
    return "\n".join(values)


def _assessment_evidence(*, final_answer: str, blocks: list[dict[str, Any]], sql_outputs: list[str]) -> dict[str, str]:
    return {
        "final_answer_user_visible": str(final_answer or ""),
        "tool_evidence": _tool_output_text(blocks),
        "sql_evidence": _sql_evidence_text(blocks, sql_outputs),
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
    answer_text = str(answer or "")

    def values_are_present(values: list[Any]) -> bool:
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

    if all(all(field in row for field in fields) for row in dict_rows):
        return values_are_present([row[field] for row in dict_rows for field in fields])
    common_columns = [
        column
        for column in (list(dict_rows[0]) if dict_rows else [])
        if all(column in row for row in dict_rows)
    ]
    for candidate_fields in itertools.combinations(common_columns, len(fields)):
        if values_are_present([row[field] for row in dict_rows for field in candidate_fields]):
            return True
    return False


def _auto_failure_attribution(
    evidence: dict[str, str],
    *,
    missing_sql_fragments: list[str],
    forbidden_hits: list[str],
    missing_tool_names: list[str],
) -> list[str]:
    failures: list[str] = []
    final_answer = evidence.get("final_answer_user_visible", "")
    tool_evidence = evidence.get("tool_evidence", "")
    sql_evidence = evidence.get("sql_evidence", "")
    answer_or_sql = "\n".join([final_answer, sql_evidence])
    if re.search(
        r"请(?:你|用户|后续)?.{0,40}执行.{0,20}SQL|仅供.{0,40}执行|"
        r"无法直接执行|未注入.{0,20}SQL|没有\s*SQL\s*执行|SQL.{0,20}尚未执行",
        final_answer,
        re.I | re.S,
    ):
        failures.append("sql_only")
    if re.search(r"\{(?:target_date|TARGET_DATE|start_date|START_DATE|end_date|END_DATE|database_name|DATABASE_NAME|database_schema|DATABASE_SCHEMA|table_name|TABLE_NAME|RULE_KEY)\}|占位符|TODO", answer_or_sql):
        failures.append("placeholder_leak")
    if re.search(r"超时|timed?\s*out|timeout(?:_error| error| occurred)", "\n".join([final_answer, tool_evidence]), re.I):
        failures.append("tool_timeout")
    if missing_sql_fragments:
        failures.append("missing_sql_fragment")
    if missing_tool_names:
        failures.append("missing_tool")
    if forbidden_hits:
        failures.append("forbidden_sql")
    return _dedupe(failures)


def auto_rule_check(case: dict[str, Any], *, final_answer: str, blocks: list[dict[str, Any]], sql_outputs: list[str], tool_names: list[str]) -> dict[str, Any]:
    evidence = _assessment_evidence(final_answer=final_answer, blocks=blocks, sql_outputs=sql_outputs)
    expected_sql = case.get("expected_sql") if isinstance(case.get("expected_sql"), dict) else {}
    expected_time = case.get("expected_time") if isinstance(case.get("expected_time"), dict) else {}
    expected_tools = case.get("expected_tools") if isinstance(case.get("expected_tools"), dict) else {}
    expected_result = case.get("expected_result") if isinstance(case.get("expected_result"), dict) else {}
    fragments = list(expected_sql.get("tables") or []) + list(expected_sql.get("fields") or []) + list(expected_sql.get("predicates") or []) + list(expected_sql.get("aggregations") or [])
    preferred_width = len(expected_result.get("required_columns") or expected_result.get("answer_result_fields") or [])
    relevant_query = _select_relevant_query_evidence(blocks, fragments, expected_sql=expected_sql, preferred_width=preferred_width)
    assessment_text = "\n".join(sql_outputs)
    evidence["sql_evidence"] = assessment_text
    time_check = (
        _best_time_rule_check(expected_time, sql_outputs)
        if bool(expected_sql.get("execution_required")) or bool(sql_outputs)
        else {"applicable": False, "passed": True, "reason": "judge_only"}
    )
    missing_sql_fragments = [
        fragment for fragment in fragments
        if str(fragment or "").strip()
        and not _sql_fragment_matches(fragment, assessment_text, expected_sql)
        and not (
            bool(time_check.get("passed"))
            and fragment in list(expected_sql.get("predicates") or [])
            and _is_time_sql_fragment(fragment, expected_time)
        )
    ]
    forbidden_hits: list[str] = []
    for pattern in expected_sql.get("forbidden_patterns") or []:
        try:
            if re.search(str(pattern), evidence["sql_evidence"], re.I):
                forbidden_hits.append(str(pattern))
        except re.error:
            if str(pattern) in evidence["sql_evidence"]:
                forbidden_hits.append(str(pattern))
    missing_tool_names: list[str] = []
    required_steps = list(expected_tools.get("required_steps") or [])
    for step in required_steps:
        if not _required_tool_step_satisfied(step, tool_names, sql_outputs):
            missing_tool_names.append(str(step))
    if bool(expected_sql.get("execution_required")) and not sql_outputs and "query_execute" not in missing_tool_names:
        missing_tool_names.append("query_execute")
    for group in expected_tools.get("allowed_alternative_groups") or []:
        names = [str(name) for name in group] if isinstance(group, list) else []
        if names and not any(name in tool_names for name in names):
            missing_tool_names.append("|".join(names))
    tool_output_text = str((relevant_query or {}).get("output_text") or "")
    relevant_rows = (relevant_query or {}).get("rows")
    empty_result = isinstance(relevant_rows, list) and len(relevant_rows) == 0
    unexpected_empty = empty_result and not bool(expected_result.get("allow_empty"))
    required_answer_fields = [str(item) for item in expected_result.get("answer_result_fields") or []]
    result_applicable = bool(required_answer_fields) and bool(sql_outputs) and bool(tool_output_text.strip())
    result_passed = (
        _answer_references_result_values(relevant_rows, required_answer_fields, final_answer)
        if result_applicable
        else True
    )
    triggered_veto_rules: list[str] = []
    failure_attribution = _auto_failure_attribution(
        evidence,
        missing_sql_fragments=missing_sql_fragments,
        forbidden_hits=forbidden_hits,
        missing_tool_names=missing_tool_names,
    )
    if bool(expected_result.get("allow_empty")):
        failure_attribution = [item for item in failure_attribution if item != "empty_result"]
    if bool(time_check.get("applicable")) and not bool(time_check.get("passed")):
        failure_attribution.append(str(time_check.get("reason") or "time_dimension_mismatch"))
    if unexpected_empty:
        failure_attribution.append("empty_result")
        failure_attribution.append("unexpected_empty_result")
    if not result_passed:
        failure_attribution.append("result_answer_inconsistent")
    return {
        "passed": not (
            missing_sql_fragments
            or forbidden_hits
            or missing_tool_names
            or unexpected_empty
            or (bool(time_check.get("applicable")) and not bool(time_check.get("passed")))
        ) and result_passed,
        "missing_sql_fragments": missing_sql_fragments,
        "forbidden_sql_patterns": forbidden_hits,
        "missing_tool_names": missing_tool_names,
        "triggered_veto_rules": triggered_veto_rules,
        "failure_attribution": _dedupe(failure_attribution),
        "hard_gates": {
            "required_tool_execution": not missing_tool_names,
            "sql_execution_and_口径": (not bool(expected_sql.get("execution_required")) or bool(sql_outputs)) and not missing_sql_fragments and not forbidden_hits,
            "non_expected_empty_result": not unexpected_empty,
            "result_consistency": result_passed,
            "time_dimension": bool(time_check.get("passed")),
        },
        "result_consistency": {"applicable": result_applicable, "passed": result_passed},
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
        {"title": f"DeepEval {case['case_id']}", "agent_id": agent_id},
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
        topic = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/topics/{urllib.parse.quote(topic_id)}", timeout=30)
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
    task_chain: list[dict[str, Any]] | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Poll task status until terminal, following recovered/replacement tasks.

    Run evidence is read afterwards from the Chat V2 server-projected ``blocks``
    in ``GET /topics/{id}/messages`` (the same projection the Chat V2 history uses).
    """
    deadline = time.time() + max(1, timeout_seconds)
    current_task_id = task_id
    seen_task_ids = {task_id}
    errors: list[dict[str, Any]] = []
    last_task: dict[str, Any] = {}
    last_poll_error = ""
    def record_task(item: dict[str, Any]) -> None:
        if task_chain is None:
            return
        item_id = str(item.get("task_id") or current_task_id).strip()
        snapshot = dict(item)
        if item_id and not snapshot.get("task_id"):
            snapshot["task_id"] = item_id
        for index, existing in enumerate(task_chain):
            if str(existing.get("task_id") or "").strip() == item_id:
                task_chain[index] = snapshot
                return
        task_chain.append(snapshot)

    while time.time() < deadline:
        try:
            last_task = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/tasks/{urllib.parse.quote(current_task_id)}", timeout=30)
            last_poll_error = ""
        except InfrastructureAbort:
            raise
        except EvalRunnerError as exc:
            last_poll_error = str(exc)
            time.sleep(1.0)
            continue

        record_task(last_task)

        status = str(last_task.get("task_status") or "").lower()
        if status in TERMINAL_STATUSES:
            if _is_recovered_task(last_task):
                try:
                    recovered_task_id = _resolve_recovered_task_id(base_url, topic_id, last_task)
                except InfrastructureAbort:
                    raise
                except EvalRunnerError as exc:
                    raise InfrastructureAbort(f"dataagent_transport_failed: {exc}") from exc
                if recovered_task_id and recovered_task_id not in seen_task_ids:
                    current_task_id = recovered_task_id
                    seen_task_ids.add(recovered_task_id)
                    continue
            return last_task, errors
        time.sleep(0.2)
    if last_poll_error:
        raise InfrastructureAbort(f"dataagent_transport_failed: {last_poll_error}")
    errors.append({"code": "timeout", "message": f"task did not finish within {timeout_seconds}s"})
    last_task = dict(last_task or {"task_id": current_task_id})
    last_task["task_status"] = str(last_task.get("task_status") or "timeout")
    record_task(last_task)
    return last_task, errors


def _fetch_sdk_events(base_url: str, task_id: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    after_id = 0
    while True:
        page = dataagent_http_json("GET", f"{base_url}/api/v1/nl2sql/tasks/{urllib.parse.quote(task_id)}/sdk-events?after_id={after_id}&limit=500", timeout=30)
        batch = [item for item in page.get("records") or [] if isinstance(item, dict)]
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
    return {
        "agent_turn_count": len(turns),
        "tool_error_count": sum(1 for item in records if item.get("record_type") == "tool_result" and bool((item.get("data") or {}).get("is_error"))),
        "recovery_count": sum(1 for item in records if "recover" in (str(item.get("record_type") or "") + str(item.get("event_type") or "")).lower()),
    }


def _seconds_between(left: Any, right: Any) -> float | None:
    try:
        start = dt.datetime.fromisoformat(str(left).replace("Z", "+00:00"))
        end = dt.datetime.fromisoformat(str(right).replace("Z", "+00:00"))
        if start.tzinfo is None: start = start.replace(tzinfo=dt.timezone.utc)
        if end.tzinfo is None: end = end.replace(tzinfo=dt.timezone.utc)
        return round(max(0.0, (end - start).total_seconds()), 3)
    except (TypeError, ValueError):
        return None


def _aggregate_task_timing(tasks: list[dict[str, Any]]) -> dict[str, float | None]:
    queue_values = [
        value for value in (_seconds_between(task.get("created_at"), task.get("started_at")) for task in tasks)
        if value is not None
    ]
    execution_values = [
        value for value in (_seconds_between(task.get("started_at"), task.get("finished_at")) for task in tasks)
        if value is not None
    ]
    return {
        "queue_wait_seconds": round(sum(queue_values), 3) if queue_values else None,
        "execution_seconds": round(sum(execution_values), 3) if execution_values else None,
    }


def _query_rows_from_value(value: Any) -> list[dict[str, Any]] | None:
    for item in _iter_structured_evidence(value):
        if isinstance(item, dict) and isinstance(item.get("rows"), list) and all(isinstance(row, dict) for row in item.get("rows") or []):
            return item.get("rows") or []
    return None


def _actual_query_row_sets(blocks: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    row_sets: list[list[dict[str, Any]]] = []
    for block in blocks:
        if _is_successful_sql_execution(block):
            rows = _query_rows_from_value(block.get("output"))
            if rows is not None:
                row_sets.append(rows)
    return row_sets


def _actual_query_evidence(blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    for position, block in enumerate(blocks):
        if not _is_successful_sql_execution(block):
            continue
        rows = _query_rows_from_value(block.get("output"))
        if rows is None:
            continue
        sqls = _dedupe(
            _collect_structured_sql(block.get("input"))
            + _collect_structured_sql(block.get("output"))
        )
        evidence.append(
            {
                "position": position,
                "sql_text": "\n".join(sqls),
                "rows": rows,
            }
        )
    return evidence


def _normalized_reference_value(value: Any, *, tolerance: float = 0.0) -> Any:
    if isinstance(value, bool) or value is None:
        return value
    if isinstance(value, (int, float)):
        numeric = float(value)
        if tolerance > 0:
            digits = max(0, min(12, int(abs(__import__("math").log10(tolerance))) * -1)) if tolerance < 1 else 0
            numeric = round(numeric, digits)
        return numeric
    return str(value)


def _canonical_rows(rows: list[dict[str, Any]], *, tolerance: float = 0.0) -> list[str]:
    normalized: list[str] = []
    for row in rows:
        clean = {
            str(key): _normalized_reference_value(value, tolerance=tolerance)
            for key, value in row.items()
        }
        normalized.append(json.dumps(clean, ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":")))
    return sorted(normalized)


def _canonical_value_rows(
    rows: list[dict[str, Any]],
    *,
    fields: list[str] | tuple[str, ...] | None = None,
    tolerance: float = 0.0,
) -> list[str]:
    normalized: list[str] = []
    for row in rows:
        values = [row.get(field) for field in fields] if fields else list(row.values())
        clean = sorted(
            json.dumps(
                _normalized_reference_value(value, tolerance=tolerance),
                ensure_ascii=False,
                sort_keys=True,
                default=str,
                separators=(",", ":"),
            )
            for value in values
        )
        normalized.append(json.dumps(clean, ensure_ascii=False, separators=(",", ":")))
    return sorted(normalized)


def _reference_scalar_is_zero(reference: dict[str, Any]) -> bool:
    rows = reference.get("rows") if isinstance(reference.get("rows"), list) else []
    if len(rows) != 1 or not isinstance(rows[0], dict) or len(rows[0]) != 1:
        return False
    value = next(iter(rows[0].values()), None)
    try:
        return float(value) == 0
    except (TypeError, ValueError):
        return False


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
    expected = case.get("expected_result") if isinstance(case.get("expected_result"), dict) else {}
    reference = expected.get("reference_query") if isinstance(expected.get("reference_query"), dict) else None
    if not reference:
        return {"applicable": False, "passed": None}
    sql = str(reference.get("sql") or "").strip()
    if not re.match(r"(?is)^\s*(?:select|with)\b", sql) or re.search(r"(?is)\b(?:insert|update|delete|drop|alter|truncate|create|grant|revoke)\b", sql):
        raise _reference_sql_failure(
            case,
            reference,
            sql,
            error_code="reference_sql_invalid",
            cause="only read-only SELECT/CTE SQL is allowed",
        )
    timeout = int(reference.get("timeout_seconds") or 60)
    try:
        response = dataagent_http_json("POST", f"{base_url}/api/v1/nl2sql/query/execute", {
            "sql": sql, "database": str(reference.get("database") or ""), "engine": reference.get("engine"),
            "limit": int(reference.get("limit") or 1000), "timeout_seconds": timeout, "topic_id": topic_id,
        }, timeout=timeout + 15)
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
    return {"applicable": True, "passed": None, "rows": rows, "row_count": len(rows)}


def _compare_reference(
    reference: dict[str, Any],
    actual_row_sets: list[list[dict[str, Any]]],
    case: dict[str, Any],
    *,
    actual_query_evidence: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    if not reference.get("applicable"):
        return reference
    expected_result = case.get("expected_result") if isinstance(case.get("expected_result"), dict) else {}
    query = expected_result.get("reference_query") if isinstance(expected_result.get("reference_query"), dict) else {}
    tolerance = float(query.get("numeric_tolerance") or 0.0)
    mode = str(query.get("comparison_mode") or "unordered_rows")
    expected_rows = reference.get("rows") if isinstance(reference.get("rows"), list) else []
    comparison_fields = [str(field) for field in query.get("comparison_fields") or [] if str(field).strip()]
    expected_columns = _dedupe([str(key) for row in expected_rows for key in row])
    required_fragments = (
        list((case.get("expected_sql") or {}).get("tables") or [])
        + list((case.get("expected_sql") or {}).get("fields") or [])
        + list((case.get("expected_sql") or {}).get("predicates") or [])
        + list((case.get("expected_sql") or {}).get("aggregations") or [])
    )
    evidence = actual_query_evidence or [
        {"position": position, "sql_text": "", "rows": rows}
        for position, rows in enumerate(actual_row_sets)
    ]
    if actual_query_evidence and required_fragments:
        for item in evidence:
            sql_text = str(item.get("sql_text") or "")
            item["semantic_match_count"] = sum(
                1
                for fragment in required_fragments
                if _sql_fragment_matches(fragment, sql_text, case.get("expected_sql") or {})
            )
        evidence = sorted(
            evidence,
            key=lambda item: (
                int(item.get("semantic_match_count") or 0),
                int(item.get("position") or 0),
            ),
            reverse=True,
        )

    def compare(actual_rows: list[dict[str, Any]]) -> tuple[bool, bool, list[str], str | None]:
        actual_columns = _dedupe([str(key) for row in actual_rows for key in row])
        if mode == "scalar":
            comparable = len(expected_rows) <= 1 and len(actual_rows) <= 1 and (
                not expected_rows or len(expected_rows[0]) == 1
            )
            if not comparable:
                return False, False, actual_columns, "scalar_shape_mismatch"
            expected_value = next(iter(expected_rows[0].values()), None) if expected_rows else None
            actual_values = list(actual_rows[0].values()) if actual_rows else []
            if not actual_values:
                return True, _reference_scalar_is_zero(reference), actual_columns, None
            for actual_value in actual_values:
                try:
                    matched_value = abs(float(expected_value) - float(actual_value)) <= tolerance
                except (TypeError, ValueError):
                    matched_value = str(expected_value) == str(actual_value)
                if matched_value:
                    return True, True, actual_columns, None
            return True, False, actual_columns, None
        if len(expected_rows) != len(actual_rows):
            return False, False, actual_columns, "row_count_mismatch"
        if not expected_rows and not actual_rows:
            return True, True, actual_columns, None
        fields = comparison_fields
        if fields:
            if not all(all(field in row for field in fields) for row in expected_rows):
                return False, False, actual_columns, "comparison_fields_missing"
            expected_projected = [{field: row.get(field) for field in fields} for row in expected_rows]
            if all(all(field in row for field in fields) for row in actual_rows):
                actual_projected = [{field: row.get(field) for field in fields} for row in actual_rows]
                return (
                    True,
                    _canonical_rows(expected_projected, tolerance=tolerance)
                    == _canonical_rows(actual_projected, tolerance=tolerance),
                    actual_columns,
                    None,
                )
            if len(actual_columns) < len(fields) or not all(
                all(column in row for column in actual_columns) for row in actual_rows
            ):
                return False, False, actual_columns, "comparison_fields_missing"
            expected_values = _canonical_value_rows(expected_projected, fields=fields, tolerance=tolerance)
            comparable_projection = False
            for candidate_fields in itertools.combinations(actual_columns, len(fields)):
                comparable_projection = True
                if _canonical_value_rows(actual_rows, fields=candidate_fields, tolerance=tolerance) == expected_values:
                    return True, True, actual_columns, None
            return comparable_projection, False, actual_columns, (
                "value_mismatch" if comparable_projection else "comparison_fields_missing"
            )
        if mode == "unordered_values":
            if expected_columns and all(all(field in row for field in expected_columns) for row in actual_rows):
                actual_projected = [
                    {field: row.get(field) for field in expected_columns}
                    for row in actual_rows
                ]
                return (
                    True,
                    _canonical_rows(expected_rows, tolerance=tolerance)
                    == _canonical_rows(actual_projected, tolerance=tolerance),
                    actual_columns,
                    None,
                )
            expected_widths = {len(row) for row in expected_rows}
            actual_widths = {len(row) for row in actual_rows}
            if len(expected_widths) != 1 or expected_widths != actual_widths:
                return False, False, actual_columns, "column_shape_mismatch"
            normalize_values = lambda rows: sorted(
                json.dumps(
                    sorted(
                        (
                            json.dumps(
                                _normalized_reference_value(value, tolerance=tolerance),
                                ensure_ascii=False,
                                sort_keys=True,
                            )
                            for value in row.values()
                        )
                    ),
                    ensure_ascii=False,
                )
                for row in rows
            )
            return True, normalize_values(expected_rows) == normalize_values(actual_rows), actual_columns, None
        if set(expected_columns) != set(actual_columns):
            return False, False, actual_columns, "column_shape_mismatch"
        return (
            True,
            _canonical_rows(expected_rows, tolerance=tolerance)
            == _canonical_rows(actual_rows, tolerance=tolerance),
            actual_columns,
            None,
        )

    comparable_candidates: list[dict[str, Any]] = []
    matched: dict[str, Any] | None = None
    mismatch_reasons: list[str] = []
    for item in evidence:
        rows = item.get("rows") if isinstance(item.get("rows"), list) else []
        comparable, passed, actual_columns, reason = compare(rows)
        if comparable:
            candidate = {**item, "actual_columns": actual_columns}
            comparable_candidates.append(candidate)
            if passed:
                matched = candidate
                break
        elif reason:
            mismatch_reasons.append(reason)
    expected_split_fields = comparison_fields or expected_columns
    if matched is None and len(expected_rows) == 1 and len(expected_split_fields) > 1:
        expected_values = [
            _normalized_reference_value(expected_rows[0].get(field), tolerance=tolerance)
            for field in expected_split_fields
        ]
        scalar_values: list[Any] = []
        for item in evidence:
            rows = item.get("rows") if isinstance(item.get("rows"), list) else []
            if len(rows) == 1 and isinstance(rows[0], dict) and len(rows[0]) == 1:
                scalar_values.append(
                    _normalized_reference_value(next(iter(rows[0].values())), tolerance=tolerance)
                )
        expected_canonical = sorted(
            json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
            for value in expected_values
        )
        for candidate_values in itertools.combinations(scalar_values, len(expected_values)):
            candidate_canonical = sorted(
                json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
                for value in candidate_values
            )
            if candidate_canonical == expected_canonical:
                matched = {
                    "position": None,
                    "sql_text": "\n".join(str(item.get("sql_text") or "") for item in evidence),
                    "rows": [dict(zip(expected_split_fields, expected_values))],
                    "actual_columns": expected_split_fields,
                    "composed_from_split_queries": True,
                }
                comparable_candidates.append(matched)
                break
    selected = matched or (comparable_candidates[0] if comparable_candidates else None)
    comparable = bool(comparable_candidates)
    passed: bool | None = bool(matched) if comparable else None
    return {
        **reference,
        "passed": passed,
        "status": "matched" if matched else ("mismatched" if comparable else "not_comparable"),
        "comparable": comparable,
        "enforced": bool(query.get("enforce_case_gate")),
        "comparison_mode": mode,
        "comparison_fields": comparison_fields,
        "expected_columns": expected_columns,
        "actual_columns": list((selected or {}).get("actual_columns") or []),
        "actual_row_count": len((selected or {}).get("rows") or []) if selected else None,
        "candidate_result_count": len(evidence),
        "comparable_candidate_count": len(comparable_candidates),
        "mismatch_reason": None if matched else (
            "value_mismatch" if comparable else (_dedupe(mismatch_reasons)[0] if mismatch_reasons else "no_query_result")
        ),
    }


def _case_with_reference_empty_policy(case: dict[str, Any], reference: dict[str, Any]) -> dict[str, Any]:
    """Treat a successfully established empty truth set as an expected result."""
    if not reference.get("applicable") or (
        int(reference.get("row_count") or 0) != 0
        and not _reference_scalar_is_zero(reference)
    ):
        return case
    expected_result = case.get("expected_result") if isinstance(case.get("expected_result"), dict) else {}
    return {**case, "expected_result": {**expected_result, "allow_empty": True}}


def _apply_reference_gate(
    rule_check: dict[str, Any],
    reference_result: dict[str, Any],
) -> None:
    enforced_failure = (
        bool(reference_result.get("applicable"))
        and bool(reference_result.get("comparable"))
        and reference_result.get("passed") is False
        and bool(reference_result.get("enforced"))
    )
    if enforced_failure:
        rule_check["passed"] = False
        rule_check.setdefault("failure_attribution", []).append("enforced_reference_data_mismatch")
        rule_check.setdefault("hard_gates", {})["reference_data_accuracy"] = False
    elif reference_result.get("passed") is True and bool(reference_result.get("enforced")):
        rule_check.setdefault("hard_gates", {})["reference_data_accuracy"] = True


def run_case(base_url: str, case: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    e2e_started: float | None = None
    e2e_seconds: float | None = None
    errors: list[dict[str, Any]] = []
    topic_id = ""
    task_id = ""
    task: dict[str, Any] = {}
    message: dict[str, Any] = {}
    final_answer = ""
    conversation: list[dict[str, Any]] = []
    sdk_events: list[dict[str, Any]] = []
    task_ids: list[str] = []
    task_records: list[dict[str, Any]] = []
    turn_final_tasks: list[dict[str, Any]] = []
    assistant_messages: list[dict[str, Any]] = []
    reference_result: dict[str, Any] = {"applicable": False, "passed": None}
    try:
        topic_id = _create_topic(base_url, case, str(args.agent_id or "").strip())
        limits = case.get("limits") if isinstance(case.get("limits"), dict) else {}
        case_timeout = min(max(1, args.timeout_seconds), int(limits.get("max_wait_seconds") or args.timeout_seconds or 900))
        final_task_id = ""
        for turn in _case_turns(case):
            if e2e_started is None:
                # Topic creation and reference-SQL setup are evaluation setup,
                # not DataAgent end-to-end execution time.
                e2e_started = time.time()
            task_id = _submit_task(base_url, topic_id, case, args, turn)
            task_ids.append(task_id)
            task, poll_errors = _poll_task(
                base_url,
                task_id,
                case_timeout,
                topic_id=topic_id,
                task_chain=task_records,
            )
            errors.extend(poll_errors)
            turn_final_tasks.append(task)
            final_task_id = str(task.get("task_id") or task_id).strip() or task_id
            for task_record in task_records:
                record_id = str(task_record.get("task_id") or "").strip()
                if record_id and record_id not in task_ids:
                    task_ids.append(record_id)
            status = str(task.get("task_status") or "").lower()
            if status and status not in SUCCESS_STATUSES:
                errors.append({"code": status, "message": json.dumps(task.get("error") or {}, ensure_ascii=False)})
                break
        messages = dataagent_http_json(
            "GET",
            f"{base_url}/api/v1/nl2sql/topics/{urllib.parse.quote(topic_id)}/messages?page=1&page_size=200&order=asc",
            timeout=30,
        )
        e2e_seconds = round(time.time() - e2e_started, 3) if e2e_started is not None else None
        message = _final_assistant_message(messages, final_task_id)
        assistant_messages = [
            item for item in messages.get("items") or []
            if isinstance(item, dict) and str(item.get("sender_type") or "") == "assistant"
        ]
        conversation = _build_conversation_log(messages)
        final_answer = str(message.get("content") or "").strip()
        for completed_task_id in task_ids:
            for event in _fetch_sdk_events(base_url, completed_task_id):
                annotated = dict(event)
                annotated["_evaluation_task_id"] = completed_task_id
                sdk_events.append(annotated)
        # Do not let reference-query infrastructure prevent the actual Agent
        # task from being submitted and persisted in the platform history.
        reference_result = _execute_reference_query(base_url, case, topic_id)
    except InfrastructureAbort:
        raise
    except EvalRunnerError as exc:
        raise InfrastructureAbort(f"dataagent_transport_failed: {exc}") from exc

    blocks = [
        block
        for assistant_message in assistant_messages
        for block in (assistant_message.get("blocks") if isinstance(assistant_message.get("blocks"), list) else [])
        if isinstance(block, dict)
    ]
    tool_names = _collect_tool_names(blocks)
    sql_outputs = _extract_sql_outputs(blocks, final_answer)
    chart_outputs = _extract_chart_outputs(blocks)
    usage = _aggregate_usage(task_records, assistant_messages)
    sdk_counts = _sdk_metrics(sdk_events)
    sdk_counts["recovery_count"] = max(
        int(sdk_counts.get("recovery_count") or 0),
        sum(1 for task_record in task_records if _is_recovered_task(task_record)),
    )
    tool_call_count = len([block for block in blocks if isinstance(block, dict) and block.get("type") == "tool_use"])
    rule_case = _case_with_reference_empty_policy(case, reference_result)
    rule_check = auto_rule_check(rule_case, final_answer=final_answer, blocks=blocks, sql_outputs=sql_outputs, tool_names=tool_names)
    _apply_runtime_hard_gates(
        rule_check,
        case,
        task_completed=(
            len(turn_final_tasks) == len(_case_turns(case))
            and all(str(item.get("task_status") or "").lower() in SUCCESS_STATUSES for item in turn_final_tasks)
        ),
        agent_turn_count=int(sdk_counts.get("agent_turn_count") or 0),
        tool_call_count=tool_call_count,
    )
    reference_result = _compare_reference(
        reference_result,
        _actual_query_row_sets(blocks),
        case,
        actual_query_evidence=_actual_query_evidence(blocks),
    )
    _apply_reference_gate(rule_check, reference_result)
    task_timing = _aggregate_task_timing(task_records)
    return {
        "evaluation_engine": EVALUATION_ENGINE, "engine_version": ENGINE_VERSION,
        "metric_semantics_version": METRIC_SEMANTICS_VERSION, "judge_prompt_version": JUDGE_PROMPT_VERSION,
        "case_id": case.get("case_id"),
        "category": case.get("category"),
        "question": _case_question(case),
        "turns": _case_turns(case),
        "agent_id": str(args.agent_id or "").strip(),
        "topic_id": topic_id,
        "task_id": str(task.get("task_id") or task_id),
        "task_ids": task_ids,
        "task_status": str(task.get("task_status") or ""),
        "final_answer": final_answer,
        "tool_names": tool_names,
        "sql_outputs": sql_outputs,
        "chart_outputs": chart_outputs,
        "tool_events": _summarize_tool_events(blocks),
        "query_evidence": _summarize_query_evidence(blocks),
        "usage": usage,
        "duration_seconds": e2e_seconds,
        "timing": {**task_timing, "e2e_seconds": e2e_seconds, "judge_seconds": None},
        "user_turn_count": len(_case_turns(case)), **sdk_counts,
        "tool_call_count": tool_call_count,
        "sql_execution_count": len([block for block in blocks if _is_successful_sql_execution(block)]),
        "reference_data_accuracy": {key: value for key, value in reference_result.items() if key != "rows"},
        "sdk_event_count": len(sdk_events),
        "auto_rule_check": rule_check,
        "judge": {},
        "veto_rules_triggered": list(rule_check.get("triggered_veto_rules") or []),
        "case_passed": False,
        "errors": errors,
        "conversation": conversation,
    }


def _run_cases(base_url: str, cases: list[dict[str, Any]], args: argparse.Namespace) -> list[dict[str, Any]]:
    if args.concurrency <= 1:
        completed: list[dict[str, Any]] = []
        for case in cases:
            try:
                completed.append(run_case(base_url, case, args))
            except InfrastructureAbort as exc:
                exc.partial_results = completed
                raise
        return completed

    results: list[dict[str, Any] | None] = [None] * len(cases)
    for batch_start in range(0, len(cases), args.concurrency):
        batch = list(enumerate(cases[batch_start : batch_start + args.concurrency], start=batch_start))
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            future_to_index = {pool.submit(run_case, base_url, case, args): index for index, case in batch}
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
                        "evaluation_engine": EVALUATION_ENGINE, "engine_version": ENGINE_VERSION,
                        "case_id": case.get("case_id"), "category": case.get("category"),
                        "question": _case_question(case), "turns": _case_turns(case),
                        "agent_id": str(getattr(args, "agent_id", "") or "").strip(),
                        "task_status": "runner_error", "final_answer": "", "tool_names": [],
                        "sql_outputs": [], "chart_outputs": [], "usage": {}, "duration_seconds": 0,
                        "timing": {}, "auto_rule_check": {"passed": False, "failure_attribution": ["runner_crash"]},
                        "judge": {}, "veto_rules_triggered": [], "case_passed": False,
                        "errors": [{"code": "runner_crash", "message": str(exc)}],
                    }
                result = results[index]
                case_id = (result or {}).get("case_id") if result else "?"
                print(f"[{len([r for r in results if r is not None])}/{len(cases)}] {case_id} done", file=sys.stderr)
    return [r for r in results if r is not None]


def _ensure_deepeval_available() -> None:
    if deepeval_evaluate is None or LLMTestCase is None:
        raise EvalRunnerError("deepeval is not installed; run through the DeepEval eval Docker image or install requirements.txt")


def to_deepeval_test_case(case: dict[str, Any], case_result: dict[str, Any]) -> Any:
    _ensure_deepeval_available()
    judge_case = _case_for_judge(case)
    expected_payload = {
        "case_id": case.get("case_id"),
        "category": case.get("category"),
        "expected_semantics": judge_case.get("expected_semantics") or {},
        "expected_time": judge_case.get("expected_time") or {},
        "expected_tools": judge_case.get("expected_tools") or {},
        "expected_sql": judge_case.get("expected_sql") or {},
        "expected_result": judge_case.get("expected_result") or {},
        "expected_answer": judge_case.get("expected_answer") or {},
        "scoring": judge_case.get("scoring") or {},
        "veto_rules": judge_case.get("veto_rules") or [],
        "judge_guidance": judge_case.get("judge_guidance") or "",
    }
    context_payload = {"case": judge_case, "case_result": case_result}
    return LLMTestCase(
        input=_case_question(case),
        actual_output=str(case_result.get("final_answer") or ""),
        expected_output=json.dumps(expected_payload, ensure_ascii=False, sort_keys=True),
        context=[json.dumps(context_payload, ensure_ascii=False, sort_keys=True)],
    )


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


def normalize_judge_payload(data: dict[str, Any], *, raw_output: str = "") -> dict[str, Any]:
    dimensions: dict[str, float] = {}
    raw_dimensions = data.get("dimension_scores")
    if isinstance(raw_dimensions, dict):
        for key in JUDGE_DIMENSIONS:
            raw_value = raw_dimensions.get(key)
            if key == "result_consistency" and raw_value is None:
                raw_value = raw_dimensions.get("data_accuracy")
            dimensions[key] = _normalize_float(raw_value, minimum=0, maximum=DIMENSION_MAX[key])
    computed = sum(dimensions.values())
    raw_score = _normalize_float(data.get("score"), minimum=0, maximum=10)
    complete_dimensions = isinstance(raw_dimensions, dict) and all(
        key in raw_dimensions or (key == "result_consistency" and "data_accuracy" in raw_dimensions)
        for key in JUDGE_DIMENSIONS
    )
    inconsistent = complete_dimensions and abs(raw_score - computed) > 0.001
    return {
        "score": computed if complete_dimensions else raw_score,
        "dimension_scores": dimensions,
        "hallucination": _normalize_bool(data.get("hallucination")),
        "veto_rules_triggered": _string_list(data.get("veto_rules_triggered")),
        "failure_attribution": _dedupe(_string_list(data.get("failure_attribution")) + (["judge_score_inconsistent"] if inconsistent else [])),
        "comment": str(data.get("comment") or "").strip(),
        # A complete dimension vector is the authoritative score source.  A
        # disagreeing model-supplied total is useful diagnostics, but it does
        # not mean the judge call itself failed.
        "judge_failed": _normalize_bool(data.get("judge_failed")),
        "raw_output": raw_output,
    }


def failed_judge(reason: str, *, raw_output: str = "", attribution: list[str] | None = None) -> dict[str, Any]:
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


def _apply_deterministic_dimension_scores(
    judge: dict[str, Any],
    rule_check: dict[str, Any],
) -> dict[str, Any]:
    if bool(judge.get("judge_failed")):
        return judge
    dimensions = (
        dict(judge.get("dimension_scores"))
        if isinstance(judge.get("dimension_scores"), dict)
        else {}
    )
    if not dimensions or not all(name in dimensions for name in JUDGE_DIMENSIONS):
        return judge
    hard_gates = rule_check.get("hard_gates") if isinstance(rule_check.get("hard_gates"), dict) else {}
    changed = False
    if "required_tool_execution" in hard_gates and "sql_execution_and_口径" in hard_gates:
        sql_passed = (
            bool(hard_gates.get("required_tool_execution"))
            and bool(hard_gates.get("sql_execution_and_口径"))
            and bool(hard_gates.get("time_dimension", True))
        )
        dimensions["sql_or_tool_call"] = DIMENSION_MAX["sql_or_tool_call"] if sql_passed else 0.0
        changed = True
    consistency = (
        rule_check.get("result_consistency")
        if isinstance(rule_check.get("result_consistency"), dict)
        else {}
    )
    if bool(consistency.get("applicable")):
        dimensions["result_consistency"] = (
            DIMENSION_MAX["result_consistency"]
            if bool(consistency.get("passed"))
            else 0.0
        )
        changed = True
    if not changed:
        return judge
    updated = dict(judge)
    updated["dimension_scores"] = dimensions
    updated["score"] = round(sum(float(dimensions.get(name) or 0) for name in JUDGE_DIMENSIONS), 4)
    return updated


def _case_for_judge(case: dict[str, Any]) -> dict[str, Any]:
    judge_case = dict(case)
    judge_case.pop("forbidden_sql_patterns", None)
    expected_result = (
        dict(judge_case.get("expected_result"))
        if isinstance(judge_case.get("expected_result"), dict)
        else {}
    )
    expected_result.pop("reference_query", None)
    judge_case["expected_result"] = expected_result
    judge_case["veto_rules"] = [
        rule
        for rule in case.get("veto_rules") or []
        if "SQL 不带 schema 前缀" not in str(rule) and "SELECT *" not in str(rule)
    ]
    return judge_case


def _judge_system_prompt() -> str:
    return (
        "你是 DataAgent 在线问数评测裁判。只能基于请求中的 case、最终回答、工具事件、SQL/图表输出和自动规则检查打分。"
        "查询执行、必需 SQL 片段、时间范围、空结果、结构可比性和参考结果匹配已由确定性规则处理，不要重新推翻这些结论。"
        "你只负责确定性规则无法覆盖的意图、实体/关系语义、推理质量、回答表达，以及回答事实与全部成功查询证据的语义一致性。"
        "只有最终回答中的事实被实际工具结果直接反驳，或没有任何工具证据支持时，才可据此扣 result_consistency、answer_quality 或判定 hallucination。"
        "不要调用任何工具，不要编造事实。必须只输出一个 JSON 对象，字段为："
        "score, dimension_scores, hallucination, veto_rules_triggered, failure_attribution, comment。"
        "score 为维度之和；dimension_scores 包含 intent(1), ontology_entity(1), relation_scope(1), "
        "sql_or_tool_call(2), result_consistency(2), reasoning(2), answer_quality(1)。"
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
            {"role": "user", "content": _judge_message_content(payload, repair=attempt > 0, previous_output=raw_output)},
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
            return normalize_judge_payload(parsed, raw_output=raw_output)
        except EvalRunnerError as exc:
            if attempt == max_attempts - 1:
                return failed_judge(str(exc), raw_output=raw_output, attribution=["judge_failed", "judge_http_error"])
        except Exception as exc:
            if attempt == max_attempts - 1:
                return failed_judge(f"裁判模型未返回合法 JSON: {exc}", raw_output=raw_output)
    return failed_judge("裁判模型未返回合法 JSON", raw_output=raw_output)


class DataAgentEvaluationMetric(BaseMetric):  # type: ignore[misc, valid-type]
    shared_case_judges: dict[str, dict[str, Any]] = {}
    shared_case_judge_seconds: dict[str, float] = {}

    def __init__(self, judge_config: JudgeConfig, threshold: float = 0.8):
        self.judge_config = judge_config
        self.threshold = threshold
        self.score = 0.0
        self.reason = ""
        self.success = False
        self.case_judges: dict[str, dict[str, Any]] = {}

    def measure(self, test_case: Any) -> float:
        payload = self._payload_from_test_case(test_case)
        case_id = str(payload.get("case", {}).get("case_id") or "")
        judge_started = time.time()
        judge = call_judge_model(self.judge_config, payload)
        self.__class__.shared_case_judge_seconds[case_id] = round(time.time() - judge_started, 3)
        if not bool(judge.get("judge_failed")):
            judge = normalize_judge_payload(judge, raw_output=str(judge.get("raw_output") or ""))
        self.case_judges[case_id] = judge
        self.__class__.shared_case_judges[case_id] = judge
        self.score = min(1.0, max(0.0, float(judge.get("score") or 0) / 10.0))
        self.reason = str(judge.get("comment") or "")
        self.success = (
            self.score >= self.threshold
            and not bool(judge.get("judge_failed"))
            and not bool(judge.get("hallucination"))
            and not (judge.get("veto_rules_triggered") or [])
        )
        return self.score

    async def a_measure(self, test_case: Any) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return bool(self.success)

    @property
    def __name__(self) -> str:
        return "DataAgentEvaluationMetric"

    @staticmethod
    def _payload_from_test_case(test_case: Any) -> dict[str, Any]:
        context = getattr(test_case, "context", None) or []
        if not context:
            raise EvalRunnerError("DeepEval test case context is missing")
        context_payload = json.loads(str(context[0]))
        case = context_payload.get("case") or {}
        case_result = context_payload.get("case_result") or {}
        return {
            "case": case,
            "user_question": str(case.get("question") or getattr(test_case, "input", "") or ""),
            "final_answer": str(case_result.get("final_answer") or getattr(test_case, "actual_output", "") or ""),
            "task_status": str(case_result.get("task_status") or ""),
            "task_error": None,
            "tool_events": case_result.get("tool_events") or [],
            "query_evidence": case_result.get("query_evidence") or [],
            "sql_outputs": case_result.get("sql_outputs") or [],
            "chart_outputs": case_result.get("chart_outputs") or [],
            "auto_rule_check": case_result.get("auto_rule_check") or {},
        }


def _test_case_case_id(test_case: Any) -> str:
    try:
        payload = DataAgentEvaluationMetric._payload_from_test_case(test_case)
    except Exception:
        return ""
    return str((payload.get("case") or {}).get("case_id") or "")


def run_deepeval(test_cases: list[Any], metric: DataAgentEvaluationMetric) -> None:
    """Drive the judge metric locally, fully offline.

    DeepEval's ``evaluate()`` couples each run to telemetry and Confident AI
    cloud calls. On intranet deployments those calls fail or hang *after* every
    case has already been measured, which previously crashed the runner before
    any report was written. The runner only needs each metric to call our own
    Anthropic-compatible judge, so we iterate the test cases directly. This is
    the single primary path and requires no deepeval.com / Confident AI service.

    A single failing case is recorded as a failed judge instead of aborting the
    whole batch, so a complete report is always produced.
    """
    _ensure_deepeval_available()
    DataAgentEvaluationMetric.shared_case_judges = {}
    DataAgentEvaluationMetric.shared_case_judge_seconds = {}
    for test_case in test_cases:
        case_id = _test_case_case_id(test_case)
        try:
            metric.measure(test_case)
        except Exception as exc:  # never lose the remaining cases or the report
            judge = failed_judge(
                f"judge measurement crashed: {exc}",
                attribution=["judge_failed", "judge_crash"],
            )
            metric.case_judges[case_id] = judge
            DataAgentEvaluationMetric.shared_case_judges[case_id] = judge
            print(f"judge measurement crashed for case {case_id or '?'}: {exc}", file=sys.stderr)


def _apply_judges(results: list[dict[str, Any]], metric: DataAgentEvaluationMetric) -> list[dict[str, Any]]:
    for item in results:
        case_id = str(item.get("case_id") or "")
        judge = (
            metric.case_judges.get(case_id)
            or DataAgentEvaluationMetric.shared_case_judges.get(case_id)
            or failed_judge("DeepEval metric did not return a judge result")
        )
        judge = _apply_deterministic_dimension_scores(judge, item.get("auto_rule_check") or {})
        judge = _merge_auto_failure_attribution(judge, item.get("auto_rule_check") or {})
        veto_rules = list(item.get("auto_rule_check", {}).get("triggered_veto_rules") or []) + list(judge.get("veto_rules_triggered") or [])
        item["judge"] = judge
        item.setdefault("timing", {})["judge_seconds"] = DataAgentEvaluationMetric.shared_case_judge_seconds.get(case_id)
        item["veto_rules_triggered"] = veto_rules
        item.setdefault("auto_rule_check", {}).setdefault("hard_gates", {})["no_hallucination"] = not bool(judge.get("hallucination"))
        item.setdefault("auto_rule_check", {}).setdefault("hard_gates", {})["no_veto"] = not bool(veto_rules)
        if bool(judge.get("hallucination")) or veto_rules:
            item["auto_rule_check"]["passed"] = False
        item["case_passed"] = (
            not item.get("errors")
            and str(item.get("task_status") or "").lower() in SUCCESS_STATUSES
            and bool((item.get("auto_rule_check") or {}).get("passed", True))
            and float(judge.get("score") or 0) >= 8
            and not bool(judge.get("judge_failed"))
            and not bool(judge.get("hallucination"))
            and not veto_rules
        )
    return results


def _avg(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    pos = (len(ordered) - 1) * percentile
    low, high = int(pos), min(len(ordered) - 1, int(pos) + 1)
    return round(ordered[low] + (ordered[high] - ordered[low]) * (pos - low), 4)


def _ratio(numerator: float, denominator: float) -> dict[str, Any]:
    return {"numerator": round(numerator, 4), "denominator": round(denominator, 4), "value": round(numerator / denominator, 4) if denominator else None}


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
            "evaluation_engine": EVALUATION_ENGINE, "engine_version": ENGINE_VERSION,
            "metric_semantics_version": METRIC_SEMANTICS_VERSION, "judge_prompt_version": JUDGE_PROMPT_VERSION,
            "run_status": "dry_run",
            "dry_run": True,
            "passed": True,
            "recommendation": "dry-run",
        }
    total = len(results)
    accepted = [item for item in results if item.get("task_id")]
    completed = [item for item in accepted if str(item.get("task_status") or "").lower() in SUCCESS_STATUSES]
    scores = [float((item.get("judge") or {}).get("score") or 0) for item in results]
    dimensions = [item.get("judge", {}).get("dimension_scores") or {} for item in completed]
    intent = _ratio(sum(float(dim.get("intent") or 0) >= 1 for dim in dimensions), len(dimensions))
    ontology = _ratio(sum(float(dim.get("ontology_entity") or 0) >= 1 for dim in dimensions), len(dimensions))
    relation = _ratio(sum(float(dim.get("relation_scope") or 0) >= 1 for dim in dimensions), len(dimensions))
    reasoning_average = round(_avg([float(dim.get("reasoning") or 0) for dim in dimensions]), 4)
    tool_sql = _ratio(sum(bool((item.get("auto_rule_check") or {}).get("hard_gates", {}).get("sql_execution_and_口径")) for item in completed), len(completed))
    time_cases = [item for item in completed if bool((item.get("auto_rule_check") or {}).get("time_dimension", {}).get("applicable"))]
    time_accuracy = _ratio(sum(bool((item.get("auto_rule_check") or {}).get("time_dimension", {}).get("passed")) for item in time_cases), len(time_cases))
    answer = _ratio(sum(float(dim.get("answer_quality") or 0) >= 1 for dim in dimensions), len(dimensions))
    def result_consistency_passed(item: dict[str, Any]) -> bool:
        automatic = (item.get("auto_rule_check") or {}).get("result_consistency") or {}
        if bool(automatic.get("applicable")):
            return bool(automatic.get("passed"))
        dimension = (item.get("judge") or {}).get("dimension_scores") or {}
        return float(dimension.get("result_consistency") or 0) >= DIMENSION_MAX["result_consistency"]

    consistency_cases = [
        item
        for item in completed
        if bool(((item.get("auto_rule_check") or {}).get("result_consistency") or {}).get("applicable"))
        or "result_consistency" in ((item.get("judge") or {}).get("dimension_scores") or {})
    ]
    consistency = _ratio(
        sum(result_consistency_passed(item) for item in consistency_cases),
        len(consistency_cases),
    )
    reference_cases = [item for item in completed if (item.get("reference_data_accuracy") or {}).get("applicable")]
    comparable_reference_cases = [
        item
        for item in reference_cases
        if bool((item.get("reference_data_accuracy") or {}).get("comparable"))
    ]
    data_comparability = _ratio(len(comparable_reference_cases), len(reference_cases))
    data_accuracy = _ratio(
        sum(bool((item.get("reference_data_accuracy") or {}).get("passed")) for item in comparable_reference_cases),
        len(comparable_reference_cases),
    )
    assertion_pass = assertion_total = 0.0
    for item in completed:
        dim = (item.get("judge") or {}).get("dimension_scores") or {}
        checks = {
            "intent": float(dim.get("intent") or 0) >= 1,
            "ontology_entity": float(dim.get("ontology_entity") or 0) >= 1,
            "relation_scope": float(dim.get("relation_scope") or 0) >= 1,
            "sql_or_tool_call": bool((item.get("auto_rule_check") or {}).get("hard_gates", {}).get("required_tool_execution")),
            "result_consistency": result_consistency_passed(item),
            "reasoning": float(dim.get("reasoning") or 0) >= 2,
            "answer_quality": float(dim.get("answer_quality") or 0) >= 1,
        }
        for key, weight in DIMENSION_MAX.items():
            assertion_total += weight
            assertion_pass += weight if checks[key] else 0
    hallucination_rate = _avg([1.0 if bool(item.get("judge", {}).get("hallucination")) else 0.0 for item in results])
    veto_count = sum(len(item.get("veto_rules_triggered") or []) for item in results)
    judge_failed_count = sum(1 for item in results if bool(item.get("judge", {}).get("judge_failed")))
    timing: dict[str, Any] = {}
    for name in ("queue_wait_seconds", "execution_seconds", "e2e_seconds", "judge_seconds"):
        values = [float((item.get("timing") or {}).get(name)) for item in results if isinstance((item.get("timing") or {}).get(name), (int, float))]
        timing[name] = {"average": round(_avg(values), 4) if values else None, "p50": _percentile(values, .5), "p90": _percentile(values, .9), "p95": _percentile(values, .95)}
    counts: dict[str, Any] = {}
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
        counts[name] = {"average": round(_avg(values), 4) if values else None, "p95": _percentile(values, .95)}
    metrics = {
        "average_score": round(_avg(scores), 4),
        "reasoning_average": reasoning_average,
        "intent_accuracy": intent["value"], "ontology_accuracy": ontology["value"], "relation_accuracy": relation["value"],
        "completion_rate": _ratio(len(completed), len(accepted)),
        "business_accuracy": _ratio(assertion_pass, assertion_total),
        "effective_pass_rate": _ratio(sum(bool(item.get("case_passed")) for item in results), total),
        "semantic_accuracy": _ratio(intent["numerator"] + ontology["numerator"] + relation["numerator"], intent["denominator"] + ontology["denominator"] + relation["denominator"]),
        "tool_sql_accuracy": tool_sql, "answer_accuracy": answer,
        "result_consistency_rate": consistency,
        "data_comparability_rate": data_comparability,
        "data_accuracy": data_accuracy,
        "time_accuracy": time_accuracy,
        "hallucination_rate": round(hallucination_rate, 4),
        "timing": timing, "counts": counts,
    }
    gate_metric_names = {
        "average_score": "average_score",
        "intent_accuracy": "intent_accuracy",
        "ontology_accuracy": "ontology_accuracy",
        "sql_tool_accuracy": "tool_sql_accuracy",
        "result_consistency_rate": "result_consistency_rate",
        "data_accuracy": "data_accuracy",
        "reasoning_average": "reasoning_average",
        "hallucination_rate": "hallucination_rate",
    }
    gate_results: dict[str, dict[str, Any]] = {}
    for gate_name, threshold in GATES.items():
        metric_name = gate_metric_names[gate_name]
        metric = metrics.get(metric_name)
        actual = metric.get("value") if isinstance(metric, dict) else metric
        applicable = actual is not None
        comparator = "<=" if gate_name == "hallucination_rate" else ">="
        passed = True if not applicable else (
            float(actual) <= float(threshold)
            if comparator == "<="
            else float(actual) >= float(threshold)
        )
        gate_results[gate_name] = {
            "metric": metric_name,
            "actual": actual,
            "threshold": threshold,
            "comparator": comparator,
            "applicable": applicable,
            "passed": passed,
        }
    gates_passed = (
        bool(results)
        and metrics["completion_rate"]["value"] == 1.0
        and veto_count == 0
        and judge_failed_count == 0
        and all(bool(item.get("passed")) for item in gate_results.values())
    )
    return {
        **dataset_stats,
        "evaluation_engine": EVALUATION_ENGINE, "engine_version": ENGINE_VERSION,
        "metric_semantics_version": METRIC_SEMANTICS_VERSION, "judge_prompt_version": JUDGE_PROMPT_VERSION,
        "run_status": "completed",
        "dry_run": False,
        "total_cases": len(results),
        "passed_cases": sum(1 for item in results if bool(item.get("case_passed"))),
        "failed_cases": sum(1 for item in results if not bool(item.get("case_passed"))),
        "veto_count": veto_count,
        "judge_failed_count": judge_failed_count,
        "metrics": metrics,
        "gates": GATES,
        "gate_results": gate_results,
        "passed": gates_passed,
        "recommendation": "建议上线" if gates_passed else "不建议上线",
    }


def render_report(summary: dict[str, Any], results: list[dict[str, Any]]) -> str:
    lines = [
        "# DataAgent DeepEval 评测报告",
        "",
        f"- 引擎: `{summary.get('engine', 'deepeval')}`",
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
            f"| 参考结果可比率 | {ratio_text('data_comparability_rate')} |",
            f"| 参考 SQL 数据准确率 | {ratio_text('data_accuracy')} |",
            f"| 推理平均分（0-2） | {float(metrics.get('reasoning_average') or 0):.2f} |",
            f"| 幻觉率 | {float(metrics.get('hallucination_rate') or 0):.2%} |",
            "",
            "## 门禁",
            "",
            "| 门禁 | 实际值 | 条件 | 通过 |",
            "|---|---:|---:|---|",
        ]
    )
    for name, gate in (summary.get("gate_results") or {}).items():
        actual = gate.get("actual")
        actual_text = "N/A" if actual is None else f"{float(actual):.4f}"
        lines.append(
            f"| {name} | {actual_text} | {gate.get('comparator')} {gate.get('threshold')} | "
            f"{'是' if gate.get('passed') else '否'} |"
        )
    lines.extend(
        [
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
    run_payload = {key: summary.get(key) for key in ("evaluation_engine", "engine_version", "metric_semantics_version", "judge_prompt_version", "run_status", "run_label", "environment_label", "dataset_id", "dataset_hash", "schema_version", "case_ids", "agent_id", "agent_snapshot_hash", "model", "judge_model", "concurrency", "auth_enabled")}
    (output_dir / "run.json").write_text(json.dumps(run_payload, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    rows = "".join(f"<tr><td>{html.escape(str(item.get('case_id') or ''))}</td><td>{float((item.get('judge') or {}).get('score') or 0):.2f}</td><td>{'PASS' if item.get('case_passed') else 'FAIL'}</td></tr>" for item in results)
    document = f"""<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>DeepEval V2</title><style>body{{font:14px system-ui;margin:32px}}table{{border-collapse:collapse;width:100%}}td,th{{border:1px solid #ddd;padding:8px}}pre{{background:#f6f8fa;padding:16px;overflow:auto}}</style><h1>DataAgent DeepEval V2</h1><table><tr><th>case_id</th><th>score</th><th>gate</th></tr>{rows}</table><pre>{html.escape(json.dumps(summary, ensure_ascii=False, indent=2))}</pre></html>"""
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


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.concurrency < 1:
        print("--concurrency must be >= 1", file=sys.stderr)
        return 2
    if not str(args.dataset or "").strip():
        print("--dataset is required and must point to an evaluation V2 JSONL file", file=sys.stderr)
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
        dataset_stats.update({"run_label": str(args.run_label or "") or _timestamp(), "environment_label": str(args.environment_label or "local"), "agent_snapshot_hash": _snapshot_hash(str(args.agent_snapshot_path or "")), "agent_id": str(args.agent_id or "").strip(), "model": str(args.model or ""), "judge_model": str(args.judge_model or ""), "concurrency": int(args.concurrency)})
        _write_dataset_snapshot(output_dir, cases)
        if args.dry_run:
            summary = build_summary([], dataset_stats, dry_run=True)
            write_outputs(output_dir, [], summary)
            print(f"eval outputs written to: {output_dir}")
            return 0

        _ensure_deepeval_available()
        judge_config = _judge_config_from_args(args)
        base_url = str(args.base_url or "").rstrip("/")
        preflight_payload = preflight(base_url, str(args.auth_token or ""))
        dataset_stats["preflight"] = preflight_payload
        dataset_stats["auth_enabled"] = bool((preflight_payload.get("auth") or {}).get("auth_enabled"))
        try:
            results = _run_cases(base_url, cases, args)
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
        try:
            metric = DataAgentEvaluationMetric(judge_config)
            test_cases = [to_deepeval_test_case(case, result) for case, result in zip(cases, results)]
            run_deepeval(test_cases, metric)
            _apply_judges(results, metric)
            summary = build_summary(results, dataset_stats)
        except Exception as exc:
            # The expensive case runs already finished; never drop the report
            # just because the judging/summary step failed. Persist what we have.
            for item in results:
                if not item.get("judge"):
                    item["judge"] = failed_judge(f"judging aborted: {exc}")
            summary = build_summary(results, dataset_stats)
            summary["judging_error"] = str(exc)
            write_outputs(output_dir, results, summary)
            print(f"eval outputs written to: {output_dir}")
            print(f"judging step failed after all cases ran: {exc}", file=sys.stderr)
            return 1
        write_outputs(output_dir, results, summary)
        print(f"eval outputs written to: {output_dir}")
        return 0 if summary.get("passed") else 1
    except EvalRunnerError as exc:
        write_outputs(output_dir, [], {"evaluation_engine": EVALUATION_ENGINE, "engine_version": ENGINE_VERSION, "metric_semantics_version": METRIC_SEMANTICS_VERSION, "judge_prompt_version": JUDGE_PROMPT_VERSION, "run_status": "infra_failed", "passed": False, "recommendation": "基础设施失败", "infrastructure_error": str(exc)})
        print(str(exc), file=sys.stderr)
        return exc.exit_code


if __name__ == "__main__":
    sys.exit(main())
