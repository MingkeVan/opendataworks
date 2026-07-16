"""认证路由：本地管理员密码登录 + OAuth2 授权码登录。

前缀复用已被两层 nginx 代理的 ``/api/v1/nl2sql``，无需改代理配置。
auth 关闭（env 未设置或显式 AUTH_ENABLED=False）时各端点安全降级：
``/config`` 返回 ``enabled=false``，其余登录端点返回 404。
"""
from __future__ import annotations

import logging
from urllib.parse import urlencode

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response
from pydantic import BaseModel

from core.auth import (
    OAUTH_STATE_COOKIE,
    OAUTH_STATE_COOKIE_MAX_AGE,
    AuthIdentity,
    generate_oauth_nonce,
    get_auth_settings,
    issue_oauth_state,
    issue_session_token,
    require_identity,
    resolve_oauth_role,
    sanitize_redirect_path,
    verify_local_admin,
    verify_oauth_state,
    verify_oauth_state_binding,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/nl2sql/auth", tags=["auth"])

_OAUTH_HTTP_TIMEOUT_SECONDS = 15.0


class LoginRequest(BaseModel):
    username: str
    password: str


def _require_enabled() -> None:
    if not get_auth_settings().enabled:
        raise HTTPException(status_code=404, detail="Authentication is not enabled")


def _identity_payload(identity: AuthIdentity) -> dict:
    return {
        "user_id": identity.user_id,
        "username": identity.username,
        "role": identity.role,
        "provider": identity.provider,
    }


def _set_session_cookie(response: Response, token: str) -> None:
    cfg = get_auth_settings()
    response.set_cookie(
        key=cfg.cookie_name,
        value=token,
        max_age=cfg.session_ttl_seconds,
        path="/",
        # HttpOnly 硬编码：前端无需读取会话令牌，不可配置放宽。
        httponly=True,
        secure=cfg.cookie_secure,
        samesite=cfg.cookie_samesite,
    )


def _clear_session_cookie(response: Response) -> None:
    cfg = get_auth_settings()
    response.delete_cookie(key=cfg.cookie_name, path="/")


@router.get("/config")
async def auth_config():
    cfg = get_auth_settings()
    if not cfg.enabled:
        return {
            "enabled": False,
            "provider_name": "",
            "provider_icon": "",
            "local_login_enabled": False,
            "oauth_login_enabled": False,
        }
    return {
        "enabled": True,
        "provider_name": cfg.oauth.provider_name if cfg.oauth_login_enabled else "",
        "provider_icon": cfg.oauth.icon if cfg.oauth_login_enabled else "",
        "local_login_enabled": cfg.local_login_enabled,
        "oauth_login_enabled": cfg.oauth_login_enabled,
    }


@router.post("/login")
async def login(payload: LoginRequest):
    _require_enabled()
    cfg = get_auth_settings()
    if not cfg.local_login_enabled:
        raise HTTPException(status_code=400, detail="Local login is not configured")
    identity = verify_local_admin(payload.username, payload.password)
    if identity is None:
        raise HTTPException(status_code=401, detail="Invalid username or password")
    response = JSONResponse({"code": 200, "data": _identity_payload(identity)})
    _set_session_cookie(response, issue_session_token(identity))
    return response


@router.get("/me")
async def me(request: Request):
    _require_enabled()
    identity = require_identity(request)
    return {"code": 200, "data": _identity_payload(identity)}


@router.post("/logout")
async def logout():
    _require_enabled()
    response = JSONResponse({"code": 200, "data": {"ok": True}})
    _clear_session_cookie(response)
    return response


@router.get("/oauth/authorize")
async def oauth_authorize(request: Request):
    _require_enabled()
    cfg = get_auth_settings()
    if not cfg.oauth_login_enabled:
        raise HTTPException(status_code=400, detail="OAuth login is not configured")
    redirect_path = sanitize_redirect_path(request.query_params.get("redirect"), fallback="")
    # nonce 同时写进 state 与发起浏览器的 HttpOnly 临时 Cookie，callback 比对，
    # 防止攻击者用自己的 callback URL 给受害者写入攻击者会话（登录 CSRF）。
    nonce = generate_oauth_nonce()
    params = {
        "response_type": "code",
        "client_id": cfg.oauth.client_id,
        "redirect_uri": cfg.oauth.redirect_uri,
        "scope": cfg.oauth.scopes,
        "state": issue_oauth_state(redirect_path, nonce=nonce),
    }
    response = RedirectResponse(url=f"{cfg.oauth.authorize_url}?{urlencode(params)}", status_code=302)
    # nonce Cookie 必须 SameSite=Lax（硬编码，不跟随 cfg.cookie_samesite）：
    # 回调是 IdP → 本站的顶级跨站 GET 导航，SameSite=Strict 的 Cookie 不会被
    # 浏览器带回，会让每次 OAuth 登录的绑定校验必失败。Lax 恰好覆盖顶级跨站 GET，
    # 是 OAuth state Cookie 的标准取值。会话 Cookie da_session 落地后只在同站发送，
    # 故仍沿用 cfg.cookie_samesite。
    response.set_cookie(
        key=OAUTH_STATE_COOKIE,
        value=nonce,
        max_age=OAUTH_STATE_COOKIE_MAX_AGE,
        path="/",
        httponly=True,
        secure=cfg.cookie_secure,
        samesite="lax",
    )
    return response


async def _exchange_code_for_userinfo(code: str) -> dict:
    cfg = get_auth_settings()
    async with httpx.AsyncClient(timeout=_OAUTH_HTTP_TIMEOUT_SECONDS) as client:
        token_response = await client.post(
            cfg.oauth.token_url,
            data={
                "grant_type": "authorization_code",
                "code": code,
                "client_id": cfg.oauth.client_id,
                "client_secret": cfg.oauth.client_secret,
                "redirect_uri": cfg.oauth.redirect_uri,
            },
            headers={"Accept": "application/json"},
        )
        token_response.raise_for_status()
        token_payload = token_response.json()
        access_token = str(token_payload.get("access_token") or "")
        if not access_token:
            raise HTTPException(status_code=502, detail="OAuth token endpoint returned no access_token")

        userinfo_response = await client.get(
            cfg.oauth.userinfo_url,
            headers={"Authorization": f"Bearer {access_token}", "Accept": "application/json"},
        )
        userinfo_response.raise_for_status()
        userinfo = userinfo_response.json()
        if not isinstance(userinfo, dict):
            raise HTTPException(status_code=502, detail="OAuth userinfo endpoint returned unexpected payload")
        return userinfo


@router.get("/oauth/callback")
async def oauth_callback(request: Request):
    _require_enabled()
    cfg = get_auth_settings()
    if not cfg.oauth_login_enabled:
        raise HTTPException(status_code=400, detail="OAuth login is not configured")

    error = request.query_params.get("error")
    if error:
        return RedirectResponse(url=f"/login?error={error}", status_code=302)

    state_payload = verify_oauth_state(request.query_params.get("state") or "")
    if state_payload is None:
        raise HTTPException(status_code=400, detail="Invalid or expired OAuth state")
    # state 必须绑定发起登录的浏览器（authorize 时种下的 nonce Cookie）。
    if not verify_oauth_state_binding(state_payload, request.cookies.get(OAUTH_STATE_COOKIE)):
        raise HTTPException(status_code=400, detail="OAuth state is not bound to this browser")
    code = str(request.query_params.get("code") or "")
    if not code:
        raise HTTPException(status_code=400, detail="Missing authorization code")

    try:
        userinfo = await _exchange_code_for_userinfo(code)
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("OAuth code exchange failed: %s", e)
        return RedirectResponse(url="/login?error=oauth_exchange_failed", status_code=302)

    # 可选 userinfo 映射钩子（Superset custom_sso_security_manager 的
    # oauth_user_info 对应物）：非标准 IdP 在外置配置里提供 OAUTH_USERINFO_MAPPER
    # 自行解析 payload；未配置则按 user_id_field / username_field 平铺取值。
    mapped_role = None
    if cfg.oauth_userinfo_mapper is not None:
        try:
            mapped = cfg.oauth_userinfo_mapper(userinfo)
        except Exception as e:
            logger.exception("OAUTH_USERINFO_MAPPER failed: %s", e)
            return RedirectResponse(url="/login?error=oauth_mapper_failed", status_code=302)
        if not isinstance(mapped, dict):
            logger.error("OAUTH_USERINFO_MAPPER must return a dict, got %r", type(mapped))
            return RedirectResponse(url="/login?error=oauth_mapper_failed", status_code=302)
        oauth_user_id = str(mapped.get("user_id") or "").strip()
        username = str(mapped.get("username") or oauth_user_id).strip()
        role_value = str(mapped.get("role") or "").strip().lower()
        mapped_role = role_value if role_value in {"admin", "user"} else None
    else:
        oauth_user_id = str(userinfo.get(cfg.oauth.user_id_field) or "").strip()
        username = str(userinfo.get(cfg.oauth.username_field) or oauth_user_id).strip()

    if not oauth_user_id:
        logger.error("OAuth userinfo missing user id: keys=%s", sorted(userinfo))
        return RedirectResponse(url="/login?error=oauth_missing_user_id", status_code=302)

    provider = cfg.oauth.provider_name
    identity = AuthIdentity(
        user_id=f"{provider}:{oauth_user_id}",
        username=username,
        # 钩子显式返回 role 时优先生效；否则按 ADMIN_USERS（provider:sub）提名。
        role=mapped_role or resolve_oauth_role(provider, oauth_user_id),
        provider=provider,
    )

    redirect_path = sanitize_redirect_path(
        state_payload.get("redirect"),
        fallback="/",
    )
    response = RedirectResponse(url=redirect_path, status_code=302)
    _set_session_cookie(response, issue_session_token(identity))
    # nonce 为一次性：登录完成即清除。
    response.delete_cookie(key=OAUTH_STATE_COOKIE, path="/")
    return response
