from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any, AsyncIterator, Dict, List, Optional, Set

from core.agent_runtime.contracts import AgentEvent, AgentEventType

logger = logging.getLogger(__name__)

TERMINAL_EVENT_TYPES: Set[AgentEventType] = {
    AgentEventType.RUN_COMPLETED,
    AgentEventType.RUN_FAILED,
    AgentEventType.RUN_CANCELLED,
    AgentEventType.RUN_SUSPENDED,
}


class SequenceViolationError(Exception):
    pass


class EventSpool:
    """
    Durable append-only event spool for AgentEvents.
    Provides fsync-before-publish guarantees, monotonic sequence validation,
    in-memory live subscription, and SSE streaming with replay.
    """

    def __init__(self, base_dir: str | Path = "/tmp/dataagent/spool"):
        self.base_dir = Path(base_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)
        self._locks: Dict[str, asyncio.Lock] = {}
        self._subscribers: Dict[str, Set[asyncio.Queue[AgentEvent]]] = {}
        self._last_sequences: Dict[str, int] = {}
        self._terminal_reached: Dict[str, bool] = {}

    def _get_lock(self, run_id: str) -> asyncio.Lock:
        if run_id not in self._locks:
            self._locks[run_id] = asyncio.Lock()
        return self._locks[run_id]

    def _get_run_path(self, run_id: str) -> Path:
        run_dir = self.base_dir / run_id
        run_dir.mkdir(parents=True, exist_ok=True)
        return run_dir / "events.jsonl"

    async def append_event(self, event: AgentEvent) -> AgentEvent:
        """
        Append an AgentEvent to disk with fsync before notifying subscribers.
        Validates contiguous monotonic sequence.
        """
        run_id = event.run_id
        lock = self._get_lock(run_id)

        async with lock:
            last_seq = self._last_sequences.get(run_id, 0)
            if last_seq == 0:
                # Check on-disk history to initialize sequence if restarted
                existing = self.read_events(run_id)
                if existing:
                    last_seq = existing[-1].sequence
                    self._last_sequences[run_id] = last_seq

            expected_seq = last_seq + 1
            if event.sequence < expected_seq:
                # Duplicate / replayed event - skip append if identical
                logger.warning(
                    "Duplicate event sequence received for run %s: got %s, expected %s",
                    run_id,
                    event.sequence,
                    expected_seq,
                )
                return event

            if event.sequence > expected_seq:
                msg = f"Sequence gap for run {run_id}: expected {expected_seq}, got {event.sequence}"
                logger.error(msg)
                raise SequenceViolationError(msg)

            # Prepare json line and compute sha256 checksum
            event_dict = event.model_dump(mode="json")
            raw_line = json.dumps(event_dict, separators=(",", ":"))
            checksum = hashlib.sha256(raw_line.encode("utf-8")).hexdigest()
            record = {
                "sequence": event.sequence,
                "checksum": checksum,
                "event": event_dict,
            }
            record_line = json.dumps(record, separators=(",", ":")) + "\n"

            file_path = self._get_run_path(run_id)
            # Write to disk and fsync
            with open(file_path, "a", encoding="utf-8") as f:
                f.write(record_line)
                f.flush()
                os.fsync(f.fileno())

            self._last_sequences[run_id] = event.sequence
            if event.type in TERMINAL_EVENT_TYPES:
                self._terminal_reached[run_id] = True

            # Notify in-memory live subscribers
            queues = self._subscribers.get(run_id, set())
            for q in list(queues):
                await q.put(event)

            return event

    def read_events(self, run_id: str, after_sequence: int = 0) -> List[AgentEvent]:
        """
        Read all persisted events for a run with sequence > after_sequence.
        """
        file_path = self._get_run_path(run_id)
        if not file_path.exists():
            return []

        events: List[AgentEvent] = []
        with open(file_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                    raw_event = record.get("event", record)
                    ev = AgentEvent.model_validate(raw_event)
                    if ev.sequence > after_sequence:
                        events.append(ev)
                except Exception as e:
                    logger.error("Failed to parse event record in %s: %s", file_path, e)
        return events

    def is_terminal(self, run_id: str) -> bool:
        if self._terminal_reached.get(run_id):
            return True
        events = self.read_events(run_id)
        if events and events[-1].type in TERMINAL_EVENT_TYPES:
            self._terminal_reached[run_id] = True
            return True
        return False

    async def stream_events(
        self,
        run_id: str,
        after_sequence: int = 0,
        heartbeat_interval: float = 15.0,
    ) -> AsyncIterator[str]:
        """
        SSE stream generator:
        1. Replays historical events after_sequence.
        2. Subscribes to live events until terminal event.
        3. Sends SSE comments as heartbeats to prevent proxy timeout.
        """
        q: asyncio.Queue[AgentEvent] = asyncio.Queue()
        if run_id not in self._subscribers:
            self._subscribers[run_id] = set()
        self._subscribers[run_id].add(q)

        current_seq = after_sequence
        try:
            # 1. Replay historical events
            historical = self.read_events(run_id, after_sequence=current_seq)
            for ev in historical:
                current_seq = max(current_seq, ev.sequence)
                yield f"id: {ev.sequence}\nevent: agent_event\ndata: {json.dumps(ev.model_dump(mode='json'), separators=(',', ':'))}\n\n"
                if ev.type in TERMINAL_EVENT_TYPES:
                    return

            # If already terminal after historical playback, exit
            if self.is_terminal(run_id):
                return

            # 2. Consume live events
            while True:
                try:
                    ev = await asyncio.wait_for(q.get(), timeout=heartbeat_interval)
                    if ev.sequence > current_seq:
                        current_seq = ev.sequence
                        yield f"id: {ev.sequence}\nevent: agent_event\ndata: {json.dumps(ev.model_dump(mode='json'), separators=(',', ':'))}\n\n"
                        if ev.type in TERMINAL_EVENT_TYPES:
                            break
                except asyncio.TimeoutError:
                    # Keepalive comment
                    yield ": keepalive\n\n"
        finally:
            if run_id in self._subscribers and q in self._subscribers[run_id]:
                self._subscribers[run_id].discard(q)
                if not self._subscribers[run_id]:
                    del self._subscribers[run_id]
