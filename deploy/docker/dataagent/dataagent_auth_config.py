"""DataAgent 认证基础配置（仓库自带，参考 Superset docker/pythonpath_dev 模式）。

本文件由 compose 以目录形式挂载进 dataagent-backend 容器
（`./docker/dataagent` → `/app/docker/dataagent`），并经
`DATAAGENT_AUTH_CONFIG=/app/docker/dataagent/dataagent_auth_config.py` 指定为
认证配置入口。加载器会把本目录加入 `sys.path`，因此文件末尾可以像 Superset
的 `superset_config.py` 一样加载同目录下的用户扩展模块。

使用方式（不要直接改本文件，升级时会被覆盖）：
1. 拷贝 `dataagent_auth_config_docker.py.example` 为
   `dataagent_auth_config_docker.py`（该文件已被 .gitignore 忽略，不会入库），
   在其中填写 SECRET_KEY / LOCAL_ADMINS / OAUTH / ADMIN_USERS 并置
   `AUTH_ENABLED = True`。
2. 重启 dataagent-backend。

语义（fail-closed，详见 docs/design/2026-07-01-dataagent-auth-design.md）：
- 容器内未设置 DATAAGENT_AUTH_CONFIG env → 认证关闭，行为与无认证版本完全一致
  （彻底回滚手段：注释掉 compose 里的该 env）。
- env 已设置但文件不可读 / 配置非法 / 用户扩展有语法错误 / 启用却缺合法
  SECRET_KEY → 服务启动失败，绝不静默降级为无认证。
- 本文件默认 AUTH_ENABLED = False：目录默认挂载也不改变现状（显式关闭态）。
"""
import logging

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 默认值（可被 dataagent_auth_config_docker.py 覆盖）
# ---------------------------------------------------------------------------
AUTH_ENABLED = False

SECRET_KEY = ""

SESSION_TTL_SECONDS = 28800

COOKIE_NAME = "da_session"
COOKIE_SECURE = False
COOKIE_SAMESITE = "lax"

LOCAL_ADMINS = []
OAUTH = {}
ADMIN_USERS = []

# ---------------------------------------------------------------------------
# 加载同目录下的用户扩展（Superset superset_config_docker.py 同款机制）。
# 文件不存在则按上面的默认值运行（认证关闭）；文件存在但有错误会让启动失败
# （fail-closed，符合预期）。
# ---------------------------------------------------------------------------
try:
    import dataagent_auth_config_docker
    from dataagent_auth_config_docker import *  # noqa: F401,F403

    logger.info(
        "Loaded your DataAgent auth override at [%s]",
        dataagent_auth_config_docker.__file__,
    )
except ImportError:
    logger.info("Using default DataAgent auth config (no dataagent_auth_config_docker.py found)")
