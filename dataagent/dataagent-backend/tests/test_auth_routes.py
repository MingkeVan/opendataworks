from __future__ import annotations

import sys
import types
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient

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
import api.auth_routes as auth_routes
from main import app

VALID_SECRET = "unit-test-secret-key-0123456789abcdef-0123456789"


@pytest.fixture(autouse=True)
def _reset_auth(monkeypatch):
    monkeypatch.delenv(auth.AUTH_CONFIG_ENV, raising=False)
    auth.reset_auth_for_tests()
    yield
    auth.reset_auth_for_tests()


def enable_auth(
    monkeypatch,
    tmp_path: Path,
    *,
    oauth: bool = False,
    admin_users: str = "[]",
    cookie_secure: bool = False,
    cookie_samesite: str = "lax",
) -> None:
    import bcrypt

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    oauth_block = ""
    if oauth:
        oauth_block = """
OAUTH = {
    "provider_name": "SSO",
    "client_id": "cid",
    "client_secret": "secret",
    "authorize_url": "https://sso.example.com/authorize",
    "token_url": "https://sso.example.com/token",
    "userinfo_url": "https://sso.example.com/userinfo",
    "scopes": "openid profile",
    "redirect_uri": "https://app.example.com/api/v1/nl2sql/auth/oauth/callback",
    "user_id_field": "sub",
    "username_field": "preferred_username",
    "post_login_redirect": "/intelligent-query/chat",
}
"""
    config_file = tmp_path / "auth_config.py"
    config_file.write_text(
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
COOKIE_SECURE = {cookie_secure!r}
COOKIE_SAMESITE = {cookie_samesite!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
ADMIN_USERS = {admin_users}
{oauth_block}
""",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, str(config_file))
    auth.init_auth()


def test_auth_config_reports_disabled_when_env_unset():
    client = TestClient(app)
    response = client.get("/api/v1/nl2sql/auth/config")
    assert response.status_code == 200
    assert response.json() == {
        "enabled": False,
        "provider_name": "",
        "local_login_enabled": False,
        "oauth_login_enabled": False,
    }


def test_login_endpoints_are_404_when_disabled():
    client = TestClient(app)
    assert client.post("/api/v1/nl2sql/auth/login", json={"username": "a", "password": "b"}).status_code == 404
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 404
    assert client.post("/api/v1/nl2sql/auth/logout").status_code == 404
    assert client.get("/api/v1/nl2sql/auth/oauth/authorize").status_code == 404


def test_local_login_sets_httponly_cookie_and_me_roundtrip(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path)
    client = TestClient(app)

    response = client.post("/api/v1/nl2sql/auth/login", json={"username": "admin", "password": "admin-pass"})
    assert response.status_code == 200
    assert response.json()["data"]["role"] == "admin"

    set_cookie = response.headers.get("set-cookie", "")
    assert "da_session=" in set_cookie
    assert "HttpOnly" in set_cookie
    assert "SameSite=lax" in set_cookie
    assert "Secure" not in set_cookie

    me = client.get("/api/v1/nl2sql/auth/me")
    assert me.status_code == 200
    assert me.json()["data"]["user_id"] == "local:admin"

    logout = client.post("/api/v1/nl2sql/auth/logout")
    assert logout.status_code == 200
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


def test_cookie_secure_flag_follows_config(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, cookie_secure=True)
    client = TestClient(app)
    response = client.post("/api/v1/nl2sql/auth/login", json={"username": "admin", "password": "admin-pass"})
    assert "Secure" in response.headers.get("set-cookie", "")


def test_local_login_rejects_bad_password(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path)
    client = TestClient(app)
    response = client.post("/api/v1/nl2sql/auth/login", json={"username": "admin", "password": "nope"})
    assert response.status_code == 401


def test_oauth_authorize_redirects_with_state(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = client.get(
        "/api/v1/nl2sql/auth/oauth/authorize?redirect=/intelligent-query/chat",
        follow_redirects=False,
    )
    assert response.status_code == 302
    location = urlparse(response.headers["location"])
    assert location.netloc == "sso.example.com"
    query = parse_qs(location.query)
    assert query["client_id"] == ["cid"]
    assert query["response_type"] == ["code"]
    state = query["state"][0]
    payload = auth.verify_oauth_state(state)
    assert payload is not None
    assert payload["redirect"] == "/intelligent-query/chat"

    # authorize 必须把 state nonce 同步种进发起浏览器的 HttpOnly Cookie（CSRF 绑定）。
    set_cookie = response.headers.get("set-cookie", "")
    assert f"{auth.OAUTH_STATE_COOKIE}=" in set_cookie
    assert "HttpOnly" in set_cookie
    assert client.cookies.get(auth.OAUTH_STATE_COOKIE) == payload["nonce"]


def test_oauth_nonce_cookie_is_lax_even_when_session_samesite_strict(monkeypatch, tmp_path):
    """P2 回归：nonce Cookie 必须硬编码 SameSite=Lax。若跟随 cfg（strict），
    浏览器不会在 IdP → 本站的跨站回调导航上带回它，callback 绑定校验必失败，
    OAuth 登录整体不可用。"""
    enable_auth(monkeypatch, tmp_path, oauth=True, cookie_samesite="strict")
    client = TestClient(app)

    response = client.get("/api/v1/nl2sql/auth/oauth/authorize", follow_redirects=False)
    assert response.status_code == 302

    nonce_cookie = next(
        c for c in response.headers.get_list("set-cookie") if c.startswith(f"{auth.OAUTH_STATE_COOKIE}=")
    ).lower()
    assert "samesite=lax" in nonce_cookie
    assert "samesite=strict" not in nonce_cookie


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class _FakeOAuth2Client:
    """伪 authlib AsyncOAuth2Client：fetch_token + 自动带 token 的 get。"""

    def __init__(self, *, userinfo):
        self._userinfo = userinfo
        self.fetch_calls = []

    def __call__(self, *args, **kwargs):
        self.init_kwargs = kwargs
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def fetch_token(self, url, **kwargs):
        self.fetch_calls.append((url, kwargs))
        return {"access_token": "at-1", "token_type": "Bearer"}

    async def get(self, url, **kwargs):
        return _FakeResponse(self._userinfo)


def _oauth_callback(client, monkeypatch, *, userinfo, redirect="", bind_nonce=True, cookie_nonce=None):
    monkeypatch.setattr(auth_routes, "AsyncOAuth2Client", _FakeOAuth2Client(userinfo=userinfo))
    nonce = auth.generate_oauth_nonce()
    state = auth.issue_oauth_state(redirect, nonce=nonce)
    # 正常流程：authorize 会把 nonce 种进发起浏览器的 HttpOnly Cookie。
    if bind_nonce:
        client.cookies.set(auth.OAUTH_STATE_COOKIE, cookie_nonce if cookie_nonce is not None else nonce)
    else:
        client.cookies.delete(auth.OAUTH_STATE_COOKIE) if auth.OAUTH_STATE_COOKIE in client.cookies else None
    return client.get(
        f"/api/v1/nl2sql/auth/oauth/callback?code=abc&state={state}",
        follow_redirects=False,
    )


def test_oauth_callback_issues_session_and_redirects(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch,
        userinfo={"sub": "42", "preferred_username": "alice"},
        redirect="/intelligent-query/chat",
    )
    assert response.status_code == 302
    assert response.headers["location"] == "/intelligent-query/chat"
    assert "HttpOnly" in response.headers.get("set-cookie", "")

    me = client.get("/api/v1/nl2sql/auth/me")
    assert me.status_code == 200
    data = me.json()["data"]
    assert data["user_id"] == "SSO:42"
    assert data["username"] == "alice"
    assert data["role"] == "user"


def test_oauth_callback_promotes_admin_by_provider_sub_not_username(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True, admin_users='["SSO:1024"]')
    client = TestClient(app)

    promoted = _oauth_callback(client, monkeypatch, userinfo={"sub": "1024", "preferred_username": "bob"})
    assert promoted.status_code == 302
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["role"] == "admin"

    client.cookies.clear()
    # username 与提名值相同也不提权（提名只认 provider:sub）。
    not_promoted = _oauth_callback(client, monkeypatch, userinfo={"sub": "7", "preferred_username": "SSO:1024"})
    assert not_promoted.status_code == 302
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["role"] == "user"


def test_oauth_callback_rejects_bad_state(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)
    response = client.get(
        "/api/v1/nl2sql/auth/oauth/callback?code=abc&state=tampered.state",
        follow_redirects=False,
    )
    assert response.status_code == 400


def test_oauth_callback_falls_back_to_safe_redirect(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)
    # state 中的 redirect 不合法（协议相对 URL）→ 回落 post_login_redirect。
    response = _oauth_callback(client, monkeypatch, userinfo={"sub": "42"}, redirect="//evil.com")
    assert response.status_code == 302
    assert response.headers["location"] == "/intelligent-query/chat"


# ---------------------------------------------------------------------------
# CUSTOM_SECURITY_MANAGER：Superset 同款类扩展契约
# （oauth_user_info / resolve_role / verify_local_login / on_login）
# ---------------------------------------------------------------------------

def enable_auth_with_manager(monkeypatch, tmp_path, *, manager_body: str) -> None:
    import bcrypt

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    config_file = tmp_path / "auth_config.py"
    config_file.write_text(
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
ADMIN_USERS = ["SSO:1024"]
OAUTH = {{
    "provider_name": "SSO",
    "client_id": "cid",
    "client_secret": "secret",
    "authorize_url": "https://sso.example.com/authorize",
    "token_url": "https://sso.example.com/token",
    "userinfo_url": "https://sso.example.com/userinfo",
    "redirect_uri": "https://app.example.com/cb",
    "post_login_redirect": "/intelligent-query/chat",
}}
{manager_body}
""",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, str(config_file))
    auth.init_auth()


NESTED_MANAGER = """
from core.security_manager import DataAgentSecurityManager

class CustomSsoSecurityManager(DataAgentSecurityManager):
    def oauth_user_info(self, provider, userinfo):
        payload = userinfo.get("data") or {}
        mapped = {"user_id": payload.get("uid"), "username": payload.get("nickname")}
        if "dataagent-admin" in (payload.get("roles") or []):
            mapped["role"] = "admin"
        return mapped

CUSTOM_SECURITY_MANAGER = CustomSsoSecurityManager
"""


def test_manager_takes_over_nonstandard_userinfo(monkeypatch, tmp_path):
    enable_auth_with_manager(monkeypatch, tmp_path, manager_body=NESTED_MANAGER)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch,
        userinfo={"code": 0, "data": {"uid": "7", "nickname": "小王", "roles": []}},
    )
    assert response.status_code == 302
    data = client.get("/api/v1/nl2sql/auth/me").json()["data"]
    assert data["user_id"] == "SSO:7"
    assert data["username"] == "小王"
    assert data["role"] == "user"


