from __future__ import annotations

from pathlib import Path


REQUIREMENTS = Path(__file__).resolve().parents[1] / "requirements.txt"
CLAUDE_AGENT_SDK_VERSION = "0.2.114"


def test_dataagent_runtime_preinstalls_pytest_for_skill_validation():
    requirements = REQUIREMENTS.read_text(encoding="utf-8").splitlines()

    assert any(line.strip().startswith("pytest") for line in requirements)


def test_dataagent_runtime_pins_claude_agent_sdk_to_safe_cli_bundle():
    requirements = REQUIREMENTS.read_text(encoding="utf-8").splitlines()

    assert f"claude-agent-sdk=={CLAUDE_AGENT_SDK_VERSION}" in {
        line.strip() for line in requirements
    }
