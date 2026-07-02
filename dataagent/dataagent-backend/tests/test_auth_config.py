from __future__ import annotations

import sys
import time
import types
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

if "pymysql" not in sys.modules:
    sys.modules["pymysql"] = types.SimpleNamespace(
        connect=lambda *args, **kwargs: None,
        cursors=types.SimpleNamespace(DictCursor=object),
        connections=types.SimpleNamespace(Connection=object),
    )

import core.auth as auth
from core.auth import (
    AuthConfigError,
    AuthIdentity,
    init_auth,
    is_auth_enabled,
    issue_session_token,
    load_auth_settings,
    resolve_oauth_role,
    sanitize_redirect_path,
    verify_local_admin,
    verify_session_token,
)
from core.topic_task_store import TopicTaskStore

VALID_SECRET = "unit-test-secret-key-0123456789abcdef-0123456789"


@pytest.fixture(autouse=True)
def _reset_auth(monkeypatch):
    monkeypatch.delenv(auth.AUTH_CONFIG_ENV, raising=False)
    auth.reset_auth_for_tests()
    # 加载器会把配置目录插入 sys.path（运行时单目录无害）；测试间恢复快照，
    # 防止上一个用例的 tmp 目录里的覆盖模块被后续用例 import 到。
    saved_sys_path = list(sys.path)
    yield
    sys.path[:] = saved_sys_path
    sys.modules.pop("dataagent_auth_config_docker", None)
    auth.reset_auth_for_tests()


def write_config(tmp_path: Path, body: str) -> str:
    config_file = tmp_path / "auth_config.py"
    config_file.write_text(body, encoding="utf-8")
    return str(config_file)