def test_manager_role_wins_over_admin_users(monkeypatch, tmp_path):
    enable_auth_with_manager(monkeypatch, tmp_path, manager_body=NESTED_MANAGER)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch,
        userinfo={"data": {"uid": "7", "nickname": "ops", "roles": ["dataagent-admin"]}},
    )
    assert response.status_code == 302
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["role"] == "admin"


def test_manager_without_role_falls_back_to_admin_users(monkeypatch, tmp_path):
    enable_auth_with_manager(monkeypatch, tmp_path, manager_body=NESTED_MANAGER)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch,
        userinfo={"data": {"uid": "1024", "nickname": "bob", "roles": []}},
    )
    assert response.status_code == 302
    # oauth_user_info 未返回 role → 回落 resolve_role（默认 ADMIN_USERS，SSO:1024）。
    assert client.get("/api/v1/nl2sql/auth/me").json()["data"]["role"] == "admin"


BROKEN_MANAGER = """
from core.security_manager import DataAgentSecurityManager

class CustomSsoSecurityManager(DataAgentSecurityManager):
    def oauth_user_info(self, provider, userinfo):
        raise RuntimeError("boom")

CUSTOM_SECURITY_MANAGER = CustomSsoSecurityManager
"""


def test_manager_failure_fails_the_login_not_the_service(monkeypatch, tmp_path):
    enable_auth_with_manager(monkeypatch, tmp_path, manager_body=BROKEN_MANAGER)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo={"sub": "42"})
    assert response.status_code == 302
    assert response.headers["location"] == "/login?error=oauth_user_info_failed"
    # 服务仍健康。
    assert client.get("/api/v1/nl2sql/auth/config").status_code == 200


