"""In-process cache of per-agent SDK slash commands.

The Claude Agent SDK reports the authoritative slash-command list (built-ins,
skills, and custom commands) in the ``system/init`` message at the start of each
run. We cache it per agent so the chat input's slash menu can surface the real
commands without re-running the agent.

The API server and the task coordinator share a single process (the coordinator
is started inside ``main.py``), so a module-level dict is visible to both the
executor that populates it and the route that reads it. The cache is best-effort:
it is empty after a restart and is repopulated by the next run; callers fall back
to the agent's enabled skill folders until then.
"""

from __future__ import annotations

_AGENT_SLASH_COMMANDS: dict[str, list[str]] = {}


def record_agent_slash_commands(agent_id: str, commands: list[str] | None) -> None:
    """Store the SDK-reported slash commands for an agent (no-op without an id)."""
    agent_key = str(agent_id or "").strip()
    if not agent_key:
        return
    cleaned = [str(item).strip() for item in (commands or []) if str(item or "").strip()]
    _AGENT_SLASH_COMMANDS[agent_key] = cleaned


def get_agent_slash_commands(agent_id: str) -> list[str] | None:
    """Return the cached slash commands for an agent, or ``None`` if unknown."""
    agent_key = str(agent_id or "").strip()
    if not agent_key:
        return None
    cached = _AGENT_SLASH_COMMANDS.get(agent_key)
    return list(cached) if cached is not None else None
