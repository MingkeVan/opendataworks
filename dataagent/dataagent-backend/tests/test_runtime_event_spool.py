import asyncio
import pytest
from pathlib import Path
from core.agent_runtime.contracts import AgentEvent, AgentEventType
from runtime_gateway.event_spool import EventSpool, SequenceViolationError


@pytest.mark.asyncio
async def test_event_spool_append_and_read(tmp_path: Path):
    spool = EventSpool(base_dir=tmp_path)
    run_id = "run-test-1"

    ev1 = AgentEvent(
        event_id="e1",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=1,
        type=AgentEventType.RUN_STARTED,
        payload={},
    )
    ev2 = AgentEvent(
        event_id="e2",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=2,
        type=AgentEventType.CONTENT_DELTA,
        payload={"delta": "hello"},
    )
    ev3 = AgentEvent(
        event_id="e3",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=3,
        type=AgentEventType.RUN_COMPLETED,
        payload={},
    )

    await spool.append_event(ev1)
    await spool.append_event(ev2)
    await spool.append_event(ev3)

    # Verify read all
    all_events = spool.read_events(run_id)
    assert len(all_events) == 3
    assert [e.sequence for e in all_events] == [1, 2, 3]
    assert all_events[1].payload == {"delta": "hello"}

    # Verify read after_sequence
    after_seq_1 = spool.read_events(run_id, after_sequence=1)
    assert len(after_seq_1) == 2
    assert [e.sequence for e in after_seq_1] == [2, 3]

    # Verify terminal status
    assert spool.is_terminal(run_id) is True


@pytest.mark.asyncio
async def test_event_spool_sequence_gap_error(tmp_path: Path):
    spool = EventSpool(base_dir=tmp_path)
    run_id = "run-test-gap"

    ev1 = AgentEvent(
        event_id="e1",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=1,
        type=AgentEventType.RUN_STARTED,
    )
    ev3 = AgentEvent(
        event_id="e3",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=3,  # Gap: missing sequence 2
        type=AgentEventType.RUN_COMPLETED,
    )

    await spool.append_event(ev1)
    with pytest.raises(SequenceViolationError, match="Sequence gap"):
        await spool.append_event(ev3)


@pytest.mark.asyncio
async def test_event_spool_stream_events_sse(tmp_path: Path):
    spool = EventSpool(base_dir=tmp_path)
    run_id = "run-test-sse"

    ev1 = AgentEvent(
        event_id="e1",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=1,
        type=AgentEventType.RUN_STARTED,
    )
    ev2 = AgentEvent(
        event_id="e2",
        run_id=run_id,
        task_id="t1",
        task_attempt_id="att1",
        sequence=2,
        type=AgentEventType.RUN_COMPLETED,
    )

    await spool.append_event(ev1)
    await spool.append_event(ev2)

    lines = []
    async for chunk in spool.stream_events(run_id, after_sequence=0):
        lines.append(chunk)

    combined = "".join(lines)
    assert "event: agent_event" in combined
    assert '"sequence":1' in combined
    assert '"sequence":2' in combined
