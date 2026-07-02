# DataAgent OAuth 认证与管理员登录实施计划

- 日期: 2026-07-01
- 关联设计: `docs/design/2026-07-01-dataagent-auth-design.md`
- 影响栈: `dataagent/dataagent-backend/`（FastAPI）、`dataagent/dataagent-frontend/`（Vue SPA + widget bundle）、`deploy/`

## 任务清单

### 后端（dataagent/dataagent-backend/）

- [x] T1 `requirements.txt`：新增 `PyJWT>=2.8.0`、`bcrypt>=4.1.0`。
- [x] T2 `core/auth.py`（新建）：fail-closed 外置配置加载（env `DATAAGENT_AUTH_CONFIG` + importlib）、`SECRET_KEY` 强度校验（非空/非占位/≥32 字节）、HS256 JWT 签发校验、`resolve_identity`（Cookie `da_session` → Bearer 兜底）、`verify_local_admin`（bcrypt）、`ADMIN_USERS`（provider:sub）提名、FastAPI 依赖 `require_admin`（关闭时 no-op）、OAuth state 辅助。
- [x] T3 `api/auth_routes.py`（新建，prefix `/api/v1/nl2sql/auth`）：`GET /config`、`POST /login`、`GET /oauth/authorize`、`GET /oauth/callback`（httpx）、`GET /me`、`POST /logout`；Cookie HttpOnly 硬编码。
- [x] T4 `main.py`：startup 触发配置加载（fail-closed），注册 auth 路由，日志打印开关与路径。
- [x] T5 `api/routes.py:_request_context`：三分支（widget 原样优先 / dataagent 标记消费 cookie，启用且无会话业务路由 401 / 其他永不读 cookie 匿名 portal）。
- [x] T6 `core/topic_task_store.py`：`_normalize_context` 透传 auth 键；`_topic_context_predicate` 按设计 3.5 矩阵；`create_topic` 写 `auth_user_id/auth_username`；行归一化与 `admin_list_topics` 增加 auth 列/过滤。
- [x] T7 迁移 `alembic/versions/20260701_000019_add_topic_auth_owner.py`（down_revision=`20260613_000018`，幂等 `_has_column`/`_has_index`）：`auth_user_id VARCHAR(255) NOT NULL DEFAULT ''`、`auth_username VARCHAR(255) NOT NULL DEFAULT ''`、`idx_da_agent_topic_auth_updated (source, auth_user_id, updated_at)`；schema 只由 Alembic 管理。
- [x] T8 `api/admin_routes.py`：settings_router 挂 router 级 `dependencies=[Depends(require_admin)]`；`/api/v1/dataagent` 拆公开只读（3 个 agents GET）与 admin router（构造时显式 `dependencies=[Depends(require_admin)]`）；`/agents/capabilities` 先于 `/agents/{agent_id}` 注册；新增 `GET /api/v1/nl2sql-admin/topics`。
- [x] T9 `deploy/dataagent-auth-config.example.py`（新建）。
- [x] T10 compose prod/dev + `.env.example` + `deploy/README.md`：注释形式 env/卷；fail-closed 与回滚说明。

### 前端（dataagent/dataagent-frontend/）

- [x] F1 `src/api/nl2sql.js`：`authApi`；`onUnauthorized` 钩子（401）；`streamSdkEvents` `credentials:'include'`；标记头只在 SPA 调用点注入（NL2SqlChatV2 defaultHeaders + `dataagent.js`），不进工厂默认值。
- [x] F1b 文件下载/预览事件代理（artifact card / markdown / fileUrlResolver / sql_export 四链路；rel path 属性转义；HTML 沙箱不放宽；widget 不动）。
- [x] F2 `src/stores/auth.js`（Pinia）。
- [x] F3 `src/views/LoginView.vue`（redirect 消毒）。
- [x] F4 router `/login` + 守卫 + isAdmin 管理页拦截。
- [x] F5/F6 双 client 401 → `/login?redirect=`；侧栏用户名/退出；菜单 isAdmin 隐藏。
- [x] F7 `sourceMode: 'all'`（admin）→ `listAdminTopics()`。
- [x] F8 mockServer `GET /api/v1/nl2sql/auth/config` → `{enabled:false}`。

### 测试

- [x] `tests/test_auth_config.py`：fail-closed 矩阵、弱 SECRET_KEY 启动失败、JWT 往返/过期、bcrypt、显式关闭回归（env set + AUTH_ENABLED=False + 已有 owner 数据 → 旧谓词/放行）。
- [x] `tests/test_auth_routes.py`：login/me/logout、Set-Cookie HttpOnly/SameSite/Secure 断言、authorize 302+state、mock httpx callback、provider:sub 提名、username 不提权。
- [x] 扩展 `test_topic_task_store.py`、`test_widget_runtime_routes.py`（widget 优先、无标记带 cookie 仍匿名、dataagent 标记解析身份）、`test_admin_routes.py`（关闭开放回归、启用 401/200、agents GET 公开、capabilities 遮蔽回归、/topics 契约）。
- [x] 前端 vitest：守卫/isAdmin、auth store、双 client 401、widget 无标记、Blob 链路、沙箱断言、rel path 转义、redirect 消毒、mock auth config。

## 验证

1. `.venv-py313`（或本环境等价 venv）聚焦 pytest；`nvm use` 后 vitest + `npm run build` + `build:widget`。
2. 本地 MySQL + Redis 可用时：`alembic upgrade head`；冒烟 A（auth 关回归：topics 全链路、widget、admin 开放、`/auth/config` enabled=false）；冒烟 B（auth 开：fail-closed 启动失败验证、登录归属、匿名隔离、文件 Blob、admin 全源、settings 401、widget/门户嵌入不变）；4b 回滚回归（移除 env → 旧谓词）。
3. 环境不可用的层面（真实 IdP OAuth 端到端、docker 双层 nginx cookie 链路）在交付说明中显式列出。

## Rollout / Backout

- Rollout：宿主机放置配置文件 → compose 取消注释 env + 卷 → 重启 dataagent-backend →（可选灰度）先 `AUTH_ENABLED=False` 验证挂载，再置 True。
- Backout：移除 `DATAAGENT_AUTH_CONFIG` env（或整段注释回滚）重启；后果=用户名下 portal 会话重新进入共享匿名池（设计 3.5 已定语义）。

## 手工 Runbook（真实 IdP 对接，环境内无法自动验证）

1. 在 IdP 注册应用，取得 client_id/secret，回调地址 `https://<dataagent-host>/api/v1/nl2sql/auth/oauth/callback`。
2. 填入配置脚本 `OAUTH` 段；`AUTH_ENABLED=True`；重启。
3. 浏览器访问独立站点 → 跳登录页 → OAuth 按钮 → IdP 登录 → 回跳后 `GET /api/v1/nl2sql/auth/me` 确认身份与角色。
4. 用 `ADMIN_USERS=["<provider>:<sub>"]` 提名一名管理员，重登后确认 `role=admin` 与全量会话视图。
