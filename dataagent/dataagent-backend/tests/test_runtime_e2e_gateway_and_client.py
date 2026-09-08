import asyncio
import pytest
from pathlib import Path
import httpx

from core.agent_runtime import (
    AgentEvent,
    AgentEventType,
    AgentRunRequest,
    ContextBundle,
    ConversationMessage,
    ModelTarget,
    RuntimePlaneClient,
    TaskEventIngestor,
    WorkspaceSpec,
)
from runtime_gateway.app import app, event_spool


@pytest.mark.asyncio
async def test_runtime_client_e2e_streaming_and_ingestion(tmp_path: Path):
    event_spool.base_dir = tmp_path

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as http_client:
        client = RuntimePlaneClient(gateway_url="http://testserver")
        client._http_client = http_client

        # 1. Health check
        is_healthy = await client.check_health()
        assert is_healthy is True

        # 2. Manifest check
        manifest = await client.get_manifest()
        assert manifest.runtime_kind == "pi_agent_core"
        assert manifest.pi_agent_core_version == "0.85.1"

        # 3. Stream and ingest events
        run_id = "run-e2e-1"
        ingestor = TaskEventIngestor(run_id=run_id)

        # Append events to spool asynchronously to simulate runtime cell output
        async def produce_events():
            await asyncio.sleep(0.05)
            await event_spool.append_event(AgentEvent(
                event_id="e1",
                run_id=run_id,
                task_id="task-1",
                task_attempt_id="att-1",
                sequence=1,
                type=AgentEventType.RUN_STARTED,
            ))
            await event_spool.append_event(AgentEvent(
                event_id="e2",
                run_id=run_id,
                task_id="task-1",
                task_attempt_id="att-1",
                sequence=2,
                type=AgentEventType.CONTENT_DELTA,
                payload={"delta": "OpenDataWorks intelligent query completed successfully."},
            ))
            await event_spool.append_event(AgentEvent(
                event_id="e3",
                run_id=run_id,
                task_id="task-1",
                task_attempt_id="att-1",
                sequence=3,
                type=AgentEventType.RUN_COMPLETED,
            ))

        producer_task = asyncio.create_task(produce_events())

        # Consume from client
        async for event in client.stream_events(run_id=run_id, after_sequence=0):
            ingestor.ingest(event)

        await producer_task

        assert ingestor.highest_sequence == 3
        assert ingestor.is_terminal is True
        msg = ingestor.finalize_assistant_message()
        assert msg is not None
        assert msg.role == "assistant"
        assert msg.content == "OpenDataWorks intelligent query completed successfully."
