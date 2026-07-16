"""DataAgent 外置配置加载 + 认证核心（会话 JWT、身份解析与管理员门禁）。

配置来自 env ``DATAAGENT_CONFIG`` 指向的宿主机挂载 Python 文件
（Superset ``superset_config.py`` 模式）。除认证（AUTH_* / OAUTH_PROVIDERS / LOCAL_ADMINS /
ADMIN_USERS）外，也可通过 ``DATAAGENT_SETTINGS`` 字典覆盖运行时 Settings
（config.py 中的字段，如超时/并发档位），main 启动时统一应用。

语义为 fail-closed：

- env 未设置 → 认证关闭，行为与无认证时代完全一致（唯一合法关闭路径，也是回滚手段）。
- env 已设置但文件不可读 / import 失败 / 配置非法 / 启用却缺合法 SECRET_KEY /
  ``DATAAGENT_SETTINGS`` 含未知字段 → 抛 ``AuthConfigError``，服务启动失败，
  绝不静默降级。
- 合法文件里 ``AUTH_ENABLED = False`` → 认证显式关闭（默认挂载态）。

设计文档：docs/design/2026-07-01-dataagent-auth-design.md
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import importlib.util
import json
import logging
import os
import secrets
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Any

from fastapi import HTTPException, Request

logger = logging.getLogger(__name__)

AUTH_CONFIG_ENV = "DATAAGENT_CONFIG"

# 与 deploy/dataagent-auth-config.example.py 中的占位值保持一致：
# 用户直接拷贝示例而不改密钥时必须启动失败。
SECRET_KEY_PLACEHOLDER = "CHANGE-ME-generate-with-secrets.token_urlsafe(32)"
SECRET_KEY_MIN_BYTES = 32

ROLE_ADMIN = "admin"
ROLE_USER = "user"

LOCAL_PROVIDER = "local"

_STATE_TTL_SECONDS = 600


class AuthConfigError(RuntimeError):
    """外置认证配置不合法（fail-closed：启动期抛出）。"""


@dataclass
class OAuthSettings:
    provider_name: str = "SSO"
    icon: str = ""
    client_id: str = ""
    client_secret: str = ""
    authorize_url: str = ""
    token_url: str = ""
    userinfo_url: str = ""
    scopes: str = "openid profile"
    redirect_uri: str = ""
    user_id_field: str = "sub"
    username_field: str = "preferred_username"
    post_login_redirect: str = "/"

    @property
    def configured(self) -> bool:
        return bool(self.client_id and self.authorize_url and self.token_url)


@dataclass
class AuthSettings:
    enabled: bool = False
    secret_key: str = ""
    session_ttl_seconds: int = 28800
    cookie_name: str = "da_session"
    cookie_secure: bool = False
    cookie_samesite: str = "lax"
    oauth: OAuthSettings = field(default_factory=OAuthSettings)
    local_admins: list[dict[str, str]] = field(default_factory=list)
    admin_users: list[str] = field(default_factory=list)
    # 可选 userinfo → 身份 映射钩子（Superset custom_sso_security_manager 的
    # oauth_user_info 对应物）：callable(userinfo: dict) -> dict，返回
    # {"user_id": 必填, "username": 可选, "role": 可选 'admin'/'user'}。
    # 用于非标准 IdP（嵌套 payload、自定义角色/组逻辑）。
    oauth_userinfo_mapper: Any = None
    # DATAAGENT_SETTINGS：非认证的运行时 Settings 覆盖（config.py 字段），
    # main 启动时经 config.update_settings 应用。
    runtime_settings: dict[str, Any] = field(default_factory=dict)
    config_path: str = ""

    @property
    def local_login_enabled(self) -> bool:
        return bool(self.local_admins)

    @property
    def oauth_login_enabled(self) -> bool:
        return self.oauth.configured


@dataclass
class AuthIdentity:
    """已认证主体。``user_id`` 为命名空间化稳定标识（local:<u> / <provider>:<sub>）。"""

    user_id: str
    username: str
    role: str
    provider: str

    @property
    def is_admin(self) -> bool:
        return self.role == ROLE_ADMIN


_settings: AuthSettings | None = None
_lock = threading.Lock()


def _validate_secret_key(secret_key: Any) -> str:
    if not isinstance(secret_key, str) or not secret_key:
        raise AuthConfigError("AUTH_ENABLED=True 但 SECRET_KEY 为空")
    if secret_key == SECRET_KEY_PLACEHOLDER:
        raise AuthConfigError(
            "SECRET_KEY 仍是示例占位值，请用 python -c \"import secrets; print(secrets.token_urlsafe(32))\" 生成"
        )
    if len(secret_key.encode("utf-8")) < SECRET_KEY_MIN_BYTES:
        raise AuthConfigError(f"SECRET_KEY 过短（HS256 弱密钥可伪造会话），至少 {SECRET_KEY_MIN_BYTES} 字节")
    return secret_key


def _parse_local_admins(raw: Any) -> list[dict[str, str]]:
    if raw is None:
        return []
    if not isinstance(raw, (list, tuple)):
        raise AuthConfigError("LOCAL_ADMINS 必须是 [{'username':…,'password_bcrypt':…}] 列表")
    admins: list[dict[str, str]] = []
    for item in raw:
        if not isinstance(item, dict):
            raise AuthConfigError("LOCAL_ADMINS 条目必须是 dict")
        username = str(item.get("username") or "").strip()
        password_bcrypt = str(item.get("password_bcrypt") or "").strip()
        if not username or not password_bcrypt:
            raise AuthConfigError("LOCAL_ADMINS 条目缺少 username 或 password_bcrypt")
        if not password_bcrypt.startswith("$2"):
            raise AuthConfigError(f"LOCAL_ADMINS 用户 {username!r} 的 password_bcrypt 不是 bcrypt 哈希")
        admins.append({"username": username, "password_bcrypt": password_bcrypt})
    return admins


def _parse_oauth_providers(raw: Any) -> OAuthSettings:
    if raw is None:
        return OAuthSettings()
    if not isinstance(raw, list):
        raise AuthConfigError("OAUTH_PROVIDERS 必须是 provider dict 列表")
    if not raw:
        return OAuthSettings()
    if len(raw) != 1:
        raise AuthConfigError("OAUTH_PROVIDERS 本期仅支持单 Provider，必须恰好配置一项")
    provider = raw[0]
    if not isinstance(provider, dict):
        raise AuthConfigError("OAUTH_PROVIDERS 条目必须是 dict")
    remote_app = provider.get("remote_app")
    if remote_app is None:
        remote_app = {}
    if not isinstance(remote_app, dict):
        raise AuthConfigError("OAUTH_PROVIDERS.remote_app 必须是 dict")
    client_kwargs = remote_app.get("client_kwargs")
    if client_kwargs is None:
        client_kwargs = {}
    if not isinstance(client_kwargs, dict):
        raise AuthConfigError("OAUTH_PROVIDERS.remote_app.client_kwargs 必须是 dict")
    oauth = OAuthSettings(
        provider_name=str(provider.get("name") or "").strip(),
        icon=str(provider.get("icon") or "").strip(),
        client_id=str(remote_app.get("client_id") or ""),
        client_secret=str(remote_app.get("client_secret") or ""),
        authorize_url=str(remote_app.get("authorize_url") or ""),
        token_url=str(remote_app.get("access_token_url") or ""),
        scopes=str(client_kwargs.get("scope") or "openid profile"),
        redirect_uri=str(remote_app.get("redirect_uri") or ""),
        userinfo_url=str(provider.get("userinfo_url") or ""),
        user_id_field=str(provider.get("user_id_field") or "sub"),
        username_field=str(provider.get("username_field") or "preferred_username"),
        post_login_redirect=str(provider.get("post_login_redirect") or "/"),
    )
    if not oauth.provider_name:
        raise AuthConfigError("OAUTH_PROVIDERS 条目缺少 name")
    if not oauth.configured:
        raise AuthConfigError(
            "OAUTH_PROVIDERS.remote_app 配置不完整："
            "client_id / authorize_url / access_token_url 必须同时提供"
        )
    # callback 实际必用的字段也纳入启动期校验，避免"能启动、登录页有 OAuth
    # 按钮、回调必失败"的上线即不可用状态。
    missing = [name for name in ("userinfo_url", "redirect_uri") if not getattr(oauth, name)]
    if missing:
        raise AuthConfigError(f"OAUTH_PROVIDERS 配置不完整：缺少回调必需字段 {missing}")
    return oauth


def _build_settings(module: Any, config_path: str) -> AuthSettings:
    enabled = bool(getattr(module, "AUTH_ENABLED", False))
    if getattr(module, "OAUTH", None):
        raise AuthConfigError("OAUTH 扁平配置已不支持，请迁移为单项 OAUTH_PROVIDERS")
    settings = AuthSettings(
        enabled=enabled,
        session_ttl_seconds=int(getattr(module, "SESSION_TTL_SECONDS", 28800)),
        cookie_name=str(getattr(module, "COOKIE_NAME", "da_session") or "da_session"),
        cookie_secure=bool(getattr(module, "COOKIE_SECURE", False)),
        cookie_samesite=str(getattr(module, "COOKIE_SAMESITE", "lax") or "lax").lower(),
        oauth=_parse_oauth_providers(getattr(module, "OAUTH_PROVIDERS", None)),
        local_admins=_parse_local_admins(getattr(module, "LOCAL_ADMINS", None)),
        admin_users=[str(x).strip() for x in (getattr(module, "ADMIN_USERS", None) or []) if str(x).strip()],
        oauth_userinfo_mapper=getattr(module, "OAUTH_USERINFO_MAPPER", None),
        config_path=config_path,
    )
    if settings.oauth_userinfo_mapper is not None and not callable(settings.oauth_userinfo_mapper):
        raise AuthConfigError("OAUTH_USERINFO_MAPPER 必须是 callable(userinfo: dict) -> dict")

    raw_runtime = getattr(module, "DATAAGENT_SETTINGS", None)
    if raw_runtime is not None:
        if not isinstance(raw_runtime, dict):
            raise AuthConfigError("DATAAGENT_SETTINGS 必须是 dict（config.py Settings 字段 → 值）")
        from config import Settings as RuntimeSettings

        known_fields = set(RuntimeSettings.model_fields)
        unknown = sorted(str(k) for k in raw_runtime if str(k) not in known_fields)
        if unknown:
            raise AuthConfigError(f"DATAAGENT_SETTINGS 含未知字段: {unknown}")
        settings.runtime_settings = {str(k): v for k, v in raw_runtime.items()}
    if settings.cookie_samesite not in {"lax", "strict", "none"}:
        raise AuthConfigError(f"COOKIE_SAMESITE 非法: {settings.cookie_samesite!r}")
    if settings.session_ttl_seconds <= 0:
        raise AuthConfigError("SESSION_TTL_SECONDS 必须为正数")
    if enabled:
        settings.secret_key = _validate_secret_key(getattr(module, "SECRET_KEY", ""))
        if not settings.local_login_enabled and not settings.oauth_login_enabled:
            raise AuthConfigError(
                "AUTH_ENABLED=True 但 LOCAL_ADMINS 与 OAUTH_PROVIDERS 均未配置，无任何可用登录方式"
            )
    return settings


def load_auth_settings(config_path: str | None = None) -> AuthSettings:
    """按 fail-closed 语义加载外置配置。config_path 为空读 env；env 未设置返回关闭态。"""
    path = (config_path if config_path is not None else os.environ.get(AUTH_CONFIG_ENV, "")).strip()
    if not path:
        return AuthSettings(enabled=False)

    if not os.path.isfile(path) or not os.access(path, os.R_OK):
        raise AuthConfigError(f"{AUTH_CONFIG_ENV} 指向的配置文件不可读: {path}")

    # 配置文件所在目录加入 sys.path（Superset PYTHONPATH=pythonpath_dev 同款）：
    # 基础配置可以 `from <override> import *` 加载同目录的用户覆盖/扩展模块。
    config_dir = os.path.dirname(os.path.abspath(path))
    if config_dir not in sys.path:
        sys.path.insert(0, config_dir)

    try:
        spec = importlib.util.spec_from_file_location("dataagent_external_config", path)
        if spec is None or spec.loader is None:
            raise AuthConfigError(f"无法加载认证配置: {path}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except AuthConfigError:
        raise
    except Exception as e:
        raise AuthConfigError(f"认证配置 import 失败: {path}: {e}") from e

    return _build_settings(module, path)


def init_auth(config_path: str | None = None) -> AuthSettings:
    """启动期初始化（main.py 调用）。非法配置抛 AuthConfigError → 启动失败。"""
    global _settings
    with _lock:
        _settings = load_auth_settings(config_path)
    return _settings


def get_auth_settings() -> AuthSettings:
    global _settings
    if _settings is None:
        with _lock:
            if _settings is None:
                _settings = load_auth_settings()
    return _settings


def reset_auth_for_tests() -> None:
    global _settings
    with _lock:
        _settings = None


def is_auth_enabled() -> bool:
    return get_auth_settings().enabled


# ---------------------------------------------------------------------------
# 会话 JWT（HS256）
# ---------------------------------------------------------------------------

def issue_session_token(identity: AuthIdentity, now: float | None = None) -> str:
    import jwt

    cfg = get_auth_settings()
    issued_at = int(now if now is not None else time.time())
    payload = {
        "sub": identity.user_id,
        "name": identity.username,
        "role": identity.role,
        "provider": identity.provider,
        "iat": issued_at,
        "exp": issued_at + cfg.session_ttl_seconds,
    }
    return jwt.encode(payload, cfg.secret_key, algorithm="HS256")


def verify_session_token(token: str) -> AuthIdentity | None:
    import jwt

    cfg = get_auth_settings()
    if not cfg.enabled or not token:
        return None
    try:
        payload = jwt.decode(token, cfg.secret_key, algorithms=["HS256"])
    except jwt.PyJWTError:
        return None
    user_id = str(payload.get("sub") or "")
    if not user_id:
        return None
    role = str(payload.get("role") or ROLE_USER)
    return AuthIdentity(
        user_id=user_id,
        username=str(payload.get("name") or user_id),
        role=role if role in {ROLE_ADMIN, ROLE_USER} else ROLE_USER,
        provider=str(payload.get("provider") or ""),
    )


def resolve_identity(request: Request) -> AuthIdentity | None:
    """从 Cookie（优先）或 Authorization: Bearer 解析已登录身份。auth 关闭返回 None。"""
    cfg = get_auth_settings()
    if not cfg.enabled:
        return None
    token = request.cookies.get(cfg.cookie_name) or ""
    if not token:
        authorization = request.headers.get("Authorization") or ""
        if authorization.lower().startswith("bearer "):
            token = authorization[7:].strip()
    return verify_session_token(token)


# ---------------------------------------------------------------------------
# 登录校验
# ---------------------------------------------------------------------------

def verify_local_admin(username: str, password: str) -> AuthIdentity | None:
    import bcrypt

    cfg = get_auth_settings()
    username = str(username or "").strip()
    for admin in cfg.local_admins:
        if admin["username"] != username:
            continue
        try:
            matched = bcrypt.checkpw(password.encode("utf-8"), admin["password_bcrypt"].encode("utf-8"))
        except ValueError:
            # bcrypt 5 对超过 72 字节的口令抛 ValueError；按认证失败处理而非 500。
            matched = False
        if matched:
            return AuthIdentity(
                user_id=f"{LOCAL_PROVIDER}:{username}",
                username=username,
                role=ROLE_ADMIN,
                provider=LOCAL_PROVIDER,
            )
        break
    logger.warning("Local admin login failed username=%r", username)
    return None


def resolve_oauth_role(provider_name: str, oauth_user_id: str) -> str:
    """按稳定标识 ``<provider>:<sub>`` 提名管理员；username 不参与提权。"""
    cfg = get_auth_settings()
    stable_id = f"{provider_name}:{oauth_user_id}"
    return ROLE_ADMIN if stable_id in cfg.admin_users else ROLE_USER


# ---------------------------------------------------------------------------
# FastAPI 依赖
# ---------------------------------------------------------------------------

def optional_identity(request: Request) -> AuthIdentity | None:
    return resolve_identity(request)


def require_identity(request: Request) -> AuthIdentity:
    identity = resolve_identity(request)
    if identity is None:
        raise HTTPException(status_code=401, detail="Not authenticated")
    return identity


def require_admin(request: Request) -> AuthIdentity | None:
    """管理端点门禁。auth 关闭时 no-op 放行（回滚 = 完整旧语义）。"""
    if not is_auth_enabled():
        return None
    identity = resolve_identity(request)
    if identity is None:
        raise HTTPException(status_code=401, detail="Not authenticated")
    if not identity.is_admin:
        raise HTTPException(status_code=403, detail="Admin role required")
    return identity


# ---------------------------------------------------------------------------
# OAuth state（HMAC 自校验 + 浏览器绑定）
#
# 仅 HMAC 自校验不够：攻击者可以用自己的合法 state/code 构造 callback URL 让
# 受害者打开（登录 CSRF，受害者会被写入攻击者账号的会话）。因此 authorize 时
# 把 state 里的 nonce 同时种进发起登录的浏览器的 HttpOnly 临时 Cookie，
# callback 必须两者一致才接受。
# ---------------------------------------------------------------------------

OAUTH_STATE_COOKIE = "da_oauth_nonce"
OAUTH_STATE_COOKIE_MAX_AGE = _STATE_TTL_SECONDS


def generate_oauth_nonce() -> str:
    return secrets.token_urlsafe(16)


def _state_signature(payload: str) -> str:
    cfg = get_auth_settings()
    return hmac.new(cfg.secret_key.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).hexdigest()


def issue_oauth_state(redirect_path: str = "", nonce: str | None = None) -> str:
    payload = json.dumps(
        {"nonce": nonce or generate_oauth_nonce(), "ts": int(time.time()), "redirect": redirect_path},
        separators=(",", ":"),
    )
    encoded = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")
    return f"{encoded}.{_state_signature(encoded)}"


def verify_oauth_state_binding(payload: dict[str, Any] | None, cookie_nonce: str | None) -> bool:
    """callback 侧校验 state 与发起登录的浏览器绑定（nonce Cookie 一致）。"""
    state_nonce = str((payload or {}).get("nonce") or "")
    cookie_value = str(cookie_nonce or "")
    return bool(state_nonce) and bool(cookie_value) and hmac.compare_digest(state_nonce, cookie_value)


def verify_oauth_state(state: str) -> dict[str, Any] | None:
    try:
        encoded, signature = str(state or "").rsplit(".", 1)
    except ValueError:
        return None
    if not hmac.compare_digest(signature, _state_signature(encoded)):
        return None
    try:
        padded = encoded + "=" * (-len(encoded) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")))
    except Exception:
        return None
    if int(time.time()) - int(payload.get("ts") or 0) > _STATE_TTL_SECONDS:
        return None
    return payload


def sanitize_redirect_path(raw: Any, fallback: str = "/") -> str:
    """只接受同源应用内路径：单个 ``/`` 开头，拒绝 scheme / ``//host`` / 反斜杠变体。"""
    value = str(raw or "").strip()
    # 以单个 "/" 开头即不可能携带 scheme（scheme 必须在位置 0）；
    # "//host" 与反斜杠变体（浏览器会归一化为 "/"）单独拒绝。
    if not value.startswith("/") or value.startswith("//") or "\\" in value:
        return fallback
    return value
