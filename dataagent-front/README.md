# dataagent-front

独立的智能问数前端模块，封装为可嵌入 Vue 组件。

## 目标

1. 不依赖现有 `frontend` 内部别名与请求封装。
2. 作为组件库输出，后续可嵌入任意 Vue3 + ElementPlus 宿主。
3. 当前阶段不改动现有 `frontend`。

## 使用方式

```js
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import { DataAgentHost } from 'dataagent-front'
import 'dataagent-front/style.css'

createApp({
  components: { DataAgentHost },
  template: '<DataAgentHost :entry-visible="true" api-base="/api" stream-base="/api" />'
}).use(ElementPlus).mount('#app')
```

## 本地开发

```bash
nvm use
npm install
npm run dev
```

## 组件构建

```bash
nvm use
npm run build:lib
```

## 关键参数

1. `entryVisible`：是否显示悬浮入口按钮。
2. `apiBase`：HTTP API 基础路径，默认 `/api`。
3. `streamBase`：SSE 基础路径，默认 `/api`。
4. `requestTimeout`：HTTP 超时时间，默认 `60000` ms。
