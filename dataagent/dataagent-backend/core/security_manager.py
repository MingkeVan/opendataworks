"""DataAgent 安全管理器基类（Superset ``SecurityManager`` 扩展模型的对应物）。

部署方在外置配置目录（`deploy/docker/dataagent/`，容器内位于 ``sys.path``）
放一个子类文件，并在 ``dataagent_config_docker.py`` 中挂上：

    from custom_sso_security_manager import CustomSsoSecurityManager
    CUSTOM_SECURITY_MANAGER = CustomSsoSecurityManager

即可覆写认证管线中的具名扩展点；未覆写的方法沿用默认实现（= 仓库默认行为）。
写法与 Superset 的 ``CUSTOM_SECURITY_MANAGER`` / ``oauth_user_info`` 一致。

本模块模块级不 import core.auth（core.auth 在配置加载期需要引用本基类做
子类校验），身份对象与角色常量在方法内部惰性导入。
"""
from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover - 仅类型标注
    from core.auth import AuthIdentity, AuthSettings

logger = logging.getLogger(__name__)


class DataAgentSecurityManager:
    """认证扩展点集合。每个方法都有默认实现，子类按需覆写。"""

    def __init__(self, settings: "AuthSettings") -> None:
        self.settings = settings

    # ------------------------------------------------------------------
    # OAuth：userinfo → 用户字段（Superset oauth_user_info 对应物）
    # ------------------------------------------------------------------
    def oauth_user_info(self, provider: str, userinfo: dict[str, Any]) -> dict[str, Any]:
        """把 userinfo 端点的原始响应映射成用户字段。

        返回 dict：
        - ``user_id``（必填）：用户稳定唯一标识（如 sub），会被拼成
          ``<provider>:<user_id>`` 作为会话归属与 ADMIN_USERS 提名键；
        - ``username``（可选）：显示名，缺省用 user_id；
        - ``role``（可选）：``admin``/``user``，返回则优先于 resolve_role。

        默认实现按 OAUTH 配置的 user_id_field / username_field 平铺取值；
        非标准 IdP（嵌套 payload、自定义字段）覆写本方法。
        运行期抛异常只使该次登录失败（跳回登录页），不影响服务。
        """
        oauth = self.settings.oauth
        user_id = str(userinfo.get(oauth.user_id_field) or "").strip()
        username = str(userinfo.get(oauth.username_field) or user_id).strip()
        return {"user_id": user_id, "username": username}

    # ------------------------------------------------------------------
    # 角色解析（oauth_user_info 未显式返回 role 时调用）
    # ------------------------------------------------------------------
    def resolve_role(self, provider: str, user_id: str, userinfo: dict[str, Any]) -> str:
        """默认按 ADMIN_USERS 的稳定标识 ``<provider>:<user_id>`` 提名管理员；
        username 不参与提权。需要按 IdP 组/角色字段提权时覆写。"""
        from core.auth import ROLE_ADMIN, ROLE_USER

        stable_id = f"{provider}:{user_id}"
        return ROLE_ADMIN if stable_id in self.settings.admin_users else ROLE_USER

    # ------------------------------------------------------------------
    # 本地密码登录
    # ------------------------------------------------------------------
    def verify_local_login(self, username: str, password: str) -> "AuthIdentity | None":
        """默认校验 LOCAL_ADMINS（bcrypt）。对接 LDAP 等其他本地源时覆写。"""
        import bcrypt

        from core.auth import LOCAL_PROVIDER, ROLE_ADMIN, AuthIdentity

        username = str(username or "").strip()
        for admin in self.settings.local_admins:
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
        logger.warning("Local login failed username=%r", username)
        return None

    # ------------------------------------------------------------------
    # 登录成功回调（通知型钩子，不是门禁）
    # ------------------------------------------------------------------
    def on_login(self, identity: "AuthIdentity") -> None:
        """登录成功后调用（本地与 OAuth 都会触发）。默认 no-op。
        可用于登录审计、部门信息同步等。抛异常只记日志，不阻断登录。"""
        return None