def enable_local_admin_auth(tmp_path: Path, *, extra: str = "") -> str:
    import bcrypt

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    return write_config(
        tmp_path,
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
{extra}
""",
    )


# ---------------------------------------------------------------------------
# Fail-closed 加载矩阵
# ---------------------------------------------------------------------------

def test_env_unset_disables_auth():
    settings = load_auth_settings()
    assert settings.enabled is False
    init_auth()
    assert is_auth_enabled() is False


def test_env_set_but_file_missing_fails_startup(monkeypatch, tmp_path):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, str(tmp_path / "missing.py"))
    with pytest.raises(AuthConfigError):
        init_auth()


def test_env_set_but_file_invalid_python_fails_startup(monkeypatch, tmp_path):
    path = write_config(tmp_path, "this is not python !!!")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    with pytest.raises(AuthConfigError):
        init_auth()


def test_enabled_without_secret_key_fails_startup(tmp_path):
    path = write_config(tmp_path, "AUTH_ENABLED = True\nLOCAL_ADMINS = []\n")
    with pytest.raises(AuthConfigError):
        load_auth_settings(path)


@pytest.mark.parametrize(
    "secret",
    ["", auth.SECRET_KEY_PLACEHOLDER, "short-secret"],
    ids=["empty", "example-placeholder", "under-32-bytes"],
)
def test_weak_secret_key_fails_startup(tmp_path, secret):
    path = write_config(
        tmp_path,
        f"AUTH_ENABLED = True\nSECRET_KEY = {secret!r}\n"
        "LOCAL_ADMINS = [{'username': 'a', 'password_bcrypt': '$2b$04$abcdefghijklmnopqrstuv'}]\n",
    )
    with pytest.raises(AuthConfigError):
        load_auth_settings(path)


def test_enabled_without_any_login_method_fails_startup(tmp_path):
    path = write_config(tmp_path, f"AUTH_ENABLED = True\nSECRET_KEY = {VALID_SECRET!r}\n")
    with pytest.raises(AuthConfigError):
        load_auth_settings(path)


def test_valid_file_with_auth_disabled_is_explicit_off(monkeypatch, tmp_path):
    path = write_config(tmp_path, "AUTH_ENABLED = False\n")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    settings = init_auth()
    assert settings.enabled is False
    assert is_auth_enabled() is False


# ---------------------------------------------------------------------------
# 显式关闭回归：env 已设置 + AUTH_ENABLED=False + 库中已有 owner 数据
# → 谓词与旧版逐字一致、admin 依赖 no-op 放行。
# ---------------------------------------------------------------------------

def test_explicit_disable_uses_legacy_predicate_and_admin_noop(monkeypatch, tmp_path):
    path = write_config(tmp_path, "AUTH_ENABLED = False\n")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, path)
    init_auth()

    store = TopicTaskStore()
    # 即使 context 携带 owner 键（模拟曾启用 auth 的调用方），关闭态也回到旧谓词。
    sql, params = store._topic_context_predicate(  # noqa: SLF001
        {"source": "portal", "auth_user_id": "SSO:42", "auth_role": "user"}, alias="t"
    )
    assert sql == "COALESCE(t.source, 'portal') = %s"
    assert params == ["portal"]

    # require_admin no-op：无会话也放行。
    fake_request = types.SimpleNamespace(cookies={}, headers={})
    assert auth.require_admin(fake_request) is None


# ---------------------------------------------------------------------------
# JWT / bcrypt / 提名
# ---------------------------------------------------------------------------

def test_jwt_roundtrip_and_expiry(monkeypatch, tmp_path):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, enable_local_admin_auth(tmp_path))
    init_auth()

    identity = AuthIdentity(user_id="SSO:42", username="alice", role="user", provider="SSO")
    token = issue_session_token(identity)
    decoded = verify_session_token(token)
    assert decoded is not None
    assert decoded.user_id == "SSO:42"
    assert decoded.username == "alice"
    assert decoded.role == "user"

    expired = issue_session_token(identity, now=time.time() - 999999)
    assert verify_session_token(expired) is None
    assert verify_session_token("garbage.token.value") is None


def test_local_admin_bcrypt_verification(monkeypatch, tmp_path):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, enable_local_admin_auth(tmp_path))
    init_auth()

    identity = verify_local_admin("admin", "admin-pass")
    assert identity is not None
    assert identity.role == "admin"
    assert identity.user_id == "local:admin"

    assert verify_local_admin("admin", "wrong-pass") is None
    assert verify_local_admin("nobody", "admin-pass") is None


def test_admin_users_promotes_by_stable_id_not_username(monkeypatch, tmp_path):
    monkeypatch.setenv(
        auth.AUTH_CONFIG_ENV,
        enable_local_admin_auth(tmp_path, extra='ADMIN_USERS = ["SSO:1024"]\n'),
    )
    init_auth()

    assert resolve_oauth_role("SSO", "1024") == "admin"
    # username（或其它 sub）不提权。
    assert resolve_oauth_role("SSO", "alice") == "user"
    assert resolve_oauth_role("OTHER", "1024") == "user"


# ---------------------------------------------------------------------------
# redirect 消毒
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "raw",
    ["http://evil.com/x", "https://evil.com", "//evil.com", "/\\evil", "javascript:alert(1)", "", None],
)
def test_sanitize_redirect_rejects_unsafe_values(raw):
    assert sanitize_redirect_path(raw, fallback="/intelligent-query") == "/intelligent-query"


def test_sanitize_redirect_accepts_app_paths():
    assert sanitize_redirect_path("/intelligent-query/chat?x=1") == "/intelligent-query/chat?x=1"


# ---------------------------------------------------------------------------
# Superset docker/pythonpath_dev 同款目录模式：
# 基础配置末尾加载同目录用户覆盖模块（配置目录在 sys.path 上）。
# ---------------------------------------------------------------------------

def _write_base_config(tmp_path: Path) -> str:
    base = tmp_path / "dataagent_auth_config.py"
    base.write_text(
        """
AUTH_ENABLED = False
SECRET_KEY = ""
LOCAL_ADMINS = []
try:
    from dataagent_auth_config_docker import *  # noqa: F401,F403
except ImportError:
    pass
""",
        encoding="utf-8",
    )
    return str(base)


@pytest.fixture()
def _clean_override_module():
    yield
    sys.modules.pop("dataagent_auth_config_docker", None)


def test_base_config_without_override_is_disabled(monkeypatch, tmp_path, _clean_override_module):
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, _write_base_config(tmp_path))
    settings = init_auth()
    assert settings.enabled is False


def test_docker_override_module_is_loaded_from_config_dir(monkeypatch, tmp_path, _clean_override_module):
    import bcrypt

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    (tmp_path / "dataagent_auth_config_docker.py").write_text(
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
""",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, _write_base_config(tmp_path))
    settings = init_auth()
    assert settings.enabled is True
    assert settings.local_login_enabled is True


def test_broken_override_module_fails_startup(monkeypatch, tmp_path, _clean_override_module):
    (tmp_path / "dataagent_auth_config_docker.py").write_text("this is not python !!!", encoding="utf-8")
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, _write_base_config(tmp_path))
    with pytest.raises(AuthConfigError):
        init_auth()


def test_shipped_deploy_base_config_is_disabled_by_default(monkeypatch, _clean_override_module):
    shipped = Path(__file__).resolve().parents[3] / "deploy" / "docker" / "dataagent" / "dataagent_auth_config.py"
    assert shipped.is_file(), shipped
    settings = load_auth_settings(str(shipped))
    assert settings.enabled is False
