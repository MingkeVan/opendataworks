"""Python half of the cross-language workspace-boundary conformance suite.

Runs ``dataagent/contracts/boundary/v1/conformance-cases.json`` against the real
Python enforcement path. The TypeScript enforcer in ``dataagent-runtime-pi`` runs
the *same* file against its own implementation
(``test/boundary_conformance.test.ts``).

This pairing is what makes the "Python generates the policy, Node enforces it"
split safe: the two implementations are separate code, so only a shared case
table can catch a divergence. Add a case here and the Node side fails until it
agrees — that is the intended workflow, not an accident.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core import agent_runtime  # noqa: E402
from core.boundary_policy import build_boundary_policy  # noqa: E402

FIXTURE_PATH = (
    Path(__file__).resolve().parents[2] / "contracts" / "boundary" / "v1" / "conformance-cases.json"
)


def _load_fixture() -> dict:
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def _case_ids() -> list[str]:
    return [case["id"] for case in _load_fixture()["cases"]]


@pytest.fixture(scope="module")
def env(tmp_path_factory) -> dict:
    """Materialize the fixture placeholders as real directories.

    Layout mirrors the container runtime: an ancestor root that is deliberately
    *not* allowed (``WS_ANCESTOR``, standing in for ``/dataagent_runtime``), the
    topic workspace under it, plus an enabled skill root and a scratch dir.
    """
    base = tmp_path_factory.mktemp("boundary")
    ws_ancestor = base / "runtime"
    workspace = ws_ancestor / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    skill_root = base / "skills" / "opendataworks-platform-tools"
    skill_root.mkdir(parents=True)
    scratch = base / "scratch"
    scratch.mkdir()
    outside = base / "outside"
    outside.mkdir()

    substitutions = {
        "{WS}": str(workspace),
        "{WS_ANCESTOR}": str(ws_ancestor),
        "{SKILL}": str(skill_root),
        "{SCRATCH}": str(scratch),
        "{OUTSIDE}": str(outside),
        "{PYBIN}": str(Path(sys.executable).resolve()),
    }
    skill_runtime = {"enabled_roots": {"opendataworks-platform-tools": str(skill_root)}}
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable}

    return {
        "workspace": workspace,
        "substitutions": substitutions,
        "skill_runtime": skill_runtime,
        "runtime_env": runtime_env,
        "scratch_dirs": [str(scratch)],
        "allowed_roots": agent_runtime._build_workspace_allowed_roots(
            workspace, skill_runtime, [str(scratch)]
        ),
    }


def _substitute(value, substitutions: dict[str, str]):
    if isinstance(value, str):
        for token, replacement in substitutions.items():
            value = value.replace(token, replacement)
        return value
    if isinstance(value, dict):
        return {k: _substitute(v, substitutions) for k, v in value.items()}
    return value


@pytest.mark.parametrize("case_id", _case_ids())
def test_boundary_conformance_case(case_id: str, env: dict):
    case = next(c for c in _load_fixture()["cases"] if c["id"] == case_id)
    tool_input = _substitute(case["input"], env["substitutions"])

    denial = agent_runtime._validate_workspace_tool_boundary(
        case["tool"],
        tool_input,
        env["workspace"],
        env["allowed_roots"],
        env["runtime_env"],
    )

    if case["expect"] == "allow":
        assert denial is None, f"{case_id}: expected allow, got denial {denial!r}"
    else:
        assert denial is not None, f"{case_id}: expected deny, got allow"
        expected_fragment = case.get("reason_contains")
        if expected_fragment:
            assert expected_fragment in denial, (
                f"{case_id}: denial {denial!r} does not mention {expected_fragment!r}"
            )


def test_policy_serialization_matches_enforcement_inputs(env: dict):
    """The serialized policy must carry exactly the roots enforcement uses.

    This is the seam where the Node side could silently drift: if the policy is
    built from a different root list than the Python enforcer validates against,
    both sides pass their own tests while disagreeing in production.
    """
    policy = build_boundary_policy(
        env["workspace"],
        env["skill_runtime"],
        env["scratch_dirs"],
        env["runtime_env"],
        profile="pi_agent_core",
    )

    assert policy["allowed_roots"] == [str(root) for root in env["allowed_roots"]]
    assert policy["workspace_root"] == str(env["workspace"])
    assert policy["allowed_executables"] == [str(Path(sys.executable).resolve())]
    assert policy["discard_sinks"] == ["/dev/null"]
    # The Pi profile has no CLI tool-result offloading, so the whole exception
    # collapses and the Node enforcer never needs to implement it.
    assert policy["tool_result_root"] is None
    assert policy["readonly_commands"] == []


def test_claude_code_profile_keeps_tool_result_exception(env: dict, tmp_path: Path):
    home = tmp_path / "home"
    policy = build_boundary_policy(
        env["workspace"],
        env["skill_runtime"],
        env["scratch_dirs"],
        {"DATAAGENT_PYTHON_BIN": sys.executable, "HOME": str(home)},
        profile="claude_code",
    )

    assert policy["tool_result_root"] is not None
    assert "cat" in policy["readonly_commands"]
    # Pagers stay excluded: LESSOPEN can execute arbitrary preprocessors.
    assert "less" not in policy["readonly_commands"]
    assert "more" not in policy["readonly_commands"]


def test_unsupported_profile_is_rejected(env: dict):
    with pytest.raises(ValueError, match="Unsupported boundary profile"):
        build_boundary_policy(
            env["workspace"], env["skill_runtime"], env["scratch_dirs"], env["runtime_env"], profile="nope"
        )
