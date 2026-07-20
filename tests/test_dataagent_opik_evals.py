from __future__ import annotations

import importlib.util
import json
import sys
import types
from contextlib import nullcontext
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNER_PATH = REPO_ROOT / "tools" / "dataagent-evals" / "opik" / "run.py"


def _sample_case() -> dict:
    return {
        "schema_version": 2,
        "case_id": "OPIK_SAMPLE_001",
        "case_type": "query",
        "suite_tags": ["test", "tool-execution"],
        "category": "anonymous",
        "question": "how many rows",
        "expected_semantics": {"intent": "aggregate", "ontology_object_ids": ["object"], "relation_ids": [], "business_rules": [], "default_environment": "test", "required_slots": []},
        "expected_time": {"required": False, "field": "", "range": {}, "grain": "", "timezone": "Asia/Shanghai", "snapshot_strategy": "execution_date"},
        "expected_tools": {"required_steps": ["query_execute"], "allowed_alternative_groups": [["run_sql"]], "ordered": False, "min_calls": 1, "max_calls": 5},
        "expected_sql": {"execution_required": True, "tables": ["example"], "fields": [], "predicates": [], "aggregations": [], "forbidden_patterns": []},
        "expected_result": {"allow_empty": False, "required_columns": ["cnt"], "answer_result_fields": ["cnt"]},
        "expected_answer": {"required_points": ["count"], "boundary_notes": [], "units": [], "error_expression": []},
        "limits": {"max_wait_seconds": 30, "max_agent_turns": 5, "max_tool_calls": 5},
        "scoring": {"intent": 1, "ontology_entity": 1, "relation_scope": 1, "sql_or_tool_call": 2, "result_consistency": 2, "reasoning": 2, "answer_quality": 1, "total_score": 10},
        "veto_rules": [],
    }


def _install_fake_opik(monkeypatch):
    calls: dict[str, object] = {"datasets": {}, "evaluate": None, "scores": []}

    class ScoreResult:
        def __init__(self, *, name, value, reason="", metadata=None):
            self.name = name
            self.value = value
            self.reason = reason
            self.metadata = metadata

    class BaseMetric:
        def __init__(self, *, name, track=True):
            self.name = name
            self.track = track

    class Dataset:
        def __init__(self, name):
            self.name = name
            self.items = []

        def insert(self, items):
            for item in items:
                if item not in self.items:
                    self.items.append(item)

    class Opik:
        def __init__(self, *, host, project_name):
            calls["client"] = {"host": host, "project_name": project_name}

        def get_or_create_dataset(self, *, name, description=None):
            datasets = calls["datasets"]
            if name not in datasets:
                datasets[name] = Dataset(name)
            return datasets[name]

        def flush(self):
            calls["flushed"] = True

    def track(**metadata):
        calls["track"] = metadata
        return lambda function: function

    def start_as_current_span(**metadata):
        calls.setdefault("spans", []).append(metadata)
        return nullcontext()

    def evaluate(*, dataset, task, scoring_metrics, **kwargs):
        calls["evaluate"] = kwargs
        for item in dataset.items:
            output = task(**item)
            for metric in scoring_metrics:
                calls["scores"].extend(metric.score(**output))
        return types.SimpleNamespace(test_results=[])

    opik_module = types.ModuleType("opik")
    opik_module.__version__ = "2.1.32"
    opik_module.Opik = Opik
    opik_module.track = track
    opik_module.start_as_current_span = start_as_current_span
    evaluation_module = types.ModuleType("opik.evaluation")
    evaluation_module.evaluate = evaluate
    metrics_module = types.ModuleType("opik.evaluation.metrics")
    metrics_module.BaseMetric = BaseMetric
    metrics_module.ScoreResult = ScoreResult
    monkeypatch.setitem(sys.modules, "opik", opik_module)
    monkeypatch.setitem(sys.modules, "opik.evaluation", evaluation_module)
    monkeypatch.setitem(sys.modules, "opik.evaluation.metrics", metrics_module)
    return calls


def _load_runner(monkeypatch):
    calls = _install_fake_opik(monkeypatch)
    spec = importlib.util.spec_from_file_location("dataagent_opik_runner", RUNNER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules["dataagent_opik_runner"] = module
    spec.loader.exec_module(module)
    return module, calls


def test_opik_dry_run_uses_v2_without_platform_calls(tmp_path, monkeypatch):
    runner, calls = _load_runner(monkeypatch)
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps(_sample_case()) + "\n", encoding="utf-8")

    code = runner.main(["--dry-run", "--dataset", str(dataset), "--output-dir", str(tmp_path / "out")])

    assert code == 0
    summary = json.loads((tmp_path / "out" / "summary.json").read_text(encoding="utf-8"))
    assert summary["evaluation_engine"] == "opik"
    assert summary["engine_version"] == "2.1.32"
    assert calls["evaluate"] is None


