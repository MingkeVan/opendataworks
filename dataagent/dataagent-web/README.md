# dataagent-web

DataAgent 前端组件包（NL2SQL 专用）。

## 导出组件

- `NL2SqlChat`
- `NL2SqlPage`

## 使用方式

```js
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import { NL2SqlPage } from 'dataagent-web'
import 'dataagent-web/style.css'

createApp({
  components: { NL2SqlPage },
  template: '<NL2SqlPage />'
}).use(ElementPlus).mount('#app')
```

## 本地开发

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use
npm install
npm run dev
```

## 构建

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use
npm run build
npm run build:lib
```
