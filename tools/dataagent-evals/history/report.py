#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import math
import statistics
from pathlib import Path
from typing import Any


SUCCESS_STATUSES = {"success", "finished"}
COMPATIBILITY_FIELDS = (
    "dataset_id", "dataset_hash", "case_set_hash", "agent_snapshot_hash", "model",
    "judge_model", "judge_prompt_version", "metric_semantics_version", "concurrency",
    "environment_label",
)


class HistoryError(Exception):
    pass


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Import DataAgent evaluation history and build offline trends.")
    parser.add_argument("--scan-root", action="append", required=True, help="Source tree containing run directories; repeatable.")
    parser.add_argument("--history-root", required=True, help="Output directory for normalized runs and reports.")
    return parser.parse_args(argv)


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise HistoryError(f"cannot read {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise HistoryError(f"expected JSON object: {path}")
    return value


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    result: list[dict[str, Any]] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as exc:
            raise HistoryError(f"invalid JSONL {path}:{line_no}: {exc}") from exc
        if isinstance(item, dict):
            result.append(item)
    return result


def discover_runs(scan_roots: list[Path], history_root: Path) -> list[Path]:
    found: set[Path] = set()
    resolved_history = history_root.resolve()
    for root in scan_roots:
        if not root.exists():
            raise HistoryError(f"scan root not found: {root}")
        for summary_path in root.rglob("summary.json"):
            run_dir = summary_path.parent.resolve()
            if run_dir == resolved_history or resolved_history in run_dir.parents:
                continue
            if (run_dir / "cases.jsonl").exists() or (run_dir / "run.json").exists():
                found.add(run_dir)
    return sorted(found, key=lambda item: str(item))


def _ratio(numerator: float, denominator: float) -> dict[str, Any]:
    return {
        "numerator": numerator,
        "denominator": denominator,
        "value": round(numerator / denominator, 6) if denominator else None,
    }


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return round(ordered[low], 4)
    return round(ordered[low] + (ordered[high] - ordered[low]) * (position - low), 4)


def _number(value: Any) -> float | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    return None


def _usage(case: dict[str, Any], names: tuple[str, ...]) -> float | None:
    usage = case.get("usage") if isinstance(case.get("usage"), dict) else {}
    for name in names:
        value = _number(usage.get(name))
        if value is not None:
            return value
    return None


def _case_record(case: dict[str, Any]) -> dict[str, Any]:
    timing = case.get("timing") if isinstance(case.get("timing"), dict) else {}
    duration = _number(timing.get("e2e_seconds"))
    if duration is None:
        duration = _number(case.get("duration_seconds"))
    judge = case.get("judge") if isinstance(case.get("judge"), dict) else {}
    attribution = list(judge.get("failure_attribution") or [])
    for error in case.get("errors") or []:
        if isinstance(error, dict) and error.get("code"):
            attribution.append(str(error["code"]))
    return {
        "case_id": str(case.get("case_id") or ""),
        "category": str(case.get("category") or ""),
        "task_id": str(case.get("task_id") or ""),
        "task_status": str(case.get("task_status") or ""),
        "case_passed": case.get("case_passed") if isinstance(case.get("case_passed"), bool) else None,
        "score": _number(judge.get("score")),
        "e2e_seconds": duration,
        "queue_wait_seconds": _number(timing.get("queue_wait_seconds")),
        "execution_seconds": _number(timing.get("execution_seconds")),
        "judge_seconds": _number(timing.get("judge_seconds")),
        "agent_turn_count": _number(case.get("agent_turn_count")),
        "tool_call_count": _number(case.get("tool_call_count")),
        "input_tokens": _usage(case, ("input_tokens", "inputTokens")),
        "output_tokens": _usage(case, ("output_tokens", "outputTokens")),
        "cache_tokens": _usage(case, ("cache_read_input_tokens", "cache_tokens", "cacheTokens")),
        "failure_attribution": sorted(set(attribution)),
    }


def _aggregate_reliable_cases(cases: list[dict[str, Any]]) -> dict[str, Any]:
    accepted = [case for case in cases if case.get("task_id")]
    completed = [case for case in accepted if str(case.get("task_status") or "").lower() in SUCCESS_STATUSES]
    metrics: dict[str, Any] = {"completion_rate": _ratio(len(completed), len(accepted))}
    durations = [float(case["e2e_seconds"]) for case in cases if case.get("e2e_seconds") is not None]
    metrics["timing"] = {
        "e2e_seconds": {
            "average": round(statistics.fmean(durations), 4) if durations else None,
            "p50": _percentile(durations, .50),
            "p90": _percentile(durations, .90),
            "p95": _percentile(durations, .95),
        }
    }
    counts: dict[str, Any] = {}
    for name in ("agent_turn_count", "tool_call_count", "input_tokens", "output_tokens", "cache_tokens"):
        values = [float(case[name]) for case in cases if case.get(name) is not None]
        counts[name] = {
            "average": round(statistics.fmean(values), 4) if values else None,
            "p95": _percentile(values, .95),
        }
    metrics["counts"] = counts
    return metrics


def _case_set_hash(case_ids: list[str]) -> str:
    return hashlib.sha256("\n".join(sorted(case_ids)).encode("utf-8")).hexdigest()


def _run_timestamp(name: str, summary: dict[str, Any]) -> str:
    raw = str(summary.get("created_at") or summary.get("started_at") or "")
    if raw:
        return raw
    digits = "".join(char for char in name if char.isdigit())[:14]
    if len(digits) == 14:
        try:
            return dt.datetime.strptime(digits, "%Y%m%d%H%M%S").isoformat()
        except ValueError:
            pass
    return name


def _infer_model(summary: dict[str, Any]) -> str:
    preflight = summary.get("preflight") if isinstance(summary.get("preflight"), dict) else {}
    health = preflight.get("health") if isinstance(preflight.get("health"), dict) else {}
    return str(summary.get("model") or health.get("model") or "")


def normalize_run(run_dir: Path) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    summary = _read_json(run_dir / "summary.json")
    raw_cases = _read_jsonl(run_dir / "cases.jsonl")
    cases = [_case_record(case) for case in raw_cases]
    persisted_run = _read_json(run_dir / "run.json") if (run_dir / "run.json").exists() else {}
    is_v2 = str(persisted_run.get("metric_semantics_version") or summary.get("metric_semantics_version") or "").startswith("2")
    engine = str(
        persisted_run.get("evaluation_engine") or summary.get("evaluation_engine") or summary.get("engine")
        or ("deepeval" if run_dir.name.startswith("deepeval-") else "builtin")
    )
    case_ids = [case["case_id"] for case in cases if case["case_id"]]
    total = len(cases) or int(summary.get("total_cases") or 0)
    legacy_50 = run_dir.name.startswith("20260515-") and total == 50
    run_id = run_dir.name
    run = {
        "run_id": run_id,
        "source_path": str(run_dir),
        "timestamp": _run_timestamp(run_dir.name, summary),
        "evaluation_engine": engine,
        "engine_version": persisted_run.get("engine_version") or summary.get("engine_version"),
        "metric_semantics_version": persisted_run.get("metric_semantics_version") or summary.get("metric_semantics_version") or "legacy-v1",
        "judge_prompt_version": persisted_run.get("judge_prompt_version") or summary.get("judge_prompt_version"),
        "dataset_id": persisted_run.get("dataset_id") or Path(str(summary.get("dataset_path") or "unknown")).stem,
        "dataset_hash": persisted_run.get("dataset_hash") or summary.get("dataset_hash"),
        "case_ids": case_ids,
        "case_set_hash": _case_set_hash(case_ids),
        "agent_id": persisted_run.get("agent_id") or summary.get("agent_id"),
        "agent_snapshot_hash": persisted_run.get("agent_snapshot_hash") or summary.get("agent_snapshot_hash"),
        "model": persisted_run.get("model") or _infer_model(summary),
        "judge_model": persisted_run.get("judge_model") or summary.get("judge_model"),
        "concurrency": persisted_run.get("concurrency") or summary.get("concurrency"),
        "environment_label": persisted_run.get("environment_label") or summary.get("environment_label"),
        "run_status": persisted_run.get("run_status") or summary.get("run_status") or "completed",
        "legacy": not is_v2,
        "legacy_50_case_suite": legacy_50,
        "total_cases": total,
    }
    missing_compatibility = [field for field in COMPATIBILITY_FIELDS if run.get(field) in (None, "", [])]
    compatible = is_v2 and not missing_compatibility and not legacy_50
    compatibility_values = [str(run.get(field)) for field in COMPATIBILITY_FIELDS]
    run["compatibility_missing"] = missing_compatibility
    run["compatibility_key"] = hashlib.sha256("\x1f".join(compatibility_values).encode("utf-8")).hexdigest() if compatible else None
    run["trend_group"] = f"{engine}:{run['compatibility_key']}" if compatible else f"legacy-isolated:{run_id}"
    normalized_summary = {
        "run_id": run_id,
        "evaluation_engine": engine,
        "run_status": run["run_status"],
        "total_cases": total,
        "metrics": summary.get("metrics") if is_v2 else _aggregate_reliable_cases(cases),
        "legacy_metrics": None if is_v2 else summary.get("metrics"),
        "legacy_gates": None if is_v2 else summary.get("gates"),
        "passed": summary.get("passed"),
    }
    return run, normalized_summary, cases


def _metric_value(summary: dict[str, Any], name: str) -> float | None:
    metrics = summary.get("metrics") if isinstance(summary.get("metrics"), dict) else {}
    value = metrics.get(name)
    if isinstance(value, dict):
        return _number(value.get("value"))
    return _number(value)


def _timing_value(summary: dict[str, Any], timing_name: str, statistic_name: str) -> float | None:
    metrics = summary.get("metrics") if isinstance(summary.get("metrics"), dict) else {}
    timing = metrics.get("timing") if isinstance(metrics.get("timing"), dict) else {}
    item = timing.get(timing_name) if isinstance(timing.get(timing_name), dict) else {}
    return _number(item.get(statistic_name))


def _fmt(value: float | None, *, percent: bool = False) -> str:
    if value is None:
        return "N/A"
    return f"{value:.2%}" if percent else f"{value:.2f}"


def _html_shell(title: str, body: str) -> str:
    return f"""<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>{html.escape(title)}</title>
<style>body{{font:14px system-ui;margin:28px;color:#172033}}table{{border-collapse:collapse;width:100%;margin:16px 0}}th,td{{border:1px solid #d8dee8;padding:7px;text-align:left}}th{{background:#f1f5f9;position:sticky;top:0}}.na{{color:#8992a3}}.pass{{background:#bbf7d0}}.fail{{background:#fecaca}}.unknown{{background:#e5e7eb}}.scroll{{overflow:auto;max-height:640px}}code,pre{{background:#f6f8fa;padding:2px 5px}}h1,h2{{margin-top:28px}}</style><h1>{html.escape(title)}</h1>{body}</html>"""


def render_trend(entries: list[dict[str, Any]]) -> str:
    rows: list[str] = []
    all_case_ids = sorted({case["case_id"] for entry in entries for case in entry["cases"] if case["case_id"]})
    for entry in entries:
        run, summary = entry["run"], entry["summary"]
        rows.append(
            "<tr>"
            f"<td>{html.escape(run['run_id'])}</td><td>{html.escape(run['evaluation_engine'])}</td>"
            f"<td>{run['total_cases']}</td><td>{_fmt(_metric_value(summary, 'completion_rate'), percent=True)}</td>"
            f"<td>{_fmt(_metric_value(summary, 'business_accuracy'), percent=True)}</td>"
            f"<td>{_fmt(_metric_value(summary, 'effective_pass_rate'), percent=True)}</td>"
            f"<td>{_fmt(_timing_value(summary, 'e2e_seconds', 'p50'))}</td>"
            f"<td>{_fmt(_timing_value(summary, 'e2e_seconds', 'p95'))}</td>"
            f"<td>{'legacy/isolated' if run['legacy'] else html.escape(str(run['trend_group']))}</td></tr>"
        )
    heat_header = "".join(f"<th>{html.escape(entry['run']['run_id'])}</th>" for entry in entries)
    heat_rows: list[str] = []
    for case_id in all_case_ids:
        cells = []
        for entry in entries:
            found = next((case for case in entry["cases"] if case["case_id"] == case_id), None)
            passed = None if found is None else found.get("case_passed")
            css = "pass" if passed is True else "fail" if passed is False else "unknown"
            cells.append(f"<td class=\"{css}\">{'✓' if passed is True else '×' if passed is False else 'N/A'}</td>")
        heat_rows.append(f"<tr><th>{html.escape(case_id)}</th>{''.join(cells)}</tr>")
    body = f"""
<p>Only V2 runs with complete compatibility metadata share a trend group. Legacy metrics are preserved but missing values remain N/A.</p>
<div class=\"scroll\"><table><thead><tr><th>run</th><th>engine</th><th>cases</th><th>completion</th><th>business accuracy</th><th>effective pass</th><th>e2e P50(s)</th><th>e2e P95(s)</th><th>group</th></tr></thead><tbody>{''.join(rows)}</tbody></table></div>
<h2>Case × run stability</h2><div class=\"scroll\"><table><thead><tr><th>case</th>{heat_header}</tr></thead><tbody>{''.join(heat_rows)}</tbody></table></div>"""
    return _html_shell("DataAgent Evaluation Trends", body)


def render_engine_comparison(entries: list[dict[str, Any]]) -> str:
    groups: dict[str, list[dict[str, Any]]] = {}
    for entry in entries:
        key = entry["run"].get("compatibility_key")
        if key:
            groups.setdefault(str(key), []).append(entry)
    comparison_rows: list[str] = []
    review_rows: list[str] = []
    for key, group in groups.items():
        latest_by_engine: dict[str, dict[str, Any]] = {}
        for entry in sorted(group, key=lambda item: item["run"]["timestamp"]):
            latest_by_engine[entry["run"]["evaluation_engine"]] = entry
        if len(latest_by_engine) < 2:
            continue
        engine_names = sorted(latest_by_engine)
        base = latest_by_engine[engine_names[0]]
        for engine_name in engine_names[1:]:
            candidate = latest_by_engine[engine_name]
            base_cases = {case["case_id"]: case for case in base["cases"]}
            other_cases = {case["case_id"]: case for case in candidate["cases"]}
            shared = sorted(set(base_cases) & set(other_cases))
            known = [case_id for case_id in shared if base_cases[case_id]["case_passed"] is not None and other_cases[case_id]["case_passed"] is not None]
            agreed = [case_id for case_id in known if base_cases[case_id]["case_passed"] == other_cases[case_id]["case_passed"]]
            differing = [case_id for case_id in known if case_id not in agreed]
            agreement = _ratio(len(agreed), len(known))
            comparison_rows.append(
                f"<tr><td>{key[:12]}</td><td>{html.escape(engine_names[0])}</td><td>{html.escape(engine_name)}</td>"
                f"<td>{_fmt(_metric_value(base['summary'], 'business_accuracy'), percent=True)}</td>"
                f"<td>{_fmt(_metric_value(candidate['summary'], 'business_accuracy'), percent=True)}</td>"
                f"<td>{_fmt(agreement['value'], percent=True)} ({agreement['numerator']}/{agreement['denominator']})</td>"
                f"<td>{len(differing)}</td></tr>"
            )
            for case_id in differing:
                review_rows.append(
                    f"<tr><td>{key[:12]}</td><td>{html.escape(case_id)}</td>"
                    f"<td>{html.escape(engine_names[0])}: {base_cases[case_id]['case_passed']}</td>"
                    f"<td>{html.escape(engine_name)}: {other_cases[case_id]['case_passed']}</td></tr>"
                )
    if not comparison_rows:
        body = "<p class=\"na\">No compatible multi-engine V2 runs were found. Legacy or incomplete runs were intentionally not compared.</p>"
    else:
        body = f"<table><thead><tr><th>group</th><th>engine A</th><th>engine B</th><th>A accuracy</th><th>B accuracy</th><th>case agreement</th><th>manual review</th></tr></thead><tbody>{''.join(comparison_rows)}</tbody></table><h2>Manual review list</h2><table><tbody>{''.join(review_rows)}</tbody></table>"
    return _html_shell("DataAgent Engine Comparison", body)


def _write_normalized_run(history_root: Path, entry: dict[str, Any]) -> None:
    source_hash = hashlib.sha256(entry["run"]["source_path"].encode("utf-8")).hexdigest()[:8]
    target = history_root / "runs" / f"{entry['run']['run_id']}-{source_hash}"
    target.mkdir(parents=True, exist_ok=True)
    (target / "run.json").write_text(json.dumps(entry["run"], ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    (target / "summary.json").write_text(json.dumps(entry["summary"], ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    with (target / "cases.jsonl").open("w", encoding="utf-8") as handle:
        for case in entry["cases"]:
            handle.write(json.dumps(case, ensure_ascii=False, sort_keys=True) + "\n")
    report = f"# {entry['run']['run_id']}\n\n- engine: `{entry['run']['evaluation_engine']}`\n- cases: {entry['run']['total_cases']}\n- legacy: {entry['run']['legacy']}\n- source: `{entry['run']['source_path']}`\n"
    (target / "report.md").write_text(report, encoding="utf-8")
    (target / "report.html").write_text(_html_shell(entry["run"]["run_id"], f"<pre>{html.escape(report)}</pre>"), encoding="utf-8")


def build_history(scan_roots: list[Path], history_root: Path) -> list[dict[str, Any]]:
    run_dirs = discover_runs(scan_roots, history_root)
    entries: list[dict[str, Any]] = []
    for run_dir in run_dirs:
        run, summary, cases = normalize_run(run_dir)
        entries.append({"run": run, "summary": summary, "cases": cases})
    entries.sort(key=lambda item: item["run"]["timestamp"])
    history_root.mkdir(parents=True, exist_ok=True)
    for entry in entries:
        _write_normalized_run(history_root, entry)
    with (history_root / "index.jsonl").open("w", encoding="utf-8") as handle:
        for entry in entries:
            handle.write(json.dumps({**entry["run"], "summary": entry["summary"]}, ensure_ascii=False, sort_keys=True) + "\n")
    (history_root / "trend.html").write_text(render_trend(entries), encoding="utf-8")
    (history_root / "engine-comparison.html").write_text(render_engine_comparison(entries), encoding="utf-8")
    return entries


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        entries = build_history([Path(value).expanduser() for value in args.scan_root], Path(args.history_root).expanduser())
    except HistoryError as exc:
        print(str(exc))
        return 2
    print(f"discovered {len(entries)} evaluation runs; reports written to {args.history_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
