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


def enable_auth(monkeypatch, tmp_path: Path, *, oauth: bool = False, admin_users: str = "[]", cookie_secure: bool = False) -> None:
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


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class _FakeAsyncClient:
    def __init__(self, *, userinfo):
        self._userinfo = userinfo

    def __call__(self, *args, **kwargs):
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def post(self, url, **kwargs):
        return _FakeResponse({"access_token": "at-1"})

    async def get(self, url, **kwargs):
        return _FakeResponse(self._userinfo)


def _oauth_callback(client, monkeypatch, *, userinfo, redirect=""):
    monkeypatch.setattr(auth_routes.httpx, "AsyncClient", _FakeAsyncClient(userinfo=userinfo))
    state = auth.issue_oauth_state(redirect)
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
