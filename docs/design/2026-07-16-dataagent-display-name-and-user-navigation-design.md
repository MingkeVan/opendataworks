# DataAgent 显示名称与普通用户导航设计

- 日期: 2026-07-16
- 状态: 已实施
- 范围: `dataagent/dataagent-backend/`、`dataagent/dataagent-frontend/`
- 变更规模: 中等（认证契约 + 前后端权限边界）
- 关联设计: `docs/design/2026-07-01-dataagent-auth-design.md`
- 关联计划: `docs/plans/2026-07-16-dataagent-display-name-and-user-navigation-plan.md`

## 1. 背景与问题

当前 DataAgent 身份对象使用 `user_id + username + role + provider`：

- OAuth `sub` 经 Provider 命名空间化后形成稳定 `user_id`，用于授权、会话归属和数据隔离。
- `username` 按 `preferred_username -> name -> email -> sub` 回退，实际承担的是显示名称职责。
- `username` 同时出现在 JWT、`/auth/me`、请求上下文、Topic 归属快照和前端用户区域，容易被误解为稳定登录名或授权依据。
- 普通用户目前只有 Chat 菜单；Skills、智能体、模型管理和 Widget 接入均被视为管理员页面。
- Skills 与智能体现有页面并非纯展示页，包含导入、启停、编辑、回滚、新建和删除等管理操作，不能仅通过取消菜单隐藏直接向普通用户开放。
- 左下角用户下拉菜单显示“管理员”或“普通用户”角色描述。该描述对普通用户没有操作价值，并使用户区域显得像权限调试信息。

## 2. 目标与非目标

### 2.1 目标

1. 在领域模型中明确区分稳定身份 `user_id` 与可变显示名称 `display_name`。
2. 保证显示名称变更不会改变授权、管理员提名、Topic 归属或会话可见性。
3. 普通用户可见并可进入 Chat、Skills、智能体三个菜单。
4. 普通用户可浏览 Skill 和智能体信息，并可从智能体页面发起对话；管理操作仍仅管理员可用。
5. 模型管理和 Widget 接入继续仅管理员可见、可访问。
6. 移除左下角用户下拉菜单中的角色描述，仅保留显示名称和退出登录。
7. auth 关闭时继续保持既有兼容语义，不引入新的部署配置。

### 2.2 非目标

- 不引入菜单配置表、权限配置文件或通用 RBAC。
- 不新增角色，仍只有 `admin` 和 `user`。
- 不调整 `ADMIN_USERS`、`LOCAL_ADMINS` 或 OAuth `role` 的现有解析规则。
- 不调整普通用户与管理员的 Topic 可见性规则。
- 不允许普通用户导入、启停、编辑、回滚、导出或卸载 Skill。
- 不允许普通用户新建、编辑或删除智能体；普通用户可以查看系统提示词、Skills、工具、MCP、数据范围等配置，但不返回 `env_vars`。
- 不修改本地管理员登录请求中的 `username`。该字段表示登录凭据名称，语义明确，不属于显示名称重命名范围。

## 3. 身份字段设计

### 3.1 领域模型

认证后的统一身份模型调整为：

| 字段 | 语义 | 稳定性 | 使用范围 |
|---|---|---|---|
| `user_id` | `<provider>:<sub>`，本地账号为 `local:<username>` | 稳定 | 授权、管理员提名、Topic 归属、数据隔离 |
| `display_name` | 面向用户显示的名称 | 可变 | 页面显示、Topic 归属人快照、审计可读性 |
| `role` | `admin` / `user` | 会话期内稳定 | 菜单、路由和后端接口授权 |
| `provider` | 身份来源 | 稳定 | 诊断和身份来源识别 |

`AuthIdentity.username` 重命名为 `AuthIdentity.display_name`。任何权限判断不得读取 `display_name`。

### 3.2 OAuth 归一化

`OAUTH_USER_INFO(provider, token_response, oauth_remotes)` 仍返回标准化 claims，必须包含 `sub`。显示名称按以下单一路径解析：

```text
display_name -> preferred_username -> name -> email -> sub
```

- 新增可选的归一化字段 `display_name`，便于自定义 SSO 钩子直接表达最终展示值。
- 现有只返回 `preferred_username`、`name` 或 `email` 的钩子无需修改。
- 最终结果写入领域字段 `display_name`；claims 原字段不继续向业务层传播。
- `sub` 始终独立构造 `user_id`，显示名称缺失或变化均不改变用户身份。
- 本地管理员的 `display_name` 默认使用其登录 `username`。

### 3.3 JWT 与认证接口

JWT 继续使用标准 claim `name` 存放 `display_name`，避免引入非标准的重复 claim：

```json
{
  "sub": "SSO:1024",
  "name": "Alice Zhang",
  "role": "user",
  "provider": "SSO"
}
```