def test_opik_metric_exports_dimensions_hard_gate_and_reference_accuracy(monkeypatch):
    runner, _ = _load_runner(monkeypatch)
    result = {
        "case_id": "OPIK_SAMPLE_001",
        "case_passed": True,
        "judge": {"score": 10, "dimension_scores": {name: maximum for name, maximum in runner.DIMENSION_MAX.items()}, "comment": "ok"},
        "auto_rule_check": {"result_consistency": {"applicable": True, "passed": True}},
        "reference_data_accuracy": {"applicable": True, "passed": True},
    }

    scores = runner.DataAgentOpikMetric().score(case_result=result)
    values = {score.name: score.value for score in scores}

    assert values["v2.intent"] == 1
    assert values["v2.total"] == 10
    assert values["v2.hard_gate"] == 1
    assert values["v2.result_consistency"] == 1
    assert values["v2.data_accuracy"] == 1


def test_opik_experiment_imports_dataset_and_records_standard_metadata(monkeypatch):
    runner, calls = _load_runner(monkeypatch)
    case = _sample_case()
    args = types.SimpleNamespace(
        opik_project_name="dataagent-evals", opik_base_url="http://opik:5173/api",
        opik_dataset_name="", opik_experiment_name="experiment-1", run_label="run-1",
        model="model-1", judge_model="judge-1", concurrency=1, environment_label="local",
        agent_id="agent-1", provider_id="", timeout_seconds=30,
    )
    result = {
        "case_id": case["case_id"], "category": case["category"], "task_status": "finished",
        "task_id": "task-1", "final_answer": "count=1", "case_passed": True, "errors": [],
        "judge": {"score": 10, "dimension_scores": {name: maximum for name, maximum in runner.DIMENSION_MAX.items()}, "comment": "ok"},
        "auto_rule_check": {"result_consistency": {"applicable": True, "passed": True}},
        "reference_data_accuracy": {"applicable": False, "passed": None},
    }
    monkeypatch.setattr(runner, "run_case", lambda *_args, **_kwargs: dict(result))
    judge = runner.JudgeConfig(base_url="http://judge", token="secret", model="judge-1")
    stats = {"dataset_hash": "a" * 64, "dataset_id": "anonymous", "schema_version": 2, "agent_snapshot_hash": "skill-hash", "auth_enabled": True}

    results, metadata = runner._run_opik_experiment("http://dataagent", [case], args, judge, stats)

    assert results[0]["case_id"] == case["case_id"]
    assert metadata["opik_dataset_name"] == "anonymous-aaaaaaaaaaaa"
    inserted = calls["datasets"][metadata["opik_dataset_name"]].items[0]
    assert inserted["source_file_hash"] == "a" * 64
    assert inserted["case"]["expected_sql"]["tables"] == ["example"]
    assert calls["evaluate"]["experiment_config"]["skill_snapshot_hash"] == "skill-hash"
    assert "secret" not in json.dumps(calls, default=str)
    assert calls["flushed"] is True


def test_opik_dataagent_requests_use_same_identity_headers(monkeypatch):
    runner, _ = _load_runner(monkeypatch)
    observed = {}

    def fake_http(method, url, payload=None, **kwargs):
        observed.update(kwargs.get("headers") or {})
        return {"ok": True}

    runner._DATAAGENT_AUTH_TOKEN = "admin-session"
    monkeypatch.setattr(runner, "http_json", fake_http)

    runner.dataagent_http_json("GET", "http://dataagent/api/v1/nl2sql/health")

    assert observed["Authorization"] == "Bearer admin-session"
    assert observed["X-ODW-Client"] == "dataagent"


def test_opik_sdk_version_is_fixed(monkeypatch):
    runner, _ = _load_runner(monkeypatch)
    runner.opik.__version__ = "2.2.0"

    try:
        runner._ensure_opik_available()
    except runner.EvalRunnerError as exc:
        assert "expected 2.1.32" in str(exc)
    else:
        raise AssertionError("version mismatch must fail")


def test_opik_reference_accuracy_matches_any_successful_query_result(monkeypatch):
    runner, _ = _load_runner(monkeypatch)
    case = _sample_case()
    case["expected_result"]["reference_query"] = {"sql": "SELECT 2", "comparison_mode": "scalar"}

    result = runner._compare_reference(
        {"applicable": True, "rows": [{"expected_cnt": 2}]},
        [[{"diagnostic": 99}], [{"actual_cnt": 2}]],
        case,
    )

    assert result["passed"] is True
    assert result["candidate_result_count"] == 2
