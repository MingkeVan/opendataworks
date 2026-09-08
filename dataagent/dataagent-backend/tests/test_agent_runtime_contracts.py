import json
from pathlib import Path
import pytest
from core.agent_runtime.contracts import (
    AgentEvent,
    AgentEventType,
    AgentRunRequest,
    ContextBundle,
    ConversationMessage,
    ModelTarget,
    WorkspaceSpec,
    RuntimeManifest,
    RuntimeFeatures,
    CellProtocolFrame,
)


def test_compatibility_cases_load_and_validate():
    repo_root = Path(__file__).resolve().parent.parent.parent.parent
    cases_path = repo_root / "dataagent" / "contracts" / "agent-events" / "v1" / "compatibility-cases.json"
    assert cases_path.exists(), f"Cases file not found at {cases_path}"

    with open(cases_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    cases = data.get("cases", [])
    assert len(cases) > 0

    for case in cases:
        for event_dict in case["events"]:
            event = AgentEvent.model_validate(event_dict)
            assert event.sequence >= 1
            assert event.type in AgentEventType


def test_agent_run_request_construction():
    req = AgentRunRequest(
        run_id="run-100",
        task_id="task-100",
        task_attempt_id="att-1",
        topic_id="topic-100",
        context=ContextBundle(
            context_snapshot_id="snap-1",
            system_instructions="You are a data assistant.",
            messages=[
                ConversationMessage(role="user", content="hello"),
            ],
        ),
        model=ModelTarget(provider_id="faux", model_id="faux-default"),
        workspace=WorkspaceSpec(workspace_root="/tmp/test-workspace"),
    )

    d = req.model_dump()
    assert d["runtime_protocol_version"] == 1
    assert d["agent_event_protocol_version"] == 1
    assert d["run_id"] == "run-100"
    assert d["context"]["renderer_target"] == "pi_agent_core"


def test_runtime_manifest_and_cell_frame():
    manifest = RuntimeManifest(
        runtime_version="0.1.0",
        pi_agent_core_version="0.85.1",
        pi_ai_version="0.85.1",
        node_version="v22.19.0",
        providers=["anthropic", "openai", "faux"],
        artifact_digest="sha256:abc123",
    )
    assert manifest.runtime_kind == "pi_agent_core"
    assert manifest.features.streaming is True

    frame = CellProtocolFrame(
        cell_id="cell-1",
        run_id="run-1",
        task_attempt_id="att-1",
        frame_id="f-1",
        type="run.start",
        payload={"foo": "bar"},
    )
    assert frame.protocol_version == 1
    assert frame.type == "run.start"
