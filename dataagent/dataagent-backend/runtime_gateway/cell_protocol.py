from __future__ import annotations

import asyncio
import json
import logging
import uuid
from typing import Any, Dict, Optional

from core.agent_runtime.contracts import CellProtocolFrame

logger = logging.getLogger(__name__)


def make_frame(
    cell_id: str,
    run_id: str,
    task_attempt_id: str,
    frame_type: str,
    payload: Optional[Dict[str, Any]] = None,
    protocol_version: int = 1,
) -> CellProtocolFrame:
    return CellProtocolFrame(
        protocol_version=protocol_version,
        cell_id=cell_id,
        run_id=run_id,
        task_attempt_id=task_attempt_id,
        frame_id=f"frame-{uuid.uuid4().hex[:12]}",
        type=frame_type,
        payload=payload or {},
    )


async def read_frame(reader: asyncio.StreamReader) -> Optional[CellProtocolFrame]:
    """
    Read a single NDJSON line from the child stdout and parse into a CellProtocolFrame.
    Returns None on EOF.
    Raises ValueError or json.JSONDecodeError if malformed or non-JSON.
    """
    line_bytes = await reader.readline()
    if not line_bytes:
        return None  # EOF
        
    line = line_bytes.decode("utf-8", errors="replace").strip()
    if not line:
        return None
        
    try:
        data = json.loads(line)
        return CellProtocolFrame.model_validate(data)
    except Exception as e:
        logger.error("Failed to parse CellProtocolFrame from child line: %s (error: %s)", line, e)
        raise ValueError(f"Invalid frame format: {e}") from e


async def write_frame(writer: asyncio.StreamWriter, frame: CellProtocolFrame | Dict[str, Any]) -> None:
    """
    Write a single CellProtocolFrame as NDJSON to child stdin.
    """
    if isinstance(frame, CellProtocolFrame):
        raw_dict = frame.model_dump(mode="json")
    else:
        raw_dict = frame
        
    line = json.dumps(raw_dict, separators=(",", ":")) + "\n"
    writer.write(line.encode("utf-8"))
    await writer.drain()
