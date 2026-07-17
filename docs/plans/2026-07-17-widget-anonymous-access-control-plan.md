# Widget 匿名访问控制实施计划

## 任务

- [x] 扩展 `WidgetAllowedSite` 与设置归一化逻辑，新增默认关闭的 `allow_anonymous`。
- [x] 在 Widget 接入设置页增加站点级匿名访问开关，并更新前端设置测试。
- [x] 调整后端 Widget 请求上下文校验，拒绝未授权匿名请求并返回 `WIDGET_LOGIN_REQUIRED`。
- [x] 扩展 Widget 脚本和 JavaScript 配置，默认不生成访客 ID，支持 `allowAnonymous` 与 `loginUrl`。
- [x] 增加 Widget 未登录视图，隐藏会话操作和输入区，处理后端 401 登录错误。
- [x] 更新接入文档与演示页面，明确匿名访问需要前后端双重开启。
- [x] 运行 DataAgent 后端与 Widget 前端的针对性测试和构建。

## 涉及文件

- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/core/skill_admin_service.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/tests/test_skill_admin_service.py`
- `dataagent/dataagent-backend/tests/test_widget_runtime_routes.py`
- `dataagent/dataagent-frontend/src/views/settings/WidgetAccessConfig.vue`
- `dataagent/dataagent-frontend/src/widget/config.js`
- `dataagent/dataagent-frontend/src/widget/entry.js`
- `dataagent/dataagent-frontend/src/widget/OpenDataWorksWidget.vue`
- `dataagent/dataagent-frontend/src/widget/WidgetChat.vue`
- `dataagent/dataagent-frontend/src/widget/styles.js`
- 对应前端测试和 `examples/widget/` 接入文档

## 验证

- 后端：站点配置默认值、显式开启、匿名拒绝、匿名放行、已登录用户放行。
- 前端：配置解析不生成访客 ID、显式匿名生成访客 ID、未登录视图及登录链接、后端 401 切换登录态。
- 构建：按仓库 Node 基线运行 Widget 构建。

## 发布与回退

- 发布后检查现有 Widget 接入方是否传递用户 ID；公开演示站点需显式开启匿名。
- 单站点应急回退通过设置 `allow_anonymous=true` 并增加客户端匿名参数完成。
- 代码回退不涉及数据库结构变更；旧配置中的新增字段可被旧版本忽略。
