from __future__ import annotations

import importlib.util
import json
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
REPORT_PATH = REPO_ROOT / "tools" / "dataagent-evals" / "history" / "report.py"


def _load_reporter():
    spec = importlib.util.spec_from_file_location("dataagent_eval_history", REPORT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


def _write_run(root: Path, name: str, *, engine="builtin", v2=True, passed=True, case_count=1):
    run_dir = root / name
    run_dir.mkdir(parents=True)
    cases = []
    for index in range(case_count):
        cases.append({
            "case_id": f"CASE_{index:03d}", "category": "test", "task_id": f"task-{index}",
            "task_status": "finished", "case_passed": passed, "duration_seconds": 10 + index,
            "usage": {"input_tokens": 100 + index, "output_tokens": 10},
            "judge": {"score": 10 if passed else 5, "failure_attribution": [] if passed else ["answer"]},
        })
    with (run_dir / "cases.jsonl").open("w", encoding="utf-8") as handle:
        for case in cases:
            handle.write(json.dumps(case) + "\n")
    metrics = {"average_score": 10 if passed else 5, "data_precision": 1 if passed else 0}
    (run_dir / "summary.json").write_text(json.dumps({"metrics": metrics, "total_cases": case_count, "engine": engine}), encoding="utf-8")
    if v2:
        run = {
            "evaluation_engine": engine, "engine_version": "2", "metric_semantics_version": "2.0",
            "judge_prompt_version": "prompt-v2", "dataset_id": "dataset", "dataset_hash": "hash",
            "agent_snapshot_hash": "skill", "model": "model", "judge_model": "judge",
            "concurrency": 1, "environment_label": "local",
        }
        (run_dir / "run.json").write_text(json.dumps(run), encoding="utf-8")
    return run_dir


def test_legacy_import_preserves_old_metrics_and_does_not_invent_missing_values(tmp_path):
    reporter = _load_reporter()
    run_dir = _write_run(tmp_path / "source", "20260515-094038", v2=False, case_count=50)

    run, summary, cases = reporter.normalize_run(run_dir)

    assert run["legacy"] is True
    assert run["legacy_50_case_suite"] is True
    assert run["compatibility_key"] is None
    assert summary["legacy_metrics"]["data_precision"] == 1
    assert summary["metrics"]["completion_rate"] == {"numerator": 50, "denominator": 50, "value": 1.0}
    assert summary["metrics"]["counts"]["agent_turn_count"]["average"] is None
    assert cases[0]["agent_turn_count"] is None


def test_history_build_writes_normalized_runs_and_self_contained_reports(tmp_path):
    reporter = _load_reporter()
    source = tmp_path / "source"
    _write_run(source, "20260720-010101", engine="builtin")
    _write_run(source, "20260720-010102", engine="opik")
    history = tmp_path / "history"

    entries = reporter.build_history([source], history)

    assert len(entries) == 2
    assert len((history / "index.jsonl").read_text(encoding="utf-8").splitlines()) == 2
    assert "DataAgent Evaluation Trends" in (history / "trend.html").read_text(encoding="utf-8")
    comparison = (history / "engine-comparison.html").read_text(encoding="utf-8")
    assert "builtin" in comparison and "opik" in comparison
    normalized_runs = list((history / "runs").glob("*/run.json"))
    assert len(normalized_runs) == 2


def test_incompatible_v2_runs_are_not_compared(tmp_path):
    reporter = _load_reporter()
    source = tmp_path / "source"
    first = _write_run(source, "20260720-020101", engine="builtin")
    second = _write_run(source, "20260720-020102", engine="opik")
    second_run = json.loads((second / "run.json").read_text(encoding="utf-8"))
    second_run["dataset_hash"] = "different"
    (second / "run.json").write_text(json.dumps(second_run), encoding="utf-8")

    entries = reporter.build_history([source], tmp_path / "history")
    comparison = reporter.render_engine_comparison(entries)

    assert "No compatible multi-engine V2 runs" in comparison
    assert first.exists()
