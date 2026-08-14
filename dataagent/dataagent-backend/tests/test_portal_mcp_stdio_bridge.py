from __future__ import annotations

import asyncio
import io
import json
import sys
from pathlib import Path

import httpx
import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

PORTAL_MCP_ROOT = BACKEND_ROOT.parent / "portal-mcp"

from core import portal_mcp_stdio_bridge as bridge_module
from core.portal_mcp_stdio_bridge import PortalMcpBridge

URL = "http://portal-mcp:8801/mcp/"


class StdoutCapture:
    """Collects the frames the bridge writes, one parsed message per line."""

    def __init__(self) -> None:
        self.lines: list[str] = []

    def write(self, line: str) -> None:
        self.lines.append(line)

    @property
    def messages(self) -> list[dict]:
        return [json.loads(line) for line in self.lines]


@pytest.fixture
def stdout(monkeypatch) -> StdoutCapture:
    capture = StdoutCapture()
    monkeypatch.setattr(PortalMcpBridge, "_write_blocking", staticmethod(capture.write))
    return capture


def _build_bridge(handler, *, headers: dict[str, str] | None = None) -> PortalMcpBridge:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return PortalMcpBridge(client, URL, headers)


def _run(coro):
    return asyncio.run(coro)


def _skip_backoff(monkeypatch) -> None:
    """Keep retry tests instant without changing the retry policy under test."""
    real_sleep = asyncio.sleep
    monkeypatch.setattr(bridge_module.asyncio, "sleep", lambda _delay: real_sleep(0))


def test_forwards_request_and_writes_json_response(stdout: StdoutCapture):
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": 7, "result": {"tools": []}},
            headers={"content-type": "application/json"},
        )

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "id": 7, "method": "tools/list"})

    _run(scenario())

    assert seen == [{"jsonrpc": "2.0", "id": 7, "method": "tools/list"}]
    assert stdout.messages == [{"jsonrpc": "2.0", "id": 7, "result": {"tools": []}}]


def test_sends_frontdoor_headers_and_protocol_accept(stdout: StdoutCapture):
    seen: list[httpx.Headers] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers)
        return httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "result": {}})

    async def scenario():
        bridge = _build_bridge(
            handler,
            headers={"X-Portal-MCP-Token": "portal-token", "X-Agent-Data-Scope": "encoded-scope"},
        )
        await bridge.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize"})

    _run(scenario())

    assert seen[0]["x-portal-mcp-token"] == "portal-token"
    assert seen[0]["x-agent-data-scope"] == "encoded-scope"
    assert seen[0]["content-type"] == "application/json"
    assert seen[0]["accept"] == "application/json, text/event-stream"
    assert bridge_module.MCP_PROTOCOL_VERSION_HEADER.lower() not in seen[0]


def test_forwards_negotiated_protocol_version_after_initialize(stdout: StdoutCapture):
    seen: list[tuple[str, httpx.Headers]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        message = json.loads(request.content)
        method = str(message.get("method") or "")
        seen.append((method, request.headers))
        if method == "initialize":
            return httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": message["id"],
                    "result": {
                        "protocolVersion": "2025-06-18",
                        "capabilities": {},
                        "serverInfo": {"name": "portal-mcp", "version": "1.0.0"},
                    },
                },
            )
        if method == "notifications/initialized":
            return httpx.Response(202, content=b"")
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": message["id"], "result": {"tools": []}},
        )

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
        await bridge.handle_message({"jsonrpc": "2.0", "method": "notifications/initialized"})
        await bridge.handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})

    _run(scenario())

    protocol_header = bridge_module.MCP_PROTOCOL_VERSION_HEADER.lower()
    assert protocol_header not in seen[0][1]
    assert seen[1][1][protocol_header] == "2025-06-18"
    assert seen[2][1][protocol_header] == "2025-06-18"


