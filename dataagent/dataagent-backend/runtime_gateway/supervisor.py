from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
import subprocess
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from core.agent_runtime.contracts import (
    AgentEvent,
    AgentEventType,
    AgentRunRequest,
    CellProtocolFrame,
)
from runtime_gateway.cell_protocol import make_frame, read_frame, write_frame
from runtime_gateway.event_spool import EventSpool

logger = logging.getLogger(__name__)


@dataclass
class CellInstance:
    cell_id: str
    process: asyncio.subprocess.Process
    topic_id: Optional[str] = None
    current_run_id: Optional[str] = None
    current_attempt_id: Optional[str] = None
    manifest: Optional[Dict[str, Any]] = None
    is_ready: bool = False
    stdout_task: Optional[asyncio.Task[None]] = None
    stderr_task: Optional[asyncio.Task[None]] = None
    created_at: float = field(default_factory=lambda: asyncio.get_event_loop().time())
    last_active_at: float = field(default_factory=lambda: asyncio.get_event_loop().time())


class CellSupervisor:
    """
    Supervisor managing the lifecycle, stdio channel, and command routing
    for runtime cell child processes.
    """

    def __init__(
        self,
        event_spool: EventSpool,
        cell_command: Optional[List[str]] = None,
        node_bin: Optional[str] = None,
        runtime_pi_dir: Optional[str] = None,
    ):
        self.spool = event_spool
        self.cells: Dict[str, CellInstance] = {}
        self.run_to_cell: Dict[str, str] = {}
        self._lock = asyncio.Lock()

        # Locate default Node runtime cell entrypoint
        if cell_command:
            self.cell_command = cell_command
        else:
            base_dir = Path(__file__).resolve().parent.parent
            pi_dir = Path(runtime_pi_dir or os.environ.get("DATAAGENT_RUNTIME_PI_DIR", str(base_dir.parent / "dataagent-runtime-pi")))
            main_js = pi_dir / "dist" / "src" / "main.js"
            node = node_bin or os.environ.get("DATAAGENT_NODE_BIN", shutil.which("node") or "node")
            self.cell_command = [str(node), str(main_js)]

    async def _spawn_cell(self, cell_id: Optional[str] = None, topic_id: Optional[str] = None) -> CellInstance:
        cid = cell_id or f"cell-{uuid.uuid4().hex[:8]}"
        logger.info("Spawning Cell process [%s] with command: %s", cid, self.cell_command)

        proc = await asyncio.create_subprocess_exec(
            *self.cell_command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

        cell = CellInstance(cell_id=cid, process=proc, topic_id=topic_id)
        self.cells[cid] = cell

        # Launch background readers for stdout and stderr
        cell.stdout_task = asyncio.create_task(self._read_cell_stdout(cell), name=f"cell-stdout-{cid}")
        cell.stderr_task = asyncio.create_task(self._read_cell_stderr(cell), name=f"cell-stderr-{cid}")

        # Send hello handshake
        hello_frame = make_frame(
            cell_id=cid,
            run_id="system",
            task_attempt_id="system",
            frame_type="hello",
            payload={"version": "1.0.0"},
        )
        assert proc.stdin is not None
        await write_frame(proc.stdin, hello_frame)

        # Wait up to 5s for hello.ack
        for _ in range(50):
            if cell.is_ready:
                break
            await asyncio.sleep(0.1)

        if not cell.is_ready:
            logger.warning("Cell [%s] spawned but did not complete handshake within 5s", cid)

        return cell

    async def _read_cell_stderr(self, cell: CellInstance) -> None:
        """Log stderr output from the child process without corrupting protocol."""
        assert cell.process.stderr is not None
        try:
            while not cell.process.stderr.at_eof():
                line = await cell.process.stderr.readline()
                if line:
                    msg = line.decode("utf-8", errors="replace").rstrip()
                    logger.info("[Cell %s stderr] %s", cell.cell_id, msg)
        except Exception as e:
            logger.error("Error reading stderr for cell %s: %s", cell.cell_id, e)

    async def _read_cell_stdout(self, cell: CellInstance) -> None:
        """Read NDJSON protocol frames from child stdout and process them."""
        assert cell.process.stdout is not None
        try:
            while not cell.process.stdout.at_eof():
                frame = await read_frame(cell.process.stdout)
                if frame is None:
                    break

                await self._handle_incoming_frame(cell, frame)
        except Exception as e:
            logger.error("Error on cell %s stdout channel: %s", cell.cell_id, e)
        finally:
            await self._on_cell_terminated(cell)

    async def _handle_incoming_frame(self, cell: CellInstance, frame: CellProtocolFrame) -> None:
        ftype = frame.type
        cell.last_active_at = asyncio.get_event_loop().time()

        if ftype == "hello.ack":
            cell.is_ready = True
            cell.manifest = frame.payload.get("manifest")
            logger.info("Cell [%s] ready. Manifest received: %s", cell.cell_id, cell.manifest)

        elif ftype == "run.accepted":
            logger.info("Cell [%s] accepted run [%s]", cell.cell_id, frame.run_id)

        elif ftype == "run.event":
            raw_event = frame.payload.get("event")
            if raw_event:
                try:
                    event = AgentEvent.model_validate(raw_event)
                    await self.spool.append_event(event)
                except Exception as e:
                    logger.error("Failed to validate/append AgentEvent for run %s: %s", frame.run_id, e)

        elif ftype == "run.settled":
            logger.info("Cell [%s] run [%s] settled with status %s", cell.cell_id, frame.run_id, frame.payload.get("status"))
            cell.current_run_id = None
            cell.current_attempt_id = None
            self.run_to_cell.pop(frame.run_id, None)

        elif ftype == "protocol.error":
            logger.error("Cell [%s] protocol error: %s", cell.cell_id, frame.payload)

        elif ftype == "run.heartbeat":
            logger.debug("Cell [%s] heartbeat for run %s", cell.cell_id, frame.run_id)

    async def _on_cell_terminated(self, cell: CellInstance) -> None:
        logger.warning("Cell [%s] process exited.", cell.cell_id)
        async with self._lock:
            active_run = cell.current_run_id
            if active_run and not self.spool.is_terminal(active_run):
                # Synthesize terminal run.failed event for active run if cell crashed mid-run
                last_events = self.spool.read_events(active_run)
                next_seq = (last_events[-1].sequence + 1) if last_events else 1
                failure_event = AgentEvent(
                    event_id=f"ev-loss-{uuid.uuid4().hex[:8]}",
                    run_id=active_run,
                    task_id=active_run,
                    task_attempt_id=cell.current_attempt_id or "attempt-0",
                    sequence=next_seq,
                    type=AgentEventType.RUN_FAILED,
                    payload={
                        "error_code": "CELL_LOSS",
                        "message": f"Cell process {cell.cell_id} terminated unexpectedly",
                    },
                )
                await self.spool.append_event(failure_event)

            self.cells.pop(cell.cell_id, None)
            if active_run:
                self.run_to_cell.pop(active_run, None)

    async def start_run(self, request: AgentRunRequest) -> Tuple[str, str]:
        """
        Start an execution run on an idle or newly spawned Cell.
        Guarantees: one active run per Cell; (run_id, task_attempt_id) idempotency.
        Returns (run_id, cell_id).
        """
        async with self._lock:
            # 1. Idempotency check: if already running
            if request.run_id in self.run_to_cell:
                cid = self.run_to_cell[request.run_id]
                cell = self.cells.get(cid)
                if cell and cell.current_run_id == request.run_id:
                    logger.info("Run %s is already active on cell %s, returning existing handle", request.run_id, cid)
                    return request.run_id, cid

            # 2. Find warm idle cell with matching topic_id, or any idle cell
            target_cell: Optional[CellInstance] = None
            for cell in self.cells.values():
                if cell.current_run_id is None and cell.is_ready:
                    if cell.topic_id == request.topic_id:
                        target_cell = cell
                        break
                    elif target_cell is None:
                        target_cell = cell

            # 3. If no idle cell, spawn a new one
            if target_cell is None:
                target_cell = await self._spawn_cell(topic_id=request.topic_id)

            target_cell.current_run_id = request.run_id
            target_cell.current_attempt_id = request.task_attempt_id
            target_cell.topic_id = request.topic_id
            self.run_to_cell[request.run_id] = target_cell.cell_id

            # 4. Send run.start frame
            start_frame = make_frame(
                cell_id=target_cell.cell_id,
                run_id=request.run_id,
                task_attempt_id=request.task_attempt_id,
                frame_type="run.start",
                payload={"request": request.model_dump(mode="json")},
            )
            assert target_cell.process.stdin is not None
            await write_frame(target_cell.process.stdin, start_frame)

            return request.run_id, target_cell.cell_id

    async def resolve_interaction(self, run_id: str, interaction_id: str, resolution: Dict[str, Any]) -> bool:
        async with self._lock:
            cid = self.run_to_cell.get(run_id)
            if not cid or cid not in self.cells:
                logger.warning("Cannot resolve interaction %s: run %s not found on any active cell", interaction_id, run_id)
                return False

            cell = self.cells[cid]
            frame = make_frame(
                cell_id=cell.cell_id,
                run_id=run_id,
                task_attempt_id=cell.current_attempt_id or "",
                frame_type="interaction.resolve",
                payload={"interaction_id": interaction_id, "resolution": resolution},
            )
            assert cell.process.stdin is not None
            await write_frame(cell.process.stdin, frame)
            return True

    async def cancel_run(self, run_id: str, reason: str = "User cancelled") -> bool:
        async with self._lock:
            cid = self.run_to_cell.get(run_id)
            if not cid or cid not in self.cells:
                logger.warning("Cannot cancel run %s: not found on active cell", run_id)
                return False

            cell = self.cells[cid]
            frame = make_frame(
                cell_id=cell.cell_id,
                run_id=run_id,
                task_attempt_id=cell.current_attempt_id or "",
                frame_type="run.cancel",
                payload={"reason": reason},
            )
            assert cell.process.stdin is not None
            await write_frame(cell.process.stdin, frame)
            return True

    async def steer_run(self, run_id: str, instructions: str) -> bool:
        async with self._lock:
            cid = self.run_to_cell.get(run_id)
            if not cid or cid not in self.cells:
                return False
            cell = self.cells[cid]
            frame = make_frame(
                cell_id=cell.cell_id,
                run_id=run_id,
                task_attempt_id=cell.current_attempt_id or "",
                frame_type="run.steer",
                payload={"instructions": instructions},
            )
            assert cell.process.stdin is not None
            await write_frame(cell.process.stdin, frame)
            return True

    async def shutdown(self) -> None:
        async with self._lock:
            for cell in list(self.cells.values()):
                try:
                    if cell.process.stdin and not cell.process.stdin.is_closing():
                        shutdown_frame = make_frame(
                            cell_id=cell.cell_id,
                            run_id="system",
                            task_attempt_id="system",
                            frame_type="cell.shutdown",
                            payload={},
                        )
                        await write_frame(cell.process.stdin, shutdown_frame)
                    cell.process.terminate()
                except Exception as e:
                    logger.warning("Error shutting down cell %s: %s", cell.cell_id, e)
            self.cells.clear()
            self.run_to_cell.clear()
