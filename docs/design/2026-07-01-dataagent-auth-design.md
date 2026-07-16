# DataAgent OAuth 认证与管理员登录设计（外置 Python 配置脚本）

> 2026-07-16 后续调整：身份展示字段、普通用户菜单和只读权限边界由
> `docs/design/2026-07-16-dataagent-display-name-and-user-navigation-design.md`
> 覆盖；其中领域字段 `username` 已改为 `display_name`。

- 日期: 2026-07-01
- 状态: 已评审（多轮评审意见已吸收）
- 范围: `dataagent/dataagent-backend/`、`dataagent/dataagent-frontend/`、`deploy/`
- 变更规模: 中大型（跨前后端 + schema + 部署配置）
- 关联计划: `docs/plans/2026-07-01-dataagent-auth-plan.md`

## 1. 背景与现状

DataAgent 后端（FastAPI :8900）目前完全无认证：

- 非 widget 请求全部落入共享的 `source='portal'` 匿名池，人人可见所有 portal 会话。
- 管理端点 `/api/v1/nl2sql-admin/*`（读取全部 widget 会话、读写 LLM/DB 配置）与 `/api/v1/dataagent/*` 的写端点无任何鉴权。
- 唯一的"身份"是 widget 的 `X-ODW-*` 头（匿名外嵌场景的多租户隔离，不是认证）。

代码定位：

- 身份唯一入口: `dataagent/dataagent-backend/api/routes.py:_request_context()`。
- 可见性唯一入口: `core/topic_task_store.py:_topic_context_predicate()`；task/queue/schedule 查询均 `JOIN da_agent_topic` 复用同一谓词。
- `da_agent_topic` 已有 `source/website_id/external_user_id/visitor_id`，无 portal 归属列。
- 主门户嵌入页 `frontend/src/views/IntelligentQueryRemoteEmbed.vue` 通过 widget bundle 加载但不带 `websiteId` → 后端按匿名 portal 处理。
- 主门户（Java 侧 `odw-auth`，`odw_session` Cookie）与 DataAgent 相互独立，本设计不集成（Cookie 命名避让即可）。

## 2. 目标

1. DataAgent 自己实现 OAuth2 授权码登录（普通用户）+ 本地管理员密码登录。
2. 所有认证配置放在外置 Python 配置脚本（Superset `superset_config.py` 模式），挂载于宿主机，`main.py` 启动时按 env `DATAAGENT_CONFIG` 路径加载（基础配置名 `dataagent_config.py`，除认证外也承载 `DATAAGENT_SETTINGS` 运行时覆盖）。
3. widget 会话（`X-ODW-Client: widget`）完全不受影响。
4. 主门户嵌入页（匿名 portal 池）保持现状。
5. 普通用户只能看自己的会话；管理员能看所有会话（portal 匿名池 + 用户会话 + widget）。
6. env 未设置时行为与现状完全一致（回滚手段）。

### 非目标

- 不与主门户 `odw-auth` / `odw_session` 集成（预留：Cookie 名避让、契约互不干扰）。
- 不做细粒度 RBAC（仅 `admin` / `user`）。
- v1 不做本地登录失败锁定（记录失败日志）。
- 本期不支持多 OAuth Provider；但外置配置使用 Superset/FAB 的
  `OAUTH_PROVIDERS` 列表外形，启动期强制列表最多一项。

## 3. 核心语义（评审后固化）

### 3.1 Fail-closed 配置加载

- `DATAAGENT_CONFIG` **未设置** → auth 关闭，行为与现状完全一致（唯一合法"关闭"路径，也是回滚手段）。
- env **已设置**但文件不可读 / import 失败 / 配置非法 / `AUTH_ENABLED=True` 却缺 `SECRET_KEY` → **启动直接失败**（raise），绝不静默降级为无认证——防止生产挂载路径 typo / 权限错误把管理端点重新裸奔。
- 合法文件里 `AUTH_ENABLED=False` → 显式关闭（允许，用于灰度前置挂载）。
- `SECRET_KEY` 强度校验（HS256 弱密钥 = 会话可伪造）：非空、非示例占位值、长度 ≥ 32 字节，不满足即启动失败；示例配置用 `python -c "import secrets; print(secrets.token_urlsafe(32))"` 生成。

