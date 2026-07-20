from __future__ import annotations

import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core import agent_visibility
from core.agent_visibility import (
    agent_visible_to,
    default_agent_visibility,
    filter_visible_agent_profiles,
    normalize_agent_visibility,
)
from core.auth import AuthIdentity


def _identity(user_id: str = "SSO:42", role: str = "user") -> AuthIdentity:
    return AuthIdentity(user_id=user_id, display_name="tester", role=role, provider="SSO")


# ---------------------------------------------------------------------------
# normalize_agent_visibility
# ---------------------------------------------------------------------------

def test_normalize_defaults_to_all_visible():
    expected = {"mode": "all", "allowed_users": [], "allowed_groups": []}
    assert normalize_agent_visibility(None) == expected
    assert normalize_agent_visibility("") == expected
    assert normalize_agent_visibility({}) == expected
    assert default_agent_visibility() == expected


def test_normalize_accepts_json_string_and_known_modes():
    assert normalize_agent_visibility('{"mode": "authenticated"}')["mode"] == "authenticated"
    assert normalize_agent_visibility({"mode": " Selected "})["mode"] == "selected"


def test_normalize_tolerates_dirty_db_values():
    # 读侧容错：脏数据一律回落默认全量可见，列表不因坏行失败。
    assert normalize_agent_visibility("{not json")["mode"] == "all"
    assert normalize_agent_visibility({"mode": "unknown"})["mode"] == "all"
    assert normalize_agent_visibility({"allowed_users": "not-a-list"})["allowed_users"] == []
    assert normalize_agent_visibility(["not", "a", "dict"])["mode"] == "all"


def test_normalize_strict_rejects_invalid_input():
    with pytest.raises(ValueError, match="invalid visibility mode"):
        normalize_agent_visibility({"mode": "unknown"}, strict=True)
    with pytest.raises(ValueError, match="must be an object"):
        normalize_agent_visibility(["not", "a", "dict"], strict=True)
    with pytest.raises(ValueError, match="allowed_users must be a list"):
        normalize_agent_visibility({"allowed_users": "not-a-list"}, strict=True)


def test_normalize_dedupes_and_strips_entries():
    normalized = normalize_agent_visibility(
        {
            "mode": "selected",
            "allowed_users": [" SSO:42 ", "SSO:42", "", None, "local:alice"],
            "allowed_groups": ["ops", "ops", " data "],
        }
    )
    assert normalized["allowed_users"] == ["SSO:42", "local:alice"]
    # allowed_groups 为用户组预留字段：随配置归一化持久化，本期不参与匹配。
    assert normalized["allowed_groups"] == ["ops", "data"]


def test_normalize_enforces_entry_limits():
    too_long = "u" * (agent_visibility.MAX_ALLOWED_USER_LENGTH + 1)
    assert normalize_agent_visibility({"allowed_users": [too_long]})["allowed_users"] == []
    with pytest.raises(ValueError, match="entry too long"):
        normalize_agent_visibility({"allowed_users": [too_long]}, strict=True)

    overflow = [f"user:{index}" for index in range(agent_visibility.MAX_ALLOWED_USERS + 1)]
    truncated = normalize_agent_visibility({"allowed_users": overflow})
    assert len(truncated["allowed_users"]) == agent_visibility.MAX_ALLOWED_USERS
    with pytest.raises(ValueError, match="too many entries"):
        normalize_agent_visibility({"allowed_users": overflow}, strict=True)


# ---------------------------------------------------------------------------
# agent_visible_to / filter_visible_agent_profiles
# ---------------------------------------------------------------------------

def _profile(mode: str, allowed_users: list[str] | None = None) -> dict:
    return {
        "agent_id": f"agent_{mode}",
        "visibility": {"mode": mode, "allowed_users": allowed_users or [], "allowed_groups": []},
    }


def test_visible_to_everyone_when_auth_disabled(monkeypatch):
    monkeypatch.setattr(agent_visibility, "is_auth_enabled", lambda: False)
    assert agent_visible_to(_profile("selected", ["SSO:someone-else"]), None) is True
    assert agent_visible_to(_profile("authenticated"), None) is True


def test_admin_always_sees_everything(monkeypatch):
    monkeypatch.setattr(agent_visibility, "is_auth_enabled", lambda: True)
    admin = _identity(user_id="local:admin", role="admin")
    assert agent_visible_to(_profile("selected", ["SSO:someone-else"]), admin) is True
    assert agent_visible_to(_profile("authenticated"), admin) is True


def test_anonymous_only_sees_all_mode(monkeypatch):
    monkeypatch.setattr(agent_visibility, "is_auth_enabled", lambda: True)
    assert agent_visible_to(_profile("all"), None) is True
    assert agent_visible_to(_profile("authenticated"), None) is False
    assert agent_visible_to(_profile("selected", ["SSO:42"]), None) is False


def test_authenticated_user_matrix(monkeypatch):
    monkeypatch.setattr(agent_visibility, "is_auth_enabled", lambda: True)
    user = _identity(user_id="SSO:42")
    assert agent_visible_to(_profile("all"), user) is True
    assert agent_visible_to(_profile("authenticated"), user) is True
    assert agent_visible_to(_profile("selected", ["SSO:42", "local:alice"]), user) is True
    assert agent_visible_to(_profile("selected", ["local:alice"]), user) is False
    # selected + 空名单 ⇒ 仅管理员可见。
    assert agent_visible_to(_profile("selected"), user) is False


def test_missing_visibility_defaults_to_all(monkeypatch):
    monkeypatch.setattr(agent_visibility, "is_auth_enabled", lambda: True)
    assert agent_visible_to({"agent_id": "agent_legacy"}, None) is True
    assert agent_visible_to(None, None) is True


def test_filter_visible_agent_profiles(monkeypatch):
    monkeypatch.setattr(agent_visibility, "is_auth_enabled", lambda: True)
    profiles = [
        _profile("all"),
        _profile("authenticated"),
        _profile("selected", ["SSO:42"]),
        _profile("selected", ["local:alice"]),
    ]

    anonymous = filter_visible_agent_profiles(profiles, None)
    assert [item["agent_id"] for item in anonymous] == ["agent_all"]

    user = filter_visible_agent_profiles(profiles, _identity(user_id="SSO:42"))
    assert [item["agent_id"] for item in user] == ["agent_all", "agent_authenticated", "agent_selected"]

    admin = filter_visible_agent_profiles(profiles, _identity(user_id="local:admin", role="admin"))
    assert len(admin) == 4
