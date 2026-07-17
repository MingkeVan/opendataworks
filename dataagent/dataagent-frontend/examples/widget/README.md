# OpenDataWorks 智能问数 Widget

嵌入式智能问数组件，可通过单个 `<script>` 标签将 OpenDataWorks 的智能问数能力集成到任何网页中。

本文档是 Widget 接入参数、身份策略和运行方式的权威说明。`docs/design/` 与 `docs/plans/` 下的文件仅记录内部设计和实施过程。

## 接入前置配置

嵌入脚本生效前，管理员需要先在 DataAgent 的 `/widget-access` 页面新增接入站点：

1. `Website ID`：必须与嵌入脚本的 `data-website-id` 完全一致。
2. `允许来源（Origin）`：填写宿主页面来源，例如 `https://portal.example.com`；`*` 仅建议用于本地演示。
3. `匿名访问`：默认关闭。生产站点建议保持关闭，由宿主系统提供当前用户身份。

未加入站点白名单或 Origin 不匹配时，后端返回 `403`，Widget 不允许访问智能问数接口。

## 快速开始

在页面底部（`</body>` 前）添加以下脚本标签：

```html
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="your-website-id"
  data-agent-id="your-agent-id"
  data-api-base-url="http://localhost:8900"
  data-user-id="current-user-id"
  data-login-url="/login"
  defer
></script>
```

Widget 会自动初始化并在页面右下角显示一个悬浮按钮。

## 配置属性

通过 `<script>` 标签的 `data-*` 属性进行配置：

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `data-website-id` | 是 | — | 站点标识符，用于区分不同的接入站点 |
| `data-agent-id` | 是 | — | 使用的 Agent ID，决定后端使用哪个智能体处理请求 |
| `data-api-base-url` | 是 | — | 后端 API 基础地址，如 `http://localhost:8900` |
| `data-display-mode` | 否 | `'floating'` | 显示模式：`'floating'`（悬浮模式）或 `'inline'`（内嵌模式） |
| `data-position` | 否 | `'bottom-right'` | 悬浮按钮位置：`'bottom-right'` 或 `'bottom-left'`（仅悬浮模式） |
| `data-project-name` | 否 | `'智能问数'` | 显示名称，出现在面板标题栏 |
| `data-project-color` | 否 | `'#4A90A4'` | 主题色（十六进制），影响按钮、标题栏等元素的颜色 |
| `data-container-id` | 否 | — | 内嵌模式下的容器元素 ID（仅内嵌模式必填） |
| `data-user-id` | 否 | — | 当前用户标识；未开启匿名访问时必须由宿主系统提供 |
| `data-login-url` | 否 | — | 未提供用户身份时“前往登录”按钮的跳转地址 |
| `data-allow-anonymous` | 否 | `'false'` | 是否允许客户端生成访客 ID；还需在管理端为该站点开启匿名访问 |

## 身份与匿名访问

匿名访问采用前后端双重开关，任何一侧未开启都不会放行匿名用户：

| 用户 ID | 嵌入参数 `allowAnonymous` | 管理端允许匿名 | 结果 |
|---------|--------------------------|----------------|------|
| 已提供 | 任意 | 任意 | 按该外部用户加载会话和使用 Widget |
| 未提供 | `false` 或未配置 | 任意 | 展示“请先登录”，不生成访客 ID |
| 未提供 | `true` | 关闭 | 后端返回 `401/WIDGET_LOGIN_REQUIRED`，切换到登录状态 |
| 未提供 | `true` | 开启 | 生成 `visitor_` 标识，按匿名访客使用 Widget |

匿名访问默认关闭。未登录状态保留 Widget 入口、标题栏和关闭按钮，但隐藏历史会话、推荐问题和输入区，不加载会话或业务数据：

- 配置 `data-login-url`：显示“前往登录”。
- 未配置 `data-login-url`：显示“刷新页面”。
- `sendMessage()`、`ask()`、新建会话和历史会话等公开 API 同样会被拦截。
- 后端拒绝匿名身份时返回 HTTP `401`，错误码为 `WIDGET_LOGIN_REQUIRED`。

宿主系统应在自身登录校验完成后注入 `data-user-id`，不要直接采用用户可修改的 URL 参数或表单值。当前 `X-ODW-User-Id` 是外部身份传递契约，不等同于密码或签名令牌。

公开演示场景需要在管理端站点设置和嵌入脚本中同时开启匿名访问：

```html
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="public-demo"
  data-agent-id="demo"
  data-api-base-url="http://localhost:8900"
  data-allow-anonymous="true"
  defer
></script>
```

## 显示模式

### 悬浮模式（Floating）

默认模式。Widget 在页面右下角（或左下角）显示一个悬浮触发按钮，点击后弹出对话面板。

```html
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="http://localhost:8900"
  data-display-mode="floating"
  data-position="bottom-right"
  data-project-name="智能助手"
  data-project-color="#6366f1"
  data-user-id="current-user-id"
  data-login-url="/login"
  defer
></script>
```

适用场景：
- 第三方网站集成
- 不希望改变现有页面布局
- 需要全局可访问的对话入口

### 内嵌模式（Inline）

Widget 直接渲染到指定的 DOM 容器中，成为页面布局的一部分。