def test_failed_reinitialize_clears_previous_protocol_version(stdout: StdoutCapture):
    seen: list[tuple[str, httpx.Headers]] = []
    initialize_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal initialize_count
        message = json.loads(request.content)
        method = str(message.get("method") or "")
        seen.append((method, request.headers))
        if method == "initialize":
            initialize_count += 1
            if initialize_count == 1:
                return httpx.Response(
                    200,
                    json={
                        "jsonrpc": "2.0",
                        "id": message["id"],
                        "result": {"protocolVersion": "2025-06-18"},
                    },
                )
            return httpx.Response(503, text="unavailable")
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": message["id"], "result": {"tools": []}},
        )

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
        await bridge.handle_message({"jsonrpc": "2.0", "id": 2, "method": "initialize"})
        await bridge.handle_message({"jsonrpc": "2.0", "id": 3, "method": "tools/list"})

    _run(scenario())

    protocol_header = bridge_module.MCP_PROTOCOL_VERSION_HEADER.lower()
    assert protocol_header not in seen[0][1]
    assert protocol_header not in seen[1][1]
    assert protocol_header not in seen[2][1]


def test_notification_produces_no_stdout_frame(stdout: StdoutCapture):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(202, content=b"", headers={"content-type": "application/json"})

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "method": "notifications/initialized"})

    _run(scenario())

    assert stdout.lines == []


def test_parses_sse_response_body(stdout: StdoutCapture):
    body = 'event: message\ndata: {"jsonrpc":"2.0","id":3,"result":{"ok":true}}\n\n'

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=body.encode(), headers={"content-type": "text/event-stream"})

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "id": 3, "method": "tools/call"})

    _run(scenario())

    assert stdout.messages == [{"jsonrpc": "2.0", "id": 3, "result": {"ok": True}}]


@pytest.mark.parametrize("status_code", [400, 401, 404, 503])
def test_http_error_becomes_tool_level_jsonrpc_error(stdout: StdoutCapture, status_code: int):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            status_code,
            json={"success": False, "message": "portal mcp 转发失败"},
        )

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "id": 9, "method": "tools/call"})

    _run(scenario())

    error = stdout.messages[0]["error"]
    assert error["code"] == bridge_module.INTERNAL_ERROR_CODE
    assert str(status_code) in error["message"]
    # Positive 404/session 400 and -32000 + "Connection closed" are all stale-session
    # signals in CLI 2.1.205; a forwarding failure must never reuse them.
    assert error["code"] not in {400, 404, -32000}
    assert "Connection closed" not in error["message"]


def test_retries_connect_errors_then_succeeds(stdout: StdoutCapture, monkeypatch):
    _skip_backoff(monkeypatch)
    attempts = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["count"] += 1
        if attempts["count"] == 1:
            raise httpx.ConnectError("connection refused", request=request)
        return httpx.Response(200, json={"jsonrpc": "2.0", "id": 4, "result": {"rows": []}})

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "id": 4, "method": "tools/call"})

    _run(scenario())

    assert attempts["count"] == 2
    assert stdout.messages == [{"jsonrpc": "2.0", "id": 4, "result": {"rows": []}}]


def test_read_timeout_is_not_retried(stdout: StdoutCapture, monkeypatch):
    _skip_backoff(monkeypatch)
    attempts = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["count"] += 1
        # The server may already have executed the call, so a write tool must not be
        # replayed. Only pre-send connect failures are retried.
        raise httpx.ReadTimeout("timed out", request=request)

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "id": 5, "method": "tools/call"})

    _run(scenario())

    assert attempts["count"] == 1
    assert stdout.messages[0]["error"]["code"] == bridge_module.INTERNAL_ERROR_CODE


def test_exhausted_connect_retries_report_error_once(stdout: StdoutCapture, monkeypatch):
    _skip_backoff(monkeypatch)
    attempts = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["count"] += 1
        raise httpx.ConnectError("connection refused", request=request)

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.handle_message({"jsonrpc": "2.0", "id": 6, "method": "tools/call"})

    _run(scenario())

    assert attempts["count"] == len(bridge_module.CONNECT_RETRY_BACKOFF_SECONDS) + 1
    assert len(stdout.messages) == 1
    assert stdout.messages[0]["id"] == 6