`GET /api/v1/nl2sql/auth/me` 的规范响应调整为：

```json
{
  "user_id": "SSO:1024",
  "display_name": "Alice Zhang",
  "role": "user",
  "provider": "SSO"
}
```

前后端应在同一发布单元内切换到 `display_name`。JWT 的物理 claim 仍为 `name`，因此升级前签发且尚未过期的会话可以继续解析，无需强制用户重新登录。

### 3.4 Topic 归属快照

请求上下文与 Topic API 使用明确字段：

- `auth_user_id`: 稳定归属 ID。
- `auth_display_name`: 创建 Topic 时的显示名称快照。
- `auth_role`: 当前请求角色，仅用于可见性判定，不持久化为用户画像。

数据库现有 `da_agent_topic.auth_username` 暂不改名。它仅作为兼容的物理存储列，由 `topic_task_store` 在持久化边界映射：

```text
context.auth_display_name -> da_agent_topic.auth_username -> response.auth_display_name
```

这样可避免为纯语义调整增加数据库迁移和滚动发布顺序要求，同时业务代码与接口不再继续传播 `username` 概念。历史 Topic 的显示名称快照原样保留。

## 4. 菜单与页面权限

### 4.1 权限矩阵

| 页面/能力 | 普通用户 | 管理员 |
|---|---:|---:|
| Chat | 使用 | 使用 |
| Skills 列表与只读详情 | 查看 | 查看 |
| Skill 内容、版本和差异 | 查看 | 查看 |
| Skill 导入、导出、启停、编辑、回滚、卸载 | 禁止 | 允许 |
| 智能体列表与只读详情 | 查看 | 查看 |
| 从智能体发起 Chat | 允许 | 允许 |
| 智能体新建、编辑、删除 | 禁止 | 允许 |
| 系统提示词、Skills、工具、MCP、数据范围 | 只读 | 查看和编辑 |
| 智能体 `env_vars` | 不返回 | 查看和编辑 |
| 模型管理 | 不可见、不可访问 | 允许 |
| Widget 接入 | 不可见、不可访问 | 允许 |
| Widget/全部会话筛选 | 不可见、不可访问 | 允许 |

这里的“普通用户可以看见 Skills 和智能体”定义为可浏览和使用，不等于取得管理权限。

### 4.2 菜单与路由

- Chat、Skills、智能体菜单不再放在 `authStore.isAdmin` 条件块内。
- 模型管理、Widget 接入继续使用 `authStore.isAdmin` 控制显示。
- `/skills`、`/skills/:folder`、`/agents` 和普通用户安全详情路由移除 `adminOnly`。
- `/models`、`/widget-access` 继续保留 `meta.adminOnly: true`。
- 前端路由守卫只负责页面入口体验；后端接口权限仍是最终授权边界。
- auth 关闭时 `isAdmin=true` 的兼容行为保持不变，所有既有管理能力仍可用。

### 4.3 Skills 页面

普通用户页面进入只读模式：

- 保留列表、搜索、查看详情、内容阅读、版本记录和差异查看。
- 隐藏导入按钮、启停开关、下载/导出、卸载、保存、回滚等管理操作。
- 编辑器以只读模式呈现，不通过“按钮禁用 + 调接口失败”表达权限。

后端拆分 Skills 路由权限：

- 新增 `require_user` 依赖：auth 启用时要求有效身份，auth 关闭时与 `require_admin` 一样兼容放行；不修改 `/auth/me` 使用的严格 `require_identity` 语义。
- 列表、文档详情等 GET 端点，以及无副作用的版本比较 POST 端点，统一依赖 `require_user`。
- 导入、导出、启停、更新、回滚、卸载端点继续依赖 `require_admin`。
- Widget 和匿名嵌入不获得 Skill 文档读取权限。

### 4.4 智能体页面

普通用户页面采用“智能体目录”语义：

- 保留智能体名称、描述、系统提示词、Skills、工具、MCP、数据范围、最大轮次、预设问题和“开启对话”。
- 详情入口显示为“查看详情”，不显示“查看编辑”。
- 隐藏新建、保存、编辑和删除操作。
- 不向普通用户返回 `env_vars`；详情中的其他配置以只读方式展示。

当前公开 `GET /api/v1/dataagent/agents` 与 `GET /api/v1/dataagent/agents/{agent_id}` 使用完整 `AgentProfile`，其中包含 `system_prompt` 和 `env_vars`。本次必须同步收紧响应模型：