ON_LOGIN_MANAGER = """
from core.security_manager import DataAgentSecurityManager

class CustomSsoSecurityManager(DataAgentSecurityManager):
    LOGIN_EVENTS = []

    def on_login(self, identity):
        type(self).LOGIN_EVENTS.append((identity.provider, identity.user_id))

CUSTOM_SECURITY_MANAGER = CustomSsoSecurityManager
"""


def test_on_login_called_for_local_and_oauth(monkeypatch, tmp_path):
    enable_auth_with_manager(monkeypatch, tmp_path, manager_body=ON_LOGIN_MANAGER)
    client = TestClient(app)

    # 本地登录触发 on_login。
    assert client.post(
        "/api/v1/nl2sql/auth/login", json={"username": "admin", "password": "admin-pass"}
    ).status_code == 200
    # OAuth 登录触发 on_login。
    assert _oauth_callback(client, monkeypatch, userinfo={"sub": "42"}).status_code == 302

    events = type(auth.get_security_manager()).LOGIN_EVENTS
    assert ("local", "local:admin") in events
    assert ("SSO", "SSO:42") in events


def test_on_login_failure_does_not_block_login(monkeypatch, tmp_path):
    enable_auth_with_manager(
        monkeypatch, tmp_path,
        manager_body="""
from core.security_manager import DataAgentSecurityManager

class CustomSsoSecurityManager(DataAgentSecurityManager):
    def on_login(self, identity):
        raise RuntimeError("audit sink down")

CUSTOM_SECURITY_MANAGER = CustomSsoSecurityManager
""",
    )
    client = TestClient(app)

    response = client.post("/api/v1/nl2sql/auth/login", json={"username": "admin", "password": "admin-pass"})
    assert response.status_code == 200
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 200


