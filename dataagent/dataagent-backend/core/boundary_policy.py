"""Workspace boundary policy serialization for non-Python data planes.

The boundary rules themselves live in :mod:`core.agent_runtime` and stay the
single source of truth. This module only *serializes* them into a language
neutral spec so a data plane that does not run in this process — currently the
Node Pi Cell — can enforce the same decisions locally instead of round-tripping
every tool call back over stdio.

Nothing here re-states a rule. Every list is exported from the constant or the
pure function that the Python enforcement path already uses, so a change on the
Python side cannot silently leave the serialized spec behind. What keeps the two
*enforcement* implementations aligned is the shared conformance fixture at
``dataagent/contracts/boundary/v1/conformance-cases.json``: both sides run the
same case table, so a divergence fails a test rather than reaching production.

Profiles
--------
``claude_code``
    Full policy, including the offloaded tool-result exception. The Claude Code
    CLI writes oversized tool results to ``<project_data_dir>/<session>/
    tool-results/<id>.{txt,json}`` and rewrites the inline result with that path,
    so the boundary must let the agent view (but not mutate) those files.

``pi_agent_core``
    Strict subset. The Pi Cell truncates tool output in process and never
    offloads it to disk, so the whole exception collapses: ``tool_result_root``
    is ``None`` and no read-only-command allowance applies. This is deliberate —
    it is the reason the TypeScript enforcer does not need to reimplement
    ``_is_offloaded_tool_result_path`` or the pager exclusion.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from core.agent_runtime import (
    _BASH_OPERATOR_CHARS,
    _BASH_READONLY_COMMANDS,
    _DISCARD_SINK_PATHS,
    _FILE_BOUNDARY_PATH_KEYS,
    _build_workspace_allowed_roots,
    _resolve_claude_project_data_dir,
)

POLICY_VERSION = 1
SUPPORTED_PROFILES = ("claude_code", "pi_agent_core")


def build_boundary_policy(
    project_cwd: str | Path,
    skill_runtime: dict[str, Any] | None,
    scratch_dirs: list[str] | None,
    runtime_env: dict[str, str] | None = None,
    profile: str = "pi_agent_core",
) -> dict[str, Any]:
    """Serialize the workspace boundary policy for ``profile``.

    ``project_cwd``, ``skill_runtime`` and ``scratch_dirs`` are the same inputs
    ``_build_workspace_boundary_hooks`` feeds the Python enforcement path, so the
    two agree by construction. ``runtime_env`` supplies ``DATAAGENT_PYTHON_BIN``
    (the one executable a skill script may invoke from outside the workspace)
    and, for the ``claude_code`` profile only, ``HOME``/``CLAUDE_CONFIG_DIR``
    used to locate the CLI's per-project data dir.
    """
    if profile not in SUPPORTED_PROFILES:
        raise ValueError(f"Unsupported boundary profile {profile!r}; expected one of {SUPPORTED_PROFILES}")

    workspace = Path(project_cwd).expanduser().resolve(strict=False)
    allowed_roots = _build_workspace_allowed_roots(workspace, skill_runtime, scratch_dirs)

    env = runtime_env or {}
    allowed_executables: list[str] = []
    python_bin = str(env.get("DATAAGENT_PYTHON_BIN") or "").strip()
    if python_bin:
        allowed_executables.append(str(Path(python_bin).expanduser().resolve(strict=False)))

    # Only the CLI offloads tool results; the Pi Cell truncates in process.
    tool_result_root: str | None = None
    readonly_commands: list[str] = []
    if profile == "claude_code":
        resolved_root = _resolve_claude_project_data_dir(workspace, env)
        if resolved_root is not None:
            tool_result_root = str(resolved_root)
            readonly_commands = sorted(_BASH_READONLY_COMMANDS)

    return {
        "policy_version": POLICY_VERSION,
        "profile": profile,
        "workspace_root": str(workspace),
        "allowed_roots": [str(root) for root in allowed_roots],
        "allowed_executables": allowed_executables,
        "discard_sinks": sorted(str(path) for path in _DISCARD_SINK_PATHS),
        "tool_path_keys": {tool: list(keys) for tool, keys in _FILE_BOUNDARY_PATH_KEYS.items()},
        "operator_chars": "".join(sorted(_BASH_OPERATOR_CHARS)),
        "tool_result_root": tool_result_root,
        "readonly_commands": readonly_commands,
    }
