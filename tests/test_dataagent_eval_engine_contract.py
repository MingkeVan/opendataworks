from __future__ import annotations

import copy
import importlib.util
import json
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNERS = {
    "builtin": REPO_ROOT / "tools" / "dataagent-evals" / "builtin" / "run.py",
    "deepeval": REPO_ROOT / "tools" / "dataagent-evals" / "deepeval" / "run.py",
    "opik": REPO_ROOT / "tools" / "dataagent-evals" / "opik" / "run.py",
}
DATASET = REPO_ROOT / "tools" / "dataagent-evals" / "dataset" / "examples" / "anonymous-v2.jsonl"
FIXTURE = REPO_ROOT / "tools" / "dataagent-evals" / "dataset" / "fixtures" / "evidence-v2.json"
LOCAL_DATASET = REPO_ROOT / "tools" / "dataagent-evals" / "dataset" / "examples" / "opendataworks-business-knowledge-smoke-v2.jsonl"


def _load(name, path):
    module_name = f"dataagent_contract_{name}"
    spec = importlib.util.spec_from_file_location(module_name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def _merge(base, patch):
    result = copy.deepcopy(base)
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = _merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def test_three_engines_load_the_same_v2_dataset_without_runtime_imports():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    stats = {}
    for name, module in modules.items():
        rows, loaded = module.load_dataset(DATASET)
        assert len(rows) == 1
        stats[name] = (loaded["dataset_hash"], tuple(loaded["case_ids"]))
        source = RUNNERS[name].read_text(encoding="utf-8")
        other_names = set(RUNNERS) - {name}
        assert all(f"dataagent-evals/{other}" not in source and f"from {other}" not in source for other in other_names)
    assert len(set(stats.values())) == 1


def test_three_engines_match_golden_evidence_and_sdk_metrics():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    base_case = json.loads(DATASET.read_text(encoding="utf-8").strip())
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    engine_results = {}
    for engine, module in modules.items():
        scenario_results = []
        for scenario in fixture["scenarios"]:
            case = _merge(base_case, scenario["case_patch"])
            blocks = scenario["blocks"]
            answer = scenario["final_answer"]
            tools = module._collect_tool_names(blocks)
            sqls = module._extract_sql_outputs(blocks, answer)
            checked = module.auto_rule_check(case, final_answer=answer, blocks=blocks, sql_outputs=sqls, tool_names=tools)
            actual = {
                "passed": checked["passed"],
                "sql_count": len(sqls),
                "sql_execution_count": sum(module._is_successful_sql_execution(block) for block in blocks),
            }
            assert actual == scenario["expected"], f"{engine}: {scenario['name']}"
            scenario_results.append(actual)
        assert module._sdk_metrics(fixture["sdk_events"]) == fixture["expected_sdk_metrics"]
        engine_results[engine] = scenario_results
    assert engine_results["builtin"] == engine_results["deepeval"] == engine_results["opik"]


def test_three_engines_scope_empty_results_to_target_query_and_enforce_limits():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_002")
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Skill",
            "input": {"skill": "opendataworks-business-knowledge"},
            "output": "Launching skill: opendataworks-business-knowledge",
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": '"$DATAAGENT_PYTHON_BIN" "$DATAAGENT_PLATFORM_SKILL_ROOT/scripts/run_sql.py" --sql "SELECT id FROM opendataworks.data_table WHERE 1 = 0"'},
            "output": {"kind": "sql_execution", "sql": "SELECT id FROM opendataworks.data_table WHERE 1 = 0", "rows": [], "row_count": 0, "result_state": "empty_result"},
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": '"$DATAAGENT_PYTHON_BIN" "$DATAAGENT_PLATFORM_SKILL_ROOT/scripts/run_sql.py" --sql "SELECT COUNT(id) AS table_cnt FROM opendataworks.data_table WHERE deleted = 0"'},
            "output": {"kind": "sql_execution", "sql": "SELECT COUNT(id) AS table_cnt FROM opendataworks.data_table WHERE deleted = 0", "rows": [{"table_cnt": 37}], "row_count": 1, "result_state": "success"},
        },
    ]
    for engine, module in modules.items():
        tools = module._collect_tool_names(blocks)
        sqls = module._extract_sql_outputs(blocks, "当前共有 37 张未删除数据表，口径为 table_cnt。")
        checked = module.auto_rule_check(
            case,
            final_answer="当前共有 37 张未删除数据表，口径为 table_cnt。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert checked["passed"] is True, engine
        assert checked["hard_gates"]["non_expected_empty_result"] is True, engine
        assert "wrong_domain" not in checked["failure_attribution"], engine

        inconsistent = module.auto_rule_check(
            case,
            final_answer="当前共有 38 张未删除数据表。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert inconsistent["hard_gates"]["result_consistency"] is False, engine

        module._apply_runtime_hard_gates(
            checked,
            case,
            task_completed=True,
            agent_turn_count=21,
            tool_call_count=11,
        )
        assert checked["passed"] is True, engine
        assert "agent_turn_limit" not in checked["hard_gates"], engine
        assert "tool_call_limit" not in checked["hard_gates"], engine
        assert checked["limit_violations"] == [], engine
        assert module._numeric_usage({"usage": {}}, ("input_tokens",)) is None, engine


def test_three_engines_keep_turns_and_usage_from_distinct_tasks():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    events = [
        {"_eval_task_id": "task-a", "task_id": "task-a", "turn_index": 1, "record_type": "assistant"},
        {"_eval_task_id": "task-b", "task_id": "task-b", "turn_index": 1, "record_type": "assistant"},
        {"_eval_task_id": "task-b", "task_id": "task-b", "turn_index": 2, "record_type": "assistant"},
    ]
    tasks = [
        {"task_id": "task-a", "usage": {"input_tokens": 10, "output_tokens": 4}},
        {"task_id": "task-b", "usage": {"input_tokens": 20, "output_tokens": 6}},
    ]
    messages = [
        {"task_id": "task-a", "usage": {"input_tokens": 10, "output_tokens": 4}},
        {"task_id": "task-b", "usage": {"input_tokens": 20, "output_tokens": 6}},
    ]

    for engine, module in modules.items():
        assert module._sdk_metrics(events)["agent_turn_count"] == 3, engine
        assert module._aggregate_usage(tasks, messages) == {
            "input_tokens": 30,
            "output_tokens": 10,
        }, engine


def test_three_engines_accept_equivalent_count_alias_and_select_target_result_shape():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_002")
    blocks = [
        {
            "type": "tool_use",
            "tool_name": "Skill",
            "input": {"skill": "opendataworks-business-knowledge"},
            "output": "Launching skill: opendataworks-business-knowledge",
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": "run_sql.py --sql SELECT COUNT(*) AS total FROM opendataworks.data_table WHERE deleted=0"},
            "output": {"kind": "sql_execution", "sql": "SELECT COUNT(*) AS total FROM opendataworks.data_table WHERE deleted=0", "rows": [{"total": 37}], "result_state": "success"},
        },
        {
            "type": "tool_use",
            "tool_name": "Bash",
            "input": {"command": "run_sql.py --sql SELECT status, COUNT(*) AS cnt FROM opendataworks.data_table WHERE deleted=0 GROUP BY status"},
            "output": {"kind": "sql_execution", "sql": "SELECT status, COUNT(*) AS cnt FROM opendataworks.data_table WHERE deleted=0 GROUP BY status", "rows": [{"status": "active", "cnt": 37}], "result_state": "success"},
        },
    ]

    for engine, module in modules.items():
        tools = module._collect_tool_names(blocks)
        sqls = module._extract_sql_outputs(blocks, "当前共有 37 张未删除数据表。")
        checked = module.auto_rule_check(
            case,
            final_answer="当前共有 37 张未删除数据表，口径为 deleted=0。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert checked["passed"] is True, engine
        assert checked["missing_sql_fragments"] == [], engine
        assert checked["result_consistency"] == {"applicable": True, "passed": True}, engine


def test_three_engines_validate_calendar_month_from_fixed_literal_bounds():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_004")

    for engine, module in modules.items():
        today = module.dt.datetime.now(module.dt.timezone(module.dt.timedelta(hours=8))).date()
        start = module._add_months(today.replace(day=1), 0)
        end = module._add_months(start, 1)
        sql = (
            "SELECT COUNT(*) AS failed_publish_cnt FROM opendataworks.workflow_publish_record "
            f"WHERE status='failed' AND created_at >= '{start.isoformat()} 00:00:00' "
            f"AND created_at < '{end.isoformat()} 00:00:00'"
        )
        blocks = [
            {"type": "tool_use", "tool_name": "Skill", "input": {"skill": "opendataworks-business-knowledge"}, "output": "ok"},
            {"type": "tool_use", "tool_name": "Bash", "input": {"command": f"run_sql.py --sql {sql}"}, "output": {"kind": "sql_execution", "sql": sql, "rows": [{"failed_publish_cnt": 0}], "result_state": "success"}},
        ]
        tools = module._collect_tool_names(blocks)
        sqls = module._extract_sql_outputs(blocks, "本月失败发布 0 次。")
        checked = module.auto_rule_check(
            case,
            final_answer="本月失败发布 0 次，按 created_at 的自然月边界统计。",
            blocks=blocks,
            sql_outputs=sqls,
            tool_names=tools,
        )
        assert checked["passed"] is True, engine
        assert checked["time_dimension"]["passed"] is True, engine
        assert checked["hard_gates"]["time_dimension"] is True, engine


def test_business_knowledge_allowed_empty_language_is_not_a_failure_code():
    modules = {name: _load(name, path) for name, path in RUNNERS.items()}
    local_cases = [json.loads(line) for line in LOCAL_DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    case = next(item for item in local_cases if item["case_id"] == "ODW_BK_005")
    blocks = [{"type": "tool_use", "tool_name": "Skill", "input": {"skill": "opendataworks-business-knowledge"}, "output": "ok"}]
    for engine, module in modules.items():
        tools = module._collect_tool_names(blocks)
        checked = module.auto_rule_check(
            case,
            final_answer="缺少 db_name，尚不能唯一定位 orders 表；请提供数据库名。",
            blocks=blocks,
            sql_outputs=[],
            tool_names=tools,
        )
        assert "empty_result" not in checked["failure_attribution"], engine
        assert checked["passed"] is True, engine