# ---------------------------------------------------------------------------
# OAuth 登录 CSRF：state 必须绑定发起登录的浏览器（P1 回归）
# ---------------------------------------------------------------------------

def test_oauth_callback_rejects_state_without_browser_nonce(monkeypatch, tmp_path):
    """攻击者拿自己的合法 state/code 构造 callback URL 让受害者打开：
    受害者浏览器没有对应 nonce Cookie，必须拒绝。"""
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo={"sub": "42"}, bind_nonce=False)
    assert response.status_code == 400
    # 未写入任何会话。
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


def test_oauth_callback_rejects_mismatched_browser_nonce(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(
        client, monkeypatch, userinfo={"sub": "42"}, cookie_nonce="another-browser-nonce"
    )
    assert response.status_code == 400
    assert client.get("/api/v1/nl2sql/auth/me").status_code == 401


def test_oauth_callback_clears_nonce_cookie_on_success(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path, oauth=True)
    client = TestClient(app)

    response = _oauth_callback(client, monkeypatch, userinfo={"sub": "42"})
    assert response.status_code == 302
    set_cookies = response.headers.get_list("set-cookie")
    assert any(f'{auth.OAUTH_STATE_COOKIE}=""' in c or f"{auth.OAUTH_STATE_COOKIE}=;" in c for c in set_cookies)


# ---------------------------------------------------------------------------
# bcrypt 超长口令 → 401 而非 500（P2 回归）
# ---------------------------------------------------------------------------

def test_login_with_overlong_password_returns_401(monkeypatch, tmp_path):
    enable_auth(monkeypatch, tmp_path)
    client = TestClient(app)
    response = client.post(
        "/api/v1/nl2sql/auth/login",
        json={"username": "admin", "password": "x" * 200},
    )
    assert response.status_code == 401