```html
<!-- 1. 准备容器 -->
<div id="widget-container" style="width: 400px; height: 600px;"></div>

<!-- 2. 加载 Widget -->
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="http://localhost:8900"
  data-display-mode="inline"
  data-container-id="widget-container"
  data-project-name="智能问数"
  data-project-color="#4A90A4"
  data-user-id="current-user-id"
  data-login-url="/login"
  defer
></script>
```

适用场景：
- 将智能问数作为页面的固定功能区域
- 需要与其他页面内容并排显示
- 构建专用的数据分析工作台

## JavaScript API

Widget 加载后会在 `window` 上暴露 `OpenDataWorksWidget` 控制器对象。

### 编程式安装

不使用脚本 `data-*` 属性时，可以通过配置对象创建实例。属性名采用 camelCase：

```javascript
const widget = window.OpenDataWorksWidget.installWidget({
  websiteId: 'my-site',
  agentId: 'agent-001',
  apiBaseUrl: 'http://localhost:8900',
  userId: currentUser.id,
  loginUrl: '/login',
  allowAnonymous: false,
  displayMode: 'floating',
  projectName: '智能问数',
  projectColor: '#4A90A4'
});
```

编程式安装和 `data-*` 安装遵循相同的身份与匿名访问策略。

### 面板控制

```javascript
const widget = window.OpenDataWorksWidget;

// 打开对话面板
widget.open();

// 关闭对话面板
widget.close();

// 切换面板开关状态
widget.toggle();

// 查询面板是否打开
const isOpen = widget.isOpen(); // → boolean
```

### 消息与会话

```javascript
// 发送一条消息（会自动打开面板）
widget.sendMessage('最近30天工作流执行趋势');

// 取消当前正在执行的任务
widget.cancel();

// 打开历史会话列表
widget.openHistory();

// 创建新会话
widget.newConversation();

// 切换到指定会话
widget.selectConversation('topic-id-xxx');

// 删除指定会话
widget.deleteConversation('topic-id-xxx');
```

### 事件监听

```javascript
// 监听事件
widget.on('open', () => {
  console.log('面板已打开');
});

widget.on('close', () => {
  console.log('面板已关闭');
});

widget.on('message', (data) => {
  console.log('收到消息:', data);
});

widget.on('error', (error) => {
  console.error('发生错误:', error);
});

widget.on('login:required', () => {
  console.log('Widget 等待宿主系统提供用户身份');
});

widget.on('ready', () => {
  console.log('Widget 已就绪');
});
```

### 销毁

```javascript
// 完全移除 Widget，清理所有 DOM 和事件监听
widget.destroy();
```

## 运行示例

### 前提条件

1. 启动 OpenDataWorks 后端服务
2. 确保已配置可用的 `agent-id`
3. 启动前端开发服务器（Widget bundle 由前端服务提供）

### 启动步骤

```bash
# 1. 进入 DataAgent 前端目录
cd dataagent/dataagent-frontend

# 2. 确保使用正确的 Node 版本
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use

# 3. 安装依赖（如果尚未安装）
npm install

# 4. 启动开发服务器
npm run dev
```

### 查看示例

开发服务器启动后，直接在浏览器中打开示例文件：

| 示例 | 文件 | 说明 |
|------|------|------|
| 悬浮模式 | [`floating.html`](./floating.html) | 在模拟的数据看板页面中嵌入悬浮 Widget |
| 内嵌模式 | [`inline.html`](./inline.html) | 将 Widget 作为页面布局的一部分 |
| JS API | [`api-demo.html`](./api-demo.html) | 交互式演示所有 API 方法和事件监听 |
| Ask AI 搜索模式 | [`docs-search.html`](./docs-search.html) | 模拟主流文档站的顶部搜索框/底部浮动输入框 Ask AI 唤起模式 |

> **注意**：示例页面中的 `data-agent-id` 设置为 `"demo"`，`data-api-base-url` 为空。
> 要实际发送消息和接收回复，需要：
> 1. 将 `data-api-base-url` 修改为实际的后端地址（如 `http://localhost:8900`）
> 2. 将 `data-agent-id` 修改为后端已配置的有效 Agent ID
> 3. 确保后端服务和相关数据库正常运行

## 浏览器兼容性

- Chrome 80+
- Firefox 78+
- Safari 14+
- Edge 80+

## 常见问题

**Q: Widget 加载后没有出现悬浮按钮？**
A: 检查浏览器控制台是否有加载错误，确认 script src 地址可访问。

**Q: 点击发送消息没有响应？**
A: 确认 `data-api-base-url` 和 `data-agent-id` 已正确配置，且后端服务正在运行。

**Q: Widget 只显示“请先登录”？**
A: 当前站点未提供 `data-user-id`。生产接入应在宿主登录完成后注入用户 ID；公开演示则需要同时开启管理端“匿名访问”和 `data-allow-anonymous="true"`。

**Q: 已配置 `data-allow-anonymous="true"`，仍然要求登录？**
A: 检查 `/widget-access` 中对应 `Website ID` 的匿名访问开关。客户端参数不能绕过后端站点策略。

**Q: 返回 `403 Widget site/origin is not allowed`？**
A: 检查管理端站点白名单中的 `Website ID` 和允许来源，确保与实际脚本参数及宿主页面 Origin 一致。

**Q: 内嵌模式下 Widget 没有渲染？**
A: 确保 `data-container-id` 指向的 DOM 元素已存在且具有明确的宽高。
