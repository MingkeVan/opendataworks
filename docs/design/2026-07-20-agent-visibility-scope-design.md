# 助手可见范围设计

## 现状

`da_agent_profile` 中的助手对所有用户可见:

- 助手目录接口 `GET /api/v1/dataagent/agents` 与详情、slash-commands 接口挂在无鉴权的 `agents_public_router` 上,匿名可访问(Chat 页与 Widget 嵌入依赖),且返回全部助手,无任何用户过滤。
- 助手配置模型(`AgentProfileBase` 等)与 `da_agent_profile` 表没有可见性、用户白名单或用户组字段。
- 话题创建 `_require_agent_profile` 只校验助手存在,任何调用方都可以用任意 `agent_id` 新建会话。
- 管理端可以新建/编辑/删除助手,但没有"对谁可见"的配置项;只能通过全局增删影响所有用户。

## 目标

- 管理员可按助手配置可见范围:全部用户(含匿名嵌入)/ 仅登录用户 / 指定用户。
- 可见性由后端强制执行:不可见的助手既不出现在列表,也不能通过直连接口读取详情或新建会话,不能只靠前端隐藏。
- 默认值保持现状(全部可见),存量部署升级后行为不变。
- 为后续"用户组"扩展预留数据结构,本期不实现组匹配。

## 非目标

- 用户组匹配。仓库当前无用户组原语(无组表、会话 JWT 无 `groups` claim);IdP 组信息需先贯通 `OAUTH_USER_INFO` → `AuthIdentity` → 会话 JWT,属后续独立变更。本期仅在可见性 JSON 中预留 `allowed_groups` 字段。
- 已有会话的追溯拦截。话题创建时冻结 agent 快照,消息投递/任务提交只信任话题绑定,本期维持该架构:可见范围变更只影响新建会话,已有会话继续可用。
- 管理端助手 CRUD 的权限模型变化(仍为 admin 全量可管)。

## 方案

### 数据模型

`da_agent_profile` 新增 `visibility_json TEXT` 列(Alembic `20260720_000020_add_agent_visibility.py`,幂等守卫,存量回填 mode=all),规范化结构复用 `data_scope_json` 的全链路模式:

```json
{
  "mode": "all | authenticated | selected",
  "allowed_users": ["SSO:1024", "local:alice"],
  "allowed_groups": []
}
```

- `mode` 缺失或非法时按 `all` 处理(读侧容错,列表不因脏数据失败);API 入参走 strict 校验,未知 mode 拒绝。
- `allowed_users` 存放命名空间化稳定用户 ID(`local:<用户名>` / `<provider>:<sub>`,与 `ADMIN_USERS` 同格式):去重、去空、单条 ≤255 字符、上限 200 条。
- `allowed_groups` 同步规范化持久化(单条 ≤128 字符、上限 50 条),本期匹配逻辑不消费。
- 两个列表与 `mode` 解耦持久化,管理端切换 mode 不丢失已配置名单。

### 可见性判定

新模块 `core/agent_visibility.py`(仿 `core/data_scope.py`):

- `normalize_agent_visibility(raw, strict=False)`:规范化;strict 用于 API 入参。
- `agent_visible_to(profile, identity)` 判定顺序:
  1. auth 未启用(`is_auth_enabled()` 为假)→ 可见(与 `require_admin`/`require_user` 的 fail-open 回滚约定一致);
  2. 管理员 → 可见;
  3. `mode=all` → 可见;
  4. 匿名(无身份)→ 不可见;
  5. `mode=authenticated` → 可见;
  6. `mode=selected` → `identity.user_id` 在 `allowed_users` 中才可见。
- `filter_visible_agent_profiles(profiles, identity)` 供列表接口复用。

### 强制点

| 接口 | 行为 |
| --- | --- |
| `GET /agents`(匿名目录) | 按调用方身份过滤列表 |
| `GET /agents/{id}`、`GET /agents/{id}/slash-commands`(匿名) | 不可见时返回 404 `agent not found`,与不存在完全一致,防存在性探测 |
| `GET /agents/profiles`、`GET /agents/{id}/profile`(登录用户) | 同上过滤 / 404 |
| `POST /api/v1/nl2sql/topics`(话题创建) | `_require_agent_profile` 增加可见性校验,不可见时与不存在同文案报 400;空 `agent_id` 回落默认助手的路径同样受控 |
| 管理端 CRUD / `configuration` | 不过滤(admin 全量),create/update 接受 `visibility` 字段 |

公开目录接口的身份解析遵循既有三分支客户端语义(`docs/design/2026-07-01-dataagent-auth-design.md` 3.2):仅 `X-ODW-Client: dataagent` 且 auth 启用时机会性消费会话 Cookie/Bearer(无效会话降级匿名而非 401,公开接口保持公开);Widget 与门户嵌入页保持匿名,只能看到 `mode=all` 的助手。

### 响应分层

- `AgentCatalogProfile`(匿名 DTO)不新增字段,不泄露任何可见性配置。
- `AgentReadableProfile`(登录用户)仅新增 `visibility_mode` 摘要,不泄露 `allowed_users` 名单。
- `AgentProfile`(admin)返回完整 `visibility`。
- agent 快照(`build_agent_snapshot`)不含 visibility:运行时不需要,避免快照携带过期副本。

### 指定用户选择器

管理端新增 `GET /api/v1/nl2sql-admin/auth-users?keyword=&limit=`(admin 专属),从 `da_agent_topic` 聚合出现过的 `auth_user_id`/`auth_username`(仿既有 `widget-users` 接口),供前端远程搜索;同时支持手输未出现过的稳定 ID(不校验存在性,typo 表现为匹配不中)。

### 前端

- `AgentDetailView` 新增「可见范围」标签页(仅管理员渲染):三档单选 + `selected` 模式下的用户多选(远程搜索 + 允许手输)。
- `AgentStudio` 卡片按 `visibility_mode` 显示徽标。
- 聊天页 `loadAgents` 区分"成功但空"与"请求失败":过滤后为空展示「暂无可用助手」空态,不再回退合成 `agent_default`(该合成条目发消息必失败);请求失败仍保留原兜底。

## 权衡

- 不可见与不存在统一 404/400 文案:牺牲错误可辨性,换取不泄露助手存在性。
- 已有会话不追溯拦截:与快照架构一致,变更影响面可预期;若后续需要严格模式,可在任务提交路径补充重查。
- `mode=selected` 时匿名与未列入用户一律不可见,管理员始终可见,无单独"仅管理员"档(等价于 `selected` + 空名单)。

## 兼容与边界

- 迁移回填 + 读侧默认 all,存量部署升级后行为不变;`AgentCatalogProfile` key 集不变,既有契约测试守护。
- 主门户以匿名 Widget 嵌入硬编码的 `agent_opendataworks`:若管理员将其改为受限,门户嵌入将拿不到该助手,属知情选择。
- auth 未启用的部署强制校验整体关闭,与仓库统一的 fail-open 回滚语义一致。
- 可见性变更即时生效(存储层无缓存,每次列表/详情/建话题都实时读取)。

## 风险与回退

- 回退代码即恢复全量可见;`visibility_json` 列与数据可保留,旧代码不读取该列,无需回滚迁移。
- 需要彻底回滚 schema 时执行 `alembic downgrade` 一步即可,列删除不影响其他数据。
- 误配 `selected` + 空名单会使助手仅管理员可见,管理端徽标与编辑页可直接发现并修正。