- 匿名公开端点返回安全的 `AgentCatalogProfile`，仅包含目录展示和 Chat 所需字段。
- 新增依赖 `require_user` 的普通用户详情端点，返回不含 `env_vars` 的 `AgentReadableProfile`；该模型保留系统提示词、Skills、工具、MCP、数据范围等只读配置。
- 管理员详情页通过独立的 admin-only 配置端点读取完整 `AgentProfile`。
- Agent 创建、更新、删除和 capabilities/data-scope 管理端点继续保持 `require_admin`。
- Widget 继续使用公开安全目录，不依赖完整配置响应。

## 5. 用户区域

左下角用户区域调整为：

- 触发器显示 `currentUser.display_name`。
- 下拉菜单仅保留“退出登录”。
- 删除当前禁用的“管理员/普通用户”描述项，不针对任一角色显示角色文字。
- 权限状态继续由菜单可见性和后端授权体现，不把角色标签作为用户画像展示。

## 6. 接口与兼容影响

| 契约 | 变更 |
|---|---|
| `AuthIdentity` | `username` 重命名为 `display_name` |
| JWT | 继续使用 `name`，内容语义明确为显示名称 |
| `/auth/me` | `username` 改为 `display_name` |
| 请求上下文 | `auth_username` 改为 `auth_display_name` |
| Topic API | `auth_username` 改为 `auth_display_name` |
| Topic 数据库 | 物理列 `auth_username` 保留，由 Store 单点映射 |
| Agent API | 匿名目录 DTO、登录用户只读 DTO、管理员完整配置 DTO 分层 |
| Skill API | GET 浏览端点与 admin 写端点拆分权限依赖 |

DataAgent 前后端应作为同一版本发布。若部署环境支持前后端独立滚动升级，则先部署兼容读取新旧字段的前端，再部署只返回新字段的后端；兼容逻辑只允许存在于 API 消费边界，不进入领域模型。

## 7. 安全与约束

- `user_id` 是唯一的身份、授权和数据隔离依据；`display_name` 永远不参与角色判断或 Topic 查询谓词。
- 普通用户菜单开放必须同时具备后端 GET/写权限拆分，不能只修改 `v-if` 或路由 `meta`。
- Agent 匿名公开响应不得包含系统提示词、MCP、数据范围或环境变量；普通用户只读响应可包含系统提示词、MCP 和数据范围，但不得包含 `env_vars`。
- Skills 读取只对已认证的 DataAgent SPA 用户开放，不扩大到 Widget 或匿名门户客户端。
- 普通用户界面隐藏管理操作，后端仍对所有写端点执行 `require_admin`，防止绕过前端直接调用。
- 模型配置、Widget 配置和跨用户会话审计权限不变。

## 8. 验证设计

### 8.1 后端

- OAuth claims 回退后生成 `display_name`，但 `user_id` 始终只由 `provider + sub` 构造。
- 同一 `sub` 使用不同显示名称登录时，Topic 归属和管理员提名结果不变。
- 旧 JWT 的 `name` claim 可恢复为 `AuthIdentity.display_name`。
- `/auth/me` 返回 `display_name`。
- Topic Store 将 `auth_display_name` 正确映射到历史 `auth_username` 物理列并返回新字段。
- 普通用户可调用 Skill 只读端点，调用导入、更新、回滚、启停、导出和卸载端点返回 403。
- 匿名 Agent API 不返回 `system_prompt`、`env_vars`、MCP 和数据范围；普通用户只读详情包含可读配置但不含 `env_vars`；管理员配置端点仍返回完整配置。

### 8.2 前端

- 普通用户可见 Chat、Skills、智能体，不可见模型管理和 Widget 接入。
- 普通用户可进入 Skills/智能体页面，直接访问模型管理和 Widget 接入仍重定向 `/chat`。
- 普通用户 Skills 页面不渲染任何写操作，编辑器为只读。
- 普通用户智能体页面只显示浏览和开启对话能力。
- 管理员仍可执行现有 Skills 和智能体管理流程。
- 用户区域显示 `display_name`，下拉菜单不再出现“普通用户”或“管理员”描述。

### 8.3 本地烟测

该变更跨认证、前端、后端和接口权限边界。实现后除定向单测与前端构建外，至少使用一个普通用户和一个管理员执行本地烟测：

1. 普通用户登录，检查三个菜单、只读页面、Chat 和写接口 403。
2. 管理员登录，检查五个菜单及 Skills/智能体管理操作。
3. 使用 OAuth 用户创建 Topic，验证列表显示 `display_name` 且只能查看自己的会话。
4. 检查公开 Agent 响应中不存在敏感配置字段。

## 9. 发布与回退

- 不新增部署配置，不执行数据库迁移。
- 前后端以同一 DataAgent 版本发布；JWT 兼容现有会话。
- 回退时恢复上一版本前后端即可，Topic 物理数据没有变化。
- 若普通用户浏览端点出现越权风险，可先回退前端菜单与只读 GET 路由开放，不影响原有管理员管理路径和 Topic 数据。
