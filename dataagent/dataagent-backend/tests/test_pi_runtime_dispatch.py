"""Runtime-kind resolution and Pi outcome -> TaskExecutionResult translation."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import Settings, resolve_runtime_kind  # noqa: E402
from core import task_executor  # noqa: E402
from core.pi_runtime import PiRunOutcome  # noqa: E402
from core.task_executor import TaskExecutionInput  # noqa: E402


def test_runtime_kind_defaults_to_claude_code():
    assert resolve_runtime_kind(Settings()) == "claude_code"


def test_runtime_kind_accepts_pi_agent_core():
    assert resolve_runtime_kind(Settings(dataagent_runtime_kind="pi_agent_core")) == "pi_agent_core"
    assert resolve_runtime_kind(Settings(dataagent_runtime_kind="  PI_AGENT_CORE  ")) == "pi_agent_core"


def test_unknown_runtime_kind_falls_back_instead_of_raising():
    """A typo in one env var must not take the whole backend down."""
    assert resolve_runtime_kind(Settings(dataagent_runtime_kind="pi-agent-core")) == "claude_code"
    assert resolve_runtime_kind(Settings(dataagent_runtime_kind="")) == "claude_code"


class _FakeStore:
    def __init__(self) -> None:
        self.records: list[dict[str, Any]] = []

    def append_sdk_record(self, **kwargs: Any) -> None:
        self.records.append(kwargs)


def _params(**overrides: Any) -> TaskExecutionInput:
    defaults: dict[str, Any] = dict(
        task_id="task-1",
        topic_id="topic-1",
        question="最近 30 天工作流发布次数趋势",
        history=[
            {"role": "user", "content": "上一轮问题"},
            {"role": "assistant", "content": "上一轮回答"},
            {"role": "user", "content": "   "},
        ],
        resume_session_id="sdk-session-should-be-ignored",
        provider_id="anthropic",
        model="test-model",
        database_hint=None,
    )
    defaults.update(overrides)
    return TaskExecutionInput(**defaults)


async def _run_adapter(monkeypatch, tmp_path: Path, outcome: PiRunOutcome, captured: dict[str, Any]):
    store = _FakeStore()
    monkeypatch.setattr(task_executor, "get_topic_task_store", lambda: store)
    monkeypatch.setattr("core.pi_runtime.resolve_cell_command", lambda cfg=None: ["/bin/true"])

    async def _fake_execute(ctx, *, writer, cancel_reason=None, **kwargs):
        captured["ctx"] = ctx
        return outcome

    monkeypatch.setattr("core.pi_runtime.execute_pi_run", _fake_execute)

    async def _no_cancel():
        return None

    return await task_executor._execute_task_stream_via_pi_runtime(
        _params(),
        provider_id="anthropic",
        model="test-model",
        system_prompt="sys",
        skill_runtime={"enabled_roots": {}},
        project_cwd=tmp_path,
        runtime_env={"DATAAGENT_PYTHON_BIN": sys.executable},
        provider_env={"ANTHROPIC_API_KEY": "sk-x"},
        agent_snapshot=None,
        cancel_reason=_no_cancel,
    )


@pytest.mark.asyncio
async def test_success_outcome_becomes_success_result(monkeypatch, tmp_path: Path):
    captured: dict[str, Any] = {}
    result = await _run_adapter(
        monkeypatch, tmp_path, PiRunOutcome(terminal_status="success", answer="趋势结果"), captured
    )

    assert result.task_status == "success"
    assert result.content == "趋势结果"
    assert result.provider_id == "anthropic"


@pytest.mark.asyncio
async def test_failure_outcome_becomes_error_result(monkeypatch, tmp_path: Path):
    captured: dict[str, Any] = {}
    result = await _run_adapter(
        monkeypatch,
        tmp_path,
        PiRunOutcome(terminal_status="failed", error_code="PI_RUN_TIMEOUT", error_message="超时"),
        captured,
    )

    assert result.task_status == "error"
    assert result.error == {"code": "PI_RUN_TIMEOUT", "message": "超时"}


@pytest.mark.asyncio
async def test_cancelled_outcome_becomes_cancelled_result(monkeypatch, tmp_path: Path):
    captured: dict[str, Any] = {}
    result = await _run_adapter(
        monkeypatch, tmp_path, PiRunOutcome(terminal_status="cancelled", answer="部分"), captured
    )

    assert result.task_status == "cancelled"
    assert result.content == "部分"


@pytest.mark.asyncio
async def test_history_is_replayed_and_resume_session_id_is_ignored(monkeypatch, tmp_path: Path):
    """Pi has no engine-level session, so every turn replays the transcript."""
    captured: dict[str, Any] = {}
    await _run_adapter(monkeypatch, tmp_path, PiRunOutcome(terminal_status="success"), captured)

    messages = captured["ctx"].messages
    assert messages == [
        {"role": "user", "content": "上一轮问题"},
        {"role": "assistant", "content": "上一轮回答"},
        {"role": "user", "content": "最近 30 天工作流发布次数趋势"},
    ]


@pytest.mark.asyncio
async def test_session_id_is_task_scoped(monkeypatch, tmp_path: Path):
    """A topic-scoped constant would make a later claude_code run drop history."""
    captured: dict[str, Any] = {}
    result = await _run_adapter(
        monkeypatch, tmp_path, PiRunOutcome(terminal_status="success"), captured
    )

    assert result.session_id == "pi-topic-1-task-1"
    assert result.session_id != "pi-topic-1"


@pytest.mark.asyncio
async def test_boundary_policy_uses_the_pi_profile(monkeypatch, tmp_path: Path):
    captured: dict[str, Any] = {}
    await _run_adapter(monkeypatch, tmp_path, PiRunOutcome(terminal_status="success"), captured)

    policy = captured["ctx"].boundary_policy
    assert policy["profile"] == "pi_agent_core"
    assert policy["tool_result_root"] is None
    assert str(tmp_path) in policy["allowed_roots"]


@pytest.mark.asyncio
async def test_missing_pi_runtime_reports_error_instead_of_raising(monkeypatch, tmp_path: Path):
    from core.pi_runtime import PiRuntimeUnavailable

    store = _FakeStore()
    monkeypatch.setattr(task_executor, "get_topic_task_store", lambda: store)

    def _unavailable(cfg=None):
        raise PiRuntimeUnavailable("Pi Cell 入口不存在：/nope")

    monkeypatch.setattr("core.pi_runtime.resolve_cell_command", _unavailable)

    async def _no_cancel():
        return None

    result = await task_executor._execute_task_stream_via_pi_runtime(
        _params(),
        provider_id="anthropic",
        model="test-model",
        system_prompt="sys",
        skill_runtime={"enabled_roots": {}},
        project_cwd=tmp_path,
        runtime_env={},
        provider_env={},
        agent_snapshot=None,
        cancel_reason=_no_cancel,
    )

    assert result.task_status == "error"
    assert result.error["code"] == "pi_runtime_missing"
    # Surfaced through the shared error record, same as the SDK path does for
    # a missing claude-agent-sdk.
    assert store.records[-1]["record_type"] == "error"
