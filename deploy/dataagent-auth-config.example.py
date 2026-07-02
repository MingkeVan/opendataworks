"""DataAgent 认证外置配置示例（Superset superset_config.py 模式）。

使用方式：
1. 拷贝本文件到宿主机，例如 /opt/opendataworks/dataagent-auth-config.py，按需修改。
2. 在 docker-compose 的 dataagent-backend 服务上挂载并指定路径（取消注释）：
     environment:
       DATAAGENT_AUTH_CONFIG: /app/auth_config.py
     volumes:
       - ${DATAAGENT_AUTH_CONFIG_FILE:-./dataagent-auth-config.py}:/app/auth_config.py:ro
3. 重启 dataagent-backend。

语义（fail-closed，详见 docs/design/2026-07-01-dataagent-auth-design.md）：
- 未设置 DATAAGENT_AUTH_CONFIG 环境变量 → 认证关闭，行为与无认证时代完全一致（回滚手段）。
- 设置了环境变量但文件不可读 / 配置非法 / AUTH_ENABLED=True 却缺合法 SECRET_KEY
  → 服务启动失败，绝不静默降级为无认证。
- AUTH_ENABLED=False（本文件默认值）→ 显式关闭，可先挂载验证再启用。
"""

# ---------------------------------------------------------------------------
# 总开关。置 True 前先完成 SECRET_KEY 与至少一种登录方式（LOCAL_ADMINS / OAUTH）。
# ---------------------------------------------------------------------------
AUTH_ENABLED = False

# ---------------------------------------------------------------------------
# 会话 JWT 密钥（HS256）。必须替换为随机值，长度不少于 32 字节：
#   python -c "import secrets; print(secrets.token_urlsafe(32))"
# 保留占位值或过短会导致启动失败。
# ---------------------------------------------------------------------------
SECRET_KEY = "CHANGE-ME-generate-with-secrets.token_urlsafe(32)"

# 会话有效期（秒），默认 8 小时。
SESSION_TTL_SECONDS = 28800

# 会话 Cookie。HttpOnly 由代码硬编码，不可配置。HTTPS 入口置 COOKIE_SECURE = True。
COOKIE_NAME = "da_session"
COOKIE_SECURE = False
COOKIE_SAMESITE = "lax"

# ---------------------------------------------------------------------------
# 本地管理员账号（OAuth 不可用时仍可登录）。password_bcrypt 生成方式：
#   python -c "import bcrypt; print(bcrypt.hashpw(b'your-password', bcrypt.gensalt()).decode())"
# ---------------------------------------------------------------------------
LOCAL_ADMINS = [
    # {"username": "admin", "password_bcrypt": "$2b$12$..."},
]

# ---------------------------------------------------------------------------
# OAuth2 授权码模式（通用 OIDC Provider）。三项核心（client_id / authorize_url /
# token_url）齐全才视为已配置；留空则登录页只提供本地密码登录。
# ---------------------------------------------------------------------------
OAUTH = {
    # 登录按钮显示名，同时作为用户稳定标识的 provider 前缀（见 ADMIN_USERS）。
    "provider_name": "SSO",
    "client_id": "",
    "client_secret": "",
    "authorize_url": "https://sso.example.com/oauth/authorize",
    "token_url": "https://sso.example.com/oauth/token",
    "userinfo_url": "https://sso.example.com/oauth/userinfo",
    "scopes": "openid profile",
    # 必须与 IdP 侧注册的回调一致：
    #   https://<dataagent-host>/api/v1/nl2sql/auth/oauth/callback
    "redirect_uri": "",
    # userinfo 响应里的用户唯一标识与显示名字段。
    "user_id_field": "sub",
    "username_field": "preferred_username",
    # 登录成功后的回跳路径（仅接受同源应用内路径）。
    "post_login_redirect": "/",
}

# ---------------------------------------------------------------------------
# OAuth 用户提名为管理员：条目为 "<provider_name>:<user_id_field值>"（稳定标识，
# 即 provider:sub）。username 可变、不保证唯一，不参与提权。
# ---------------------------------------------------------------------------
ADMIN_USERS = [
    # "SSO:1024",
]
