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
        # Executes real, irreversible CREATE TABLE DDL on the engine; confirm every
        # time in default/acceptEdits, deny under plan. The read-only
        # portal_preview_create_table is intentionally not a write tool.
        "portal_create_table",
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

# Built-in (non-MCP) Claude Code tools that mutate files. Denied under plan so the
# read-only-until-approved promise holds even though they are never auto-allowed;
# after plan approval the run switches to acceptEdits, where they auto-allow.
PLAN_DENIED_BUILTIN_TOOLS: frozenset[str] = frozenset(
    {"Write", "Edit", "MultiEdit", "NotebookEdit"}
)

# The built-in plan tool the model calls to present its plan and request approval
# to leave plan mode. It is not MCP-qualified.
EXIT_PLAN_MODE_TOOL_NAME: str = "ExitPlanMode"

# Mode the session switches to once the user approves a plan: drafts auto-execute,
# high-risk (publish/online) still confirm. Keeps the approved plan flowing without
# re-confirming every draft write, while preserving the high-risk guard.
POST_PLAN_MODE: str = "acceptEdits"

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


def normalize_permission_decision(decision: Any) -> str:
    """Coerce a permission decision to its canonical persisted form.

    ``allow``/``deny``/``timeout`` stay as-is; anything unknown collapses to
    ``deny`` (fail-closed)."""
    value = str(decision or "").strip().lower()
    return value if value in {"allow", "deny", "timeout"} else "deny"


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


def is_exit_plan_mode(tool_name: str) -> bool:
    """Whether ``tool_name`` is the built-in plan-presentation/approval tool."""
    return _bare_tool_name(tool_name) == EXIT_PLAN_MODE_TOOL_NAME


def post_plan_mode() -> str:
    """Permission mode a session adopts after the user approves a plan."""
    return POST_PLAN_MODE


def is_write_tool(tool_name: str) -> bool:
    return _bare_tool_name(tool_name) in WRITE_TOOL_NAMES


def is_high_risk_tool(tool_name: str) -> bool:
    return _bare_tool_name(tool_name) in HIGH_RISK_TOOL_NAMES


def plan_denies_tool(tool_name: str) -> bool:
    """Whether ``tool_name`` must be denied under ``plan`` mode.

    Covers portal MCP write tools and the built-in file-mutation tools
    (``PLAN_DENIED_BUILTIN_TOOLS``). Defense in depth: these are not in the
    auto-allow set, but if the model invokes one it must not run before the plan is
    approved; after approval the run switches to acceptEdits where they auto-allow.

    ``Bash`` is deliberately not denied: it is the read-only research vector (skill
    scripts, read-only SQL) and is auto-allowed upstream via ``allowed_tools`` (the
    callback never sees it), and its filesystem writes are confined to the ephemeral
    per-topic workspace by the runtime boundary hook. This is an accepted trust
    boundary, not a hard guarantee: the sandbox forwards DB/portal credentials
    (``MYSQL_`` / ``DATAAGENT_PORTAL_`` / ``ODW_`` env) into the child, so Bash could
    in principle reach platform state outside the gated MCP path. Plan mode relies on
    the model honoring read-only research here rather than on Bash being incapable."""
    bare = _bare_tool_name(tool_name)
    return bare in WRITE_TOOL_NAMES or bare in PLAN_DENIED_BUILTIN_TOOLS


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