### 3.2 显式客户端标记（cookie 只对 DataAgent 独立 SPA 生效）

dataagent-frontend 独立 SPA 的所有 API client 显式携带 `X-ODW-Client: dataagent`。后端 `_request_context` 三分支：

| 客户端 | 行为 |
|---|---|
| `X-ODW-Client: widget` | 现有 widget 分支，原样、优先，永不消费 cookie |
| `X-ODW-Client: dataagent` + auth 启用 | 从 `da_session` Cookie（`Authorization: Bearer` 兜底）解析身份；无有效会话的业务路由返回 401；auth 关闭时按旧 portal 语义处理 |
| 其他（无标记，含门户嵌入页） | **永不消费 cookie**，始终匿名 portal 池 |

`/api/v1/nl2sql/auth/*` 与 `require_admin` 依赖直接读 Cookie/Bearer（不依赖标记，管理端点不属于匿名池语义范围）。

标记头**只在 SPA 调用点注入，绝不做 client 工厂默认值**（`createNl2SqlApiClient` 同时被 SPA 和 widget 共用）。

### 3.3 会话令牌

- HS256 JWT，Cookie `da_session`（与门户 `odw_session` 避让），claims `{sub, name, role, provider, exp}`。
- `sub` 命名空间化：本地账号 `local:<username>`，OAuth 用户 `<provider>:<OIDC sub>`。
- Cookie 属性：**HttpOnly 硬编码为 True（不可配置）**、`SameSite` 默认 `lax`、生产 `COOKIE_SECURE=True`。

### 3.4 管理员提名（稳定标识）

- 本地管理员：配置脚本 `LOCAL_ADMINS=[{username, password_bcrypt}]`，bcrypt 校验，OAuth 不可用时仍可登录。
- OAuth 提名：`ADMIN_USERS` 条目匹配 `"<provider>:<sub>"`（稳定标识）；**不用 username 提权**（可变、不保证唯一），username 仅做展示。

### 3.4.1 OAuth Provider 配置契约

- 配置键为 `OAUTH_PROVIDERS=[{...}]`，与 Superset/FAB 的列表和
  `remote_app` 外形对齐；空列表表示不启用 OAuth，多于一项直接
  fail-closed 启动失败。
- 旧的非空扁平 `OAUTH` 不保留兼容分支；加载时立即报迁移错误，
  避免已配 OAuth 被静默忽略后仅剩本地登录。
- 通用字段映射：`name` 映射 provider 键/登录按钮显示名，
  同时用于回调路径和身份命名空间，因此限制为 1-64 位安全 key、保留
  `local` 且不允许 `:` / `/` / `%` 等路径或命名空间分隔字符；
  `icon` 是 Font Awesome 4 class（如 `fa-github`），
  `remote_app.client_id/client_secret/authorize_url/access_token_url/api_base_url/redirect_uri`
  及 `remote_app.client_kwargs.scope` 用于授权码传输；`redirect_uri` 必须是
  绝对 URL，且路径遵循 FAB 约定 `/oauth-authorized/<provider name>`。
- `remote_app.response_type`（默认 `code`）和 `remote_app.grant_type`
  （默认 `authorization_code`）进入实际 authorize/token 请求，不再由传输层写死；
  当前执行路径只实现授权码模式，显式配置其他值会 fail-closed 启动失败；
  `token_key` 可为 Superset 配置迁移保留，DataAgent 仍从标准
  `access_token` 字段解析令牌。