def test_run_loop_answers_every_concurrent_request(stdout: StdoutCapture):
    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        # Answer out of arrival order so the test also covers interleaved completion.
        await asyncio.sleep(0.01 * (3 - payload["id"]))
        return httpx.Response(200, json={"jsonrpc": "2.0", "id": payload["id"], "result": {}})

    frames = "".join(
        json.dumps({"jsonrpc": "2.0", "id": index, "method": "tools/call"}) + "\n"
        for index in (1, 2, 3)
    )

    async def scenario():
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        bridge = PortalMcpBridge(client, URL)
        await bridge.run(io.StringIO(frames))

    _run(scenario())

    assert sorted(message["id"] for message in stdout.messages) == [1, 2, 3]


def test_run_loop_skips_malformed_frames(stdout: StdoutCapture):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "result": {}})

    async def scenario():
        bridge = _build_bridge(handler)
        await bridge.run(io.StringIO('not json\n\n{"jsonrpc":"2.0","id":1,"method":"tools/list"}\n'))

    _run(scenario())

    assert stdout.messages == [{"jsonrpc": "2.0", "id": 1, "result": {}}]


@pytest.mark.skipif(not (PORTAL_MCP_ROOT / "portal_mcp").is_dir(), reason="portal-mcp source not present")
def test_end_to_end_against_real_portal_mcp_app(stdout: StdoutCapture, monkeypatch):
    """Drive initialize → tools/list through the bridge against the real server app.

    Guards the contract the bridge actually depends on (stateless Streamable HTTP,
    JSON response bodies, no Mcp-Session-Id) instead of only asserting against mocks.
    """
    pytest.importorskip("mcp", reason="mcp SDK not installed")
    if str(PORTAL_MCP_ROOT) not in sys.path:
        sys.path.insert(0, str(PORTAL_MCP_ROOT))

    monkeypatch.setenv("PORTAL_MCP_FRONTDOOR_TOKEN", "portal-token")
    monkeypatch.setenv("PORTAL_MCP_BACKEND_SERVICE_TOKEN", "backend-token")
    from portal_mcp.app import create_app

    app = create_app()
    seen_headers: list[dict[str, str]] = []

    async def capture_headers(scope, receive, send):
        if scope["type"] == "http":
            seen_headers.append(
                {
                    key.decode("latin-1").lower(): value.decode("latin-1")
                    for key, value in scope.get("headers") or []
                }
            )
        await app(scope, receive, send)

    async def scenario():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=capture_headers), base_url="http://portal-mcp:8801"
        ) as client:
            # The Starlette lifespan owns the MCP session manager; run it for the call.
            async with app.router.lifespan_context(app):
                bridge = PortalMcpBridge(client, "/mcp/", {"X-Portal-MCP-Token": "portal-token"})
                await bridge.handle_message(
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {
                            "protocolVersion": "2025-06-18",
                            "capabilities": {},
                            "clientInfo": {"name": "bridge-test", "version": "1.0.0"},
                        },
                    }
                )
                await bridge.handle_message({"jsonrpc": "2.0", "method": "notifications/initialized"})
                await bridge.handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})

    _run(scenario())

    messages = stdout.messages
    assert messages[0]["id"] == 1 and "result" in messages[0]
    negotiated_version = messages[0]["result"]["protocolVersion"]
    protocol_header = bridge_module.MCP_PROTOCOL_VERSION_HEADER.lower()
    assert protocol_header not in seen_headers[0]
    assert seen_headers[1][protocol_header] == negotiated_version
    assert seen_headers[2][protocol_header] == negotiated_version
    tool_names = {tool["name"] for tool in messages[-1]["result"]["tools"]}
    assert "portal_query_readonly" in tool_names
