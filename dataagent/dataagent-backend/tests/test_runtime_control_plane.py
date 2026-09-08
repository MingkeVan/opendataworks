import asyncio
import pytest
from core.agent_runtime import (
    AgentEvent,
    AgentEventType,
    AgentInteraction,
    AgentRunRequest,
    ContextBundle,
    ConversationMessage,
    DeploymentLock,
    DeploymentLockError,
    InteractionService,
    ModelTarget,
    RuntimePlaneClient,
    TaskEventIngestor,
    WorkspaceSpec,
)


def test_deployment_lock():
    assert DeploymentLock.get_active_runtime() == "pi_agent_core"
    DeploymentLock.verify_request_runtime("pi_agent_core")

    with pytest.raises(DeploymentLockError, match="Runtime switching is forbidden"):
        DeploymentLock.verify_request_runtime("claude_code")


def test_event_ingestor_accumulates_and_finalizes_message():
    run_id = "run-ingest-1"
    events_received = []

    ingestor = TaskEventIngestor(run_id=run_id, on_event=lambda ev: events_received.append(ev.sequence))

    # Ingest started
    ingestor.ingest(AgentEvent(
        event_id="e1",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=1,
        type=AgentEventType.RUN_STARTED,
    ))

    # Ingest text deltas
    ingestor.ingest(AgentEvent(
        event_id="e2",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=2,
        type=AgentEventType.CONTENT_DELTA,
        payload={"delta": "Hello ", "kind": "text"},
    ))
    ingestor.ingest(AgentEvent(
        event_id="e3",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=3,
        type=AgentEventType.CONTENT_DELTA,
        payload={"delta": "World!", "kind": "text"},
    ))

    # Ingest duplicate event (should be ignored)
    ingestor.ingest(AgentEvent(
        event_id="e3-dup",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=3,
        type=AgentEventType.CONTENT_DELTA,
        payload={"delta": "Duplicate!", "kind": "text"},
    ))

    # Finalize before terminal returns None
    assert ingestor.finalize_assistant_message() is None

    # Ingest completed
    ingestor.ingest(AgentEvent(
        event_id="e4",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=4,
        type=AgentEventType.RUN_COMPLETED,
    ))

    assert events_received == [1, 2, 3, 4]
    msg = ingestor.finalize_assistant_message()
    assert msg is not None
    assert msg.role == "assistant"
    assert msg.content == "Hello World!"


@pytest.mark.asyncio
async def test_interaction_service():
    service = InteractionService()

    interaction = AgentInteraction(
        interaction_id="inter-1",
        run_id="run-1",
        task_id="t1",
        kind="permission",
        request={"tool": "Write", "path": "file.txt"},
    )

    service.register_interaction(interaction)
    pending = service.list_pending("run-1")
    assert len(pending) == 1
    assert pending[0].interaction_id == "inter-1"

    resolved = await service.resolve_interaction("inter-1", {"allow": True})
    assert resolved is True
    assert service.get_interaction("inter-1").status == "resolved"
    assert service.get_interaction("inter-1").response == {"allow": True}
    assert len(service.list_pending("run-1")) == 0
