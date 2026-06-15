"""Session permission gating for tool calls.

Skill-agnostic policy shared by the runtime (``_build_allowed_tools`` strips
write tools under ``plan``) and the ``can_use_tool`` confirmation callback
(``default``/``acceptEdits`` route write/high-risk tools through user
confirmation). The high-risk and write tool sets are the single source of
truth; any agent or MCP server can register tools here without the runtime
learning their business meaning.

Tool names are matched against both the bare MCP tool name (e.g.
``portal_publish_workflow``) and the SDK-qualified form
(``mcp__portal__portal_publish_workflow``).
"""
from __future__ import annotations

from typing import Any

from core.agent_profile_service import normalize_permission_mode

# High-risk tools always require confirmation in default/acceptEdits and are
# denied under plan. These mutate deployed/production state.
HIGH_RISK_TOOL_NAMES: frozenset[str] = frozenset(
    {
        "portal_publish_workflow",
        "portal_workflow_schedule_online",
    }
)

# Draft-level write tools: confirmed under default, auto-allowed under
# acceptEdits, denied under plan.
DRAFT_WRITE_TOOL_NAMES: frozenset[str] = frozenset(
    {
        "portal_create_task",
        "portal_update_task",
        "portal_create_workflow",
        "portal_update_workflow",
        "portal_upsert_schedule",
        "portal_workflow_schedule_offline",
    }
)

WRITE_TOOL_NAMES: frozenset[str] = HIGH_RISK_TOOL_NAMES | DRAFT_WRITE_TOOL_NAMES

# Confirmation-card annotation keys the skill attaches to a write tool call so the
# generic gate can render a meaningful card (title/diff summary). They are gate
# metadata, not part of any downstream tool schema — the portal MCP write tools
# use ``extra="forbid"`` and would reject the call after approval if these leaked
# through. The gate consumes them for the card and strips them before forwarding.
CARD_ANNOTATION_KEYS: frozenset[str] = frozenset({"title", "summary"})


def strip_card_annotations(tool_input: dict[str, Any]) -> dict[str, Any]:
    """Drop confirmation-card annotation keys from a tool input before it is
    forwarded to the underlying tool."""
    return {k: v for k, v in tool_input.items() if k not in CARD_ANNOTATION_KEYS}


# Canonical permission-decision values persisted in SDK records and surfaced to
# clients. The request side accepts the bare ``allow``/``deny`` verbs; both the
# stored record and the API response use these normalized forms so the immediate
# response and the later streamed/reloaded record never diverge.
_PERMISSION_DECISION_NORMALIZATION = {
    "allow": "allowed",
    "allowed": "allowed",
    "deny": "denied",
    "denied": "denied",
    "timeout": "timeout",
}


def normalize_permission_decision(decision: Any) -> str:
    """Coerce a permission decision to its canonical persisted form.

    ``allow`` -> ``allowed``, ``deny`` -> ``denied``, ``timeout`` stays; anything
    unknown collapses to ``denied`` (fail-closed)."""
    return _PERMISSION_DECISION_NORMALIZATION.get(str(decision or "").strip().lower(), "denied")


def permission_decision_redis_key(task_id: str, request_id: str) -> str:
    """Redis key carrying a user's permission decision for a waiting run.

    Single source of truth shared by the coordinator (writer) and the executor's
    confirmation wait (reader), mirroring the cancel-flag key pattern.
    """
    return f"da:task:permission:{task_id}:{request_id}"


def _bare_tool_name(tool_name: str) -> str:
    """Reduce an SDK-qualified MCP tool name to its bare tool name.

    ``mcp__portal__portal_publish_workflow`` -> ``portal_publish_workflow``.
    """
    name = str(tool_name or "").strip()
    if name.startswith("mcp__"):
        parts = name.split("__")
        if parts:
            return parts[-1]
    return name


def is_write_tool(tool_name: str) -> bool:
    return _bare_tool_name(tool_name) in WRITE_TOOL_NAMES


def is_high_risk_tool(tool_name: str) -> bool:
    return _bare_tool_name(tool_name) in HIGH_RISK_TOOL_NAMES


def plan_denies_tool(tool_name: str) -> bool:
    """Under ``plan`` no write tool may run (defense in depth alongside the
    runtime not mounting them)."""
    return is_write_tool(tool_name)


def requires_confirmation(tool_name: str, permission_mode: str | None) -> bool:
    """Whether ``tool_name`` must be confirmed by the user under ``permission_mode``.

    - ``bypassPermissions``: never (auto-allow; the API layer still enforces
      preview tokens for deploy/online).
    - ``plan``: never *confirmed* — write tools are denied outright; callers
      check :func:`plan_denies_tool` first.
    - ``default``: every write tool (drafts included).
    - ``acceptEdits``: only high-risk tools (drafts auto-allowed).
    """
    mode = normalize_permission_mode(permission_mode)
    if mode == "bypassPermissions":
        return False
    if mode == "plan":
        return False
    if mode == "acceptEdits":
        return is_high_risk_tool(tool_name)
    # default
    return is_write_tool(tool_name)
