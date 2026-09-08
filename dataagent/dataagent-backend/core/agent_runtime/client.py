from __future__ import annotations

import json
import logging
import os
from typing import Any, AsyncIterator, Dict, Optional, Tuple

import httpx

from core.agent_runtime.contracts import (
    AgentEvent,
    AgentRunRequest,
    RuntimeManifest,
)
from core.agent_runtime.security import sign_capability_token

logger = logging.getLogger(__name__)


class RuntimePlaneError(Exception):
    pass


class RuntimePlaneClient:
    """
    Client connecting Python Control Plane to Runtime Gateway.
    """

    def __init__(
        self,
        gateway_url: Optional[str] = None,
        secret_key: Optional[str] = None,
        timeout: float = 30.0,
    ):
        self.gateway_url = (
            gateway_url
            or os.environ.get("DATAAGENT_GATEWAY_URL")
            or "http://127.0.0.1:8002"
        ).rstrip("/")
        self.secret_key = (
            secret_key
            or os.environ.get("DATAAGENT_RUNTIME_SECRET")
            or "dataagent-default-insecure-secret"
        )
        self.timeout = timeout
        self._http_client: Optional[httpx.AsyncClient] = None

    async def get_http_client(self) -> httpx.AsyncClient:
        if self._http_client is None or self._http_client.is_closed:
            self._http_client = httpx.AsyncClient(
                base_url=self.gateway_url,
                timeout=httpx.Timeout(self.timeout, read=None),
            )
        return self._http_client

    async def close(self) -> None:
        if self._http_client and not self._http_client.is_closed:
            await self._http_client.aclose()

    async def check_health(self) -> bool:
        client = await self.get_http_client()
        try:
            resp = await client.get("/health", timeout=5.0)
            return resp.status_code == 200 and resp.json().get("status") == "ok"
        except Exception as e:
            logger.warning("Gateway health check failed: %s", e)
            return False

    async def get_manifest(self) -> RuntimeManifest:
        client = await self.get_http_client()
        resp = await client.get("/manifest")
        resp.raise_for_status()
        return RuntimeManifest.model_validate(resp.json())

    async def start_run(self, request: AgentRunRequest) -> Tuple[str, str]:
        """
        Signs a capability token and requests the Gateway to start the run.
        Returns (run_id, cell_id).
        """
        token = sign_capability_token(
            run_id=request.run_id,
            task_attempt_id=request.task_attempt_id,
            topic_id=request.topic_id,
            purpose=request.purpose,
            secret_key=self.secret_key,
        )

        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        }

        client = await self.get_http_client()
        resp = await client.post(
            "/runs",
            headers=headers,
            json=request.model_dump(mode="json"),
        )
        if resp.status_code != 200:
            raise RuntimePlaneError(f"Failed to start run ({resp.status_code}): {resp.text}")

        data = resp.json()
        return data["run_id"], data["cell_id"]

    async def stream_events(
        self,
        run_id: str,
        after_sequence: int = 0,
    ) -> AsyncIterator[AgentEvent]:
        """
        Stream SSE events from the Gateway event spool.
        """
        client = await self.get_http_client()
        url = f"/runs/{run_id}/events?after_sequence={after_sequence}"

        async with client.stream("GET", url) as response:
            if response.status_code != 200:
                body = await response.aread()
                raise RuntimePlaneError(f"Failed to stream events ({response.status_code}): {body.decode('utf-8')}")

            async for line in response.aiter_lines():
                line = line.strip()
                if not line or line.startswith(":"):
                    continue  # heartbeat comment or empty line
                if line.startswith("data: "):
                    raw_data = line[6:].strip()
                    try:
                        event_dict = json.loads(raw_data)
                        yield AgentEvent.model_validate(event_dict)
                    except Exception as e:
                        logger.error("Failed to parse AgentEvent from SSE: %s (line: %s)", e, raw_data)

    async def resolve_interaction(
        self,
        run_id: str,
        interaction_id: str,
        resolution: Dict[str, Any],
    ) -> bool:
        client = await self.get_http_client()
        resp = await client.post(
            f"/runs/{run_id}/interactions/{interaction_id}/resolve",
            json={"resolution": resolution},
        )
        return resp.status_code == 200

    async def cancel_run(self, run_id: str, reason: str = "User cancelled") -> bool:
        client = await self.get_http_client()
        resp = await client.post(
            f"/runs/{run_id}/cancel",
            json={"reason": reason},
        )
        return resp.status_code == 200

    async def steer_run(self, run_id: str, instructions: str) -> bool:
        client = await self.get_http_client()
        resp = await client.post(
            f"/runs/{run_id}/steer",
            json={"instructions": instructions},
        )
        return resp.status_code == 200