- 不在 `OAUTH_PROVIDERS` 显式配置 userinfo 端点或 payload 字段。与 FAB
  `SecurityManager.oauth_user_info(provider, token_response)` 的扩展边界一致，OAuth
  启用时外置配置必须提供同步 `OAUTH_USER_INFO(provider, token_response,
  oauth_remotes)` callable。DataAgent 用 token response 和
  `remote_app.api_base_url` 构造已绑定 token 的 `oauth_remotes[provider]`，钩子可像
  FAB 一样调用 `.get("userinfo")`，并返回归一化 dict。返回必须含稳定字符串
  `sub`，可含 `preferred_username` / `name` / `email` 及 `role`。DataAgent 固定用
  `sub` 作为身份，显示名按上述 claims 回退，`role` 仅接受 `admin` / `user`。
- 顶层 `userinfo_url` / `user_id_field` / `username_field`、
  `remote_app.userinfo_endpoint` 和旧 `OAUTH_USERINFO_MAPPER` 均不保留兼容分支；
  检测到后 fail-closed 并提示迁移到 `OAUTH_USER_INFO`，避免被静默忽略。
- OAuth state 中的 redirect 缺失或不安全时固定回退到 `/`，
  再由前端 router 选择当前默认页；不再提供 `post_login_redirect`
  配置，避免认证后端绑定可变的页面路径。

### 3.5 可见性谓词矩阵

判定唯一依据 `is_auth_enabled()`，无第三分支：

| 场景 | 谓词（portal 源） |
|---|---|
| `is_auth_enabled()==False`（env 未设置，或显式 `AUTH_ENABLED=False`） | 旧谓词逐字一致 `COALESCE(source,'portal')='portal'`，无 owner 过滤；admin 依赖 no-op 放行 |
| auth 启用 + 匿名/无标记客户端 | `source='portal' AND auth_user_id=''`（列 `NOT NULL DEFAULT ''`，等值可走索引；匿名池不泄漏用户会话） |
| auth 启用 + 登录普通用户 | `source='portal' AND auth_user_id=%s` |
| auth 启用 + admin 聊天列表 | portal 全量；跨源全量走管理端点（`context=None`） |
| widget | 原样不动（无论 auth 开关） |

关闭后果（已定）：若曾启用 auth 后关闭，用户名下的 portal 会话会重新出现在共享匿名池——恢复"无认证共享池"的完整旧语义，不保留半套隔离分支。

### 3.6 文件下载/预览链路

浏览器导航无法附加自定义头，SPA 侧统一用事件代理：渲染时把文件链接以转义后的 `data-rel-path` 标注（禁止把原始 rel path 直接写进 HTML 属性；HTML 属性转义 + `encodeURIComponent`），消息容器上点击代理 `preventDefault` → 带标记头/cookie 的 fetch → Blob → `URL.createObjectURL`（预览 / `a[download]`，用后 revoke）。覆盖 artifact card、markdown rewrite、`ToolOutputRenderer.fileUrlResolver`、sql_export 四条链路；widget 保留现有裸链接。HTML 预览继续走 sandbox iframe（无 `allow-scripts`/`allow-same-origin`），不得放宽。签名 URL 记为后续备选。

### 3.7 开放重定向防护

登录页 `?redirect=` 与 OAuth 回跳只接受同源应用内路径：必须以单个 `/` 开头，拒绝绝对 URL、`//host` 协议相对形式、含 `\` 变体；非法值回落独立 DataAgent 的 `/chat`。

## 4. 数据模型

仅一处 DDL（Alembic 迁移 `20260701_000019_add_topic_auth_owner`，schema 只由 Alembic 管理，不进运行时初始化）：

```sql
ALTER TABLE da_agent_topic
  ADD COLUMN auth_user_id VARCHAR(255) NOT NULL DEFAULT '',
  ADD COLUMN auth_username VARCHAR(255) NOT NULL DEFAULT '',
  ADD INDEX idx_da_agent_topic_auth_updated (source, auth_user_id, updated_at);
