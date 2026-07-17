# Widget 匿名访问控制设计

> 本文记录内部设计决策。接入方配置与使用说明以 `dataagent/dataagent-frontend/examples/widget/README.md` 为准。

## 现状

智能问数 Widget 在接入方未提供 `userId` 时，会在浏览器生成并持久化 `visitor_` 标识，请求携带 `X-ODW-Visitor-Id`。后端只校验 `website_id` 与 `Origin`，因此白名单内站点默认允许匿名访客创建会话、提问和读取该访客的历史记录。

`visitor_` 只用于会话隔离，不是经过认证的用户身份。对于可访问业务数据的 Agent，默认匿名访问不符合最小授权原则。

## 目标

- Widget 匿名访问默认关闭，并可按 `website_id` 显式开启。
- 匿名访问必须由后端站点策略强制执行，不能通过伪造访客请求绕过。
- 未提供用户身份且未开启匿名时，不生成 `visitor_`，Widget 展示明确的登录状态，不加载会话或业务数据。
- 保留公开演示等场景的匿名访问能力。

## 方案

### 站点策略

在 `widget_allowed_sites` 的每个站点条目增加：

```json
{
  "website_id": "portal-prod",
  "allowed_origins": ["https://portal.example.com"],
  "allow_anonymous": false
}
```

缺失 `allow_anonymous` 时按 `false` 处理。现有站点配置升级后自动转为禁止匿名，不需要数据库迁移；字段继续存放在 `da_agent_settings.raw_json`。

后端先校验站点和 Origin，再校验身份：

- 有 `X-ODW-User-Id`：按现有外部用户上下文继续处理。
- 无用户 ID、有访客 ID且站点 `allow_anonymous=true`：按匿名访客上下文处理。
- 其他情况：返回 HTTP 401，`detail.code=WIDGET_LOGIN_REQUIRED`。

### Widget 接入配置

新增两个可选接入参数：

- `data-allow-anonymous="true"` / `allowAnonymous: true`：允许客户端生成访客 ID。默认 `false`。
- `data-login-url="..."` / `loginUrl: "..."`：未登录状态的跳转地址。

匿名访问需要客户端参数与后端站点策略同时开启。客户端未开启时不生成或发送访客 ID；后端未开启时即使收到访客 ID也拒绝请求。

### 未登录界面

当 Widget 有 `websiteId`、没有用户身份且客户端未开启匿名，或后端返回 `WIDGET_LOGIN_REQUIRED` 时：

- 悬浮入口和面板标题栏保留。
- 隐藏新建会话、历史会话、消息列表、推荐问题和输入区。
- 面板主体展示锁定图标、`请先登录` 和 `登录后即可使用智能问数`。
- 配置 `loginUrl` 时显示 `前往登录`；未配置时显示 `刷新页面`。
- 未登录期间不发送行为埋点或会话请求。

登录由宿主业务系统完成。登录后宿主页面重新加载 Widget，并通过现有 `userId` 接入契约提供身份。

## 兼容与边界

- 主门户以无 `websiteId` 的 portal 模式加载 Widget，不受本策略影响。
- 已有 `visitor_` 会话和管理端查询能力保留；关闭匿名后不再新增匿名会话。
- 公开演示站点需要同时在管理设置中开启匿名，并在嵌入脚本中设置 `data-allow-anonymous="true"`。
- 本次不改变 `X-ODW-User-Id` 的可信传递方式。外部身份签名或令牌校验需要与宿主认证系统单独设计。

## 风险与回退

- 现有未传用户 ID 的生产 Widget 会进入登录态，这是安全默认值带来的预期行为。
- 如某站点确需恢复旧行为，可在站点设置开启匿名，并同步更新嵌入参数，无需回滚代码。
