from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Dict, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, status
from fastapi.responses import JSONResponse, StreamingResponse

from core.agent_runtime.contracts import (
    AgentRunRequest,
    RuntimeFeatures,
    RuntimeManifest,
)
from runtime_gateway.capability import authenticate_capability_token
from runtime_gateway.event_spool import EventSpool
from runtime_gateway.supervisor import CellSupervisor

logger = logging.getLogger(__name__)

event_spool = EventSpool(base_dir=os.environ.get("DATAAGENT_SPOOL_DIR", "/tmp/dataagent/spool"))
supervisor = CellSupervisor(event_spool=event_spool)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    logger.info("Starting Runtime Gateway...")
    yield
    logger.info("Shutting down Runtime Gateway cells...")
    await supervisor.shutdown()


app = FastAPI(
    title="DataAgent Runtime Gateway",
    description="Agent execution plane runtime gateway and supervisor",
    version="1.0.0",
    lifespan=lifespan,
)


def verify_auth_header(
    run_id: Optional[str] = None,
    task_attempt_id: Optional[str] = None,
    authorization: Optional[str] = Header(None),
) -> None:
    """Optional capability verification if Authorization header is provided."""
    if not authorization or not authorization.startswith("Bearer "):
        # In non-enforcing mode or when capability header is omitted, allow pass-through
        return
    token = authorization[7:].strip()
    if run_id and task_attempt_id:
        try:
            authenticate_capability_token(token, run_id, task_attempt_id)
        except Exception as e:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=f"Capability verification failed: {e}",
            )


@app.get("/health")
async def health() -> Dict[str, Any]:
    return {
        "status": "ok",
        "runtime_kind": "pi_agent_core",
        "active_cells": len(supervisor.cells),
        "active_runs": len(supervisor.run_to_cell),
    }


@app.get("/manifest", response_model=RuntimeManifest)
async def get_manifest() -> RuntimeManifest:
    return RuntimeManifest(
        runtime_kind="pi_agent_core",
        runtime_version="0.1.0",
        pi_agent_core_version="0.85.1",
        pi_ai_version="0.85.1",
        node_version="22.19.0",
        runtime_protocol_versions=[1],
        agent_event_protocol_versions=[1],
        context_renderer_versions=[1],
        providers=["anthropic", "openai", "bedrock", "ollama", "mock"],
        features=RuntimeFeatures(),
        limits={"max_timeout_seconds": 600, "max_turns": 50},
        artifact_digest="sha256:official-pi-agent-kernel",
    )


@app.post("/runs")
async def start_run(
    request: AgentRunRequest,
    authorization: Optional[str] = Header(None),
) -> Dict[str, Any]:
    verify_auth_header(request.run_id, request.task_attempt_id, authorization)
    try:
        run_id, cell_id = await supervisor.start_run(request)
        return {
            "run_id": run_id,
            "cell_id": cell_id,
            "status": "accepted",
        }
    except Exception as e:
        logger.error("Failed to start run %s: %s", request.run_id, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to start run: {e}",
        )


@app.get("/runs/{run_id}/events")
async def stream_run_events(
    run_id: str,
    after_sequence: int = Query(0, ge=0),
) -> StreamingResponse:
    """
    Server-Sent Events endpoint streaming AgentEvents with replay from after_sequence.
    """
    stream = event_spool.stream_events(run_id, after_sequence=after_sequence)
    return StreamingResponse(
        stream,
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.post("/runs/{run_id}/interactions/{interaction_id}/resolve")
async def resolve_interaction(
    run_id: str,
    interaction_id: str,
    body: Dict[str, Any],
) -> Dict[str, Any]:
    resolution = body.get("resolution", body)
    resolved = await supervisor.resolve_interaction(run_id, interaction_id, resolution)
    if not resolved:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Run {run_id} not found on any active cell",
        )
    return {"status": "ok", "interaction_id": interaction_id}


@app.post("/runs/{run_id}/cancel")
async def cancel_run(
    run_id: str,
    body: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    reason = (body or {}).get("reason", "User cancelled")
    cancelled = await supervisor.cancel_run(run_id, reason=reason)
    return {"status": "ok", "cancelled": cancelled}


@app.post("/runs/{run_id}/steer")
async def steer_run(
    run_id: str,
    body: Dict[str, Any],
) -> Dict[str, Any]:
    instructions = body.get("instructions", "")
    steered = await supervisor.steer_run(run_id, instructions=instructions)
    return {"status": "ok", "steered": steered}