```

- `VARCHAR(255)`：值为 `provider:sub`，OIDC sub 可能较长。
- 索引带 `source` 前导列，匹配主要谓词。
- 不需要用户表/会话表：JWT + 外置配置承载登录态；旧数据默认 `auth_user_id=''` 自然进入匿名池。

## 5. 接口设计

### 5.1 认证接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/config` | 公开：`{enabled, provider_name, provider_icon, local_login_enabled, oauth_login_enabled}` |
| POST | `/login` | 本地管理员密码登录，成功 Set-Cookie `da_session` |
| GET | `/oauth/authorize` | 读配置拼授权 URL + state cookie，302 |
| GET | `/oauth-authorized/{provider}`（根路径） | FAB 约定回调；校验 provider/state 后 code→token→`OAUTH_USER_INFO`→按 `ADMIN_USERS` 定角色→Set-Cookie→302 |
| GET | `/me` | 当前身份（未登录 401） |
| POST | `/logout` | 清 Cookie |

### 5.2 管理端点门禁

- `/api/v1/nl2sql-admin/*`（settings、model-detections、widget-topics 等）：router 级 `dependencies=[Depends(require_admin)]`。
- `/api/v1/dataagent/*` 拆分：
  - 公开只读 router：`GET /agents`、`GET /agents/{agent_id}`、`GET /agents/{agent_id}/slash-commands`（widget 与匿名嵌入依赖，必须保持公开）。
  - admin router（`APIRouter(dependencies=[Depends(require_admin)])`）：agent CRUD、capabilities、data-scope、skill 文档 CRUD/导入导出等。
  - 路径遮蔽：静态 `/agents/capabilities` 必须先于动态 `/agents/{agent_id}` 注册，附回归测试。
- 新增 `GET /api/v1/nl2sql-admin/topics`（source ''|portal|widget、auth_user_id、keyword、分页）复用 `admin_list_topics`，供管理员全量会话视图。
- `require_admin` 在 auth 关闭时 no-op 放行（回滚 = 完整旧语义）。

## 6. 前端设计（dataagent-frontend 独立 SPA）

- `authApi`（config/login/me/logout/authorize URL）；`X-ODW-Client: dataagent` 只在 SPA 调用点注入。
- 两个 client（`nl2sql.js` 工厂 + `dataagent.js`）统一 `onUnauthorized` 钩子 → `/login?redirect=`（消毒后）。
- Pinia `stores/auth.js`：`bootstrap()`、`isAuthenticated`、`isAdmin`（auth 关闭时为 true）、`loginLocal`、`logout`。
- `LoginView.vue`：密码表单 + 条件 OAuth 按钮；OAuth 按钮用
  Font Awesome 4 渲染 `provider_icon`（空值则仅文字）；redirect 消毒。
- 路由守卫（仅 auth 启用时生效）+ 管理页路由/菜单按 `isAdmin` 拦截/隐藏。
- `NL2SqlChatV2.vue` 的 `sourceMode` 增加 `'all'`（仅 admin），显示来源徽标 + 归属人。
- 文件下载/预览事件代理（3.6）。
- widget bundle：不设标记、不设钩子、无 router → 完全不受影响。

## 7. 部署（Superset docker/pythonpath_dev 同款目录模式）

- `deploy/docker/dataagent/` 目录由 compose 整体挂载进 dataagent-backend（`/app/docker/dataagent:ro`），env 默认指定 `DATAAGENT_CONFIG=/app/docker/dataagent/dataagent_config.py`；加载器把该目录加入 `sys.path`。
- 目录内容：
  - `dataagent_config.py`：仓库自带基础配置，默认 `AUTH_ENABLED=False`（挂载即"显式关闭"态，行为与现状一致）；文件末尾 `from dataagent_config_docker import *` 加载同目录用户覆盖（不存在则跳过，Superset `superset_config_docker.py` 同款机制）。除认证外也可用 `DATAAGENT_SETTINGS = {"<config.py Settings 字段>": 值}` 覆盖运行时配置（启动期校验字段名，未知字段 fail-closed）。
  - `dataagent_config_docker.py.example`：用户覆盖示例（SECRET_KEY/bcrypt 生成提示、Superset/FAB 形态的单项 `OAUTH_PROVIDERS` 通用 OIDC 样例、Font Awesome icon、`provider:sub` 提名写法、`DATAAGENT_SETTINGS` 样例）；用户拷贝为 `dataagent_config_docker.py` 填写。
  - `custom_sso_user_info.py.example`：`OAUTH_USER_INFO` 钩子示例（Superset `custom_sso_security_manager.py` 的 `oauth_user_info` 对应物）——接收 provider、token response 与已绑定 token 的 `oauth_remotes`，通过 `oauth_remotes[provider].get("userinfo")` 获取并归一化；返回 `role` 时优先于 `ADMIN_USERS`；钩子运行期异常只使该次登录失败，不影响服务。
  - `.gitignore`：忽略一切用户文件（覆盖配置、自定义扩展模块如自研 SSO 适配），只保留自带文件与 `.example`。
