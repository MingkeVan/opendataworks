from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core import slash_command_cache
from core import task_executor


# Class name matters: the executor dispatches on type(msg).__name__.
class SystemMessage:
    def __init__(self, subtype, data=None, **extra):
        self.subtype = subtype
        self.data = data
        for key, value in extra.items():
            setattr(self, key, value)


def _make_accumulator(agent_id):
    params = SimpleNamespace(agent_snapshot={"agent_id": agent_id} if agent_id else None)
    return task_executor.SdkResultAccumulator(params, provider_id="openrouter", model="m")


def test_cache_record_strips_and_dedupes_blanks():
    slash_command_cache._AGENT_SLASH_COMMANDS.clear()
    slash_command_cache.record_agent_slash_commands("agent-1", [" compact ", "", "context", None])
    assert slash_command_cache.get_agent_slash_commands("agent-1") == ["compact", "context"]
    # Unknown agent and blank id resolve to None.
    assert slash_command_cache.get_agent_slash_commands("missing") is None
    assert slash_command_cache.get_agent_slash_commands("") is None


def test_get_returns_a_copy():
    slash_command_cache._AGENT_SLASH_COMMANDS.clear()
    slash_command_cache.record_agent_slash_commands("agent-1", ["compact"])
    snapshot = slash_command_cache.get_agent_slash_commands("agent-1")
    snapshot.append("mutated")
    assert slash_command_cache.get_agent_slash_commands("agent-1") == ["compact"]


def test_ingest_init_system_message_records_slash_commands():
    slash_command_cache._AGENT_SLASH_COMMANDS.clear()
    acc = _make_accumulator("agent-1")
    acc.ingest(SystemMessage("init", {"slash_commands": ["clear", "compact", "opendataworks-platform-tools"]}))
    assert slash_command_cache.get_agent_slash_commands("agent-1") == [
        "clear",
        "compact",
        "opendataworks-platform-tools",
    ]


def test_ingest_ignores_non_init_and_missing_agent():
    slash_command_cache._AGENT_SLASH_COMMANDS.clear()
    # Non-init system message is ignored.
    _make_accumulator("agent-1").ingest(SystemMessage("compact_boundary", {"slash_commands": ["x"]}))
    assert slash_command_cache.get_agent_slash_commands("agent-1") is None
    # init without an agent_id in the snapshot is ignored.
    _make_accumulator("").ingest(SystemMessage("init", {"slash_commands": ["x"]}))
    assert slash_command_cache._AGENT_SLASH_COMMANDS == {}


def test_ingest_reads_slash_commands_from_attribute_fallback():
    slash_command_cache._AGENT_SLASH_COMMANDS.clear()
    acc = _make_accumulator("agent-1")
    # Some SDK builds expose slash_commands as a direct attribute, not under data.
    acc.ingest(SystemMessage("init", None, slash_commands=["usage"]))
    assert slash_command_cache.get_agent_slash_commands("agent-1") == ["usage"]