- `deploy/docker/nginx/`：两个前端 nginx 配置的宿主机副本 + compose 中注释式挂载；默认仍用镜像内构建版本（单一主路径），需要宿主机管理时取消注释。DataAgent 独立站点的镜像内配置与宿主机副本均以 `^~` 显式代理根路径
  `/oauth-authorized/` 到 dataagent-backend，开发态 Vite 配置同样代理该路径，
  保证回调不会被 SPA fallback 或静态资源正则吞掉。主门户/widget 仍维持匿名语义，
  不新增 OAuth 回调入口。
- 启用 = 拷贝 example 填写后重启（不改 compose）；彻底回滚 = 注释掉 env 重启；后果见 3.5。用户覆盖文件有语法错误时启动失败（fail-closed 覆盖到用户扩展层）。

## 8. 安全考量

- HttpOnly Cookie 防 XSS 窃取；SameSite=Lax + OAuth state（HMAC 自校验 + 浏览器绑定：authorize 把 nonce 同步种进发起浏览器的 HttpOnly 临时 Cookie `da_oauth_nonce`，callback 比对一致才接受，且签名 state 绑定 provider，防登录 CSRF/跨 Provider 事务错配）；生产 Secure。`da_oauth_nonce` 的 SameSite **硬编码为 Lax、不跟随 `cookie_samesite`**——回调是 IdP→本站的顶级跨站 GET 导航，Strict Cookie 不会被带回，否则会话 Cookie 配成 Strict 时每次 OAuth 登录必失败；会话 Cookie `da_session` 落地后仅同站发送，故仍沿用配置值。
- Fail-closed 加载防误配置裸奔（覆盖层：基础配置用 find_spec 判存在后直接 import，覆盖文件内部 import 失败同样启动失败，不吞 ImportError）；SECRET_KEY 强度校验防弱密钥伪造；OAuth 启用时 `OAUTH_USER_INFO` callable、`api_base_url` 与 `redirect_uri` 纳入启动期必填校验，并校验回调路径严格匹配 `/oauth-authorized/<provider>`（避免上线后回调必失败）；同步 userinfo 钩子在线程中执行，remote client 强制 15 秒 HTTP 超时，避免阻塞事件循环；bcrypt 超长口令（>72 字节 ValueError）按认证失败处理而非 500。
- 稳定标识提权（provider:sub）防 username 漂移/碰撞提权。
- 开放重定向防护（3.7）；文件 rel path 属性转义防注入；HTML 预览沙箱不放宽。
- CORS `allow_origins=["*"]`+credentials：Starlette 会回显 Origin；由 SameSite=Lax + HttpOnly + 同源 nginx 链路缓解，预留 `AUTH_ALLOWED_ORIGINS` 后续项。

## 9. 取舍

- DataAgent 自持认证（而非复用门户 `odw_session`）：DataAgent 可独立部署（widget 外嵌场景），且门户 OAuth 尚未实现；Cookie 避让保留后续集成空间。
- 外置 Python 配置脚本（而非存库/env）：满足"挂载宿主机、启动加载"的运维诉求，Superset 同款模式；fail-closed 弥补文件挂载的误配置风险。
- JWT 无服务端会话表：最小改动；登出=清 Cookie，令牌自然过期（TTL 默认 8h）。
- 显式客户端标记（而非"有 cookie 就认"）：保住门户嵌入页匿名语义，同主机名跨端口 Cookie 共享不再影响行为。
