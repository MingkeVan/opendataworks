# OpenDataWorks 数据中台前端

基于 Vue 3 + Element Plus 的数据中台管理界面。

## 功能模块

### 1. 表管理（Tables）
- 数据表元数据管理
- 表血缘关系可视化
- 分层管理（ODS/DWD/DWS）

### 2. 任务管理（Tasks）
- 任务创建和编辑
- 支持多种节点类型：
  - **SQL** - SQL 查询/DML 任务
  - **SHELL** - Shell 脚本任务
  - **PYTHON** - Python 脚本任务
- 数据源配置（SQL 节点）
- 任务血缘关系
- 任务发布到 DolphinScheduler
- 手动触发执行

### 3. 血缘管理（Lineage）
- 表级血缘关系
- 任务依赖关系
- 血缘图可视化

### 4. 智能问数（Intelligent Query）
- 主前端内置 NL2SQL 智能问数工作台
- 会话管理、SSE 流式回复、SQL 回显与执行结果展示
- 通过 `/api/v1/nl2sql` 与 DataAgent 后端交互

## 快速开始

### 前置要求

- Node.js >= 18
- npm >= 8

### 安装依赖

```bash
npm install
```

### 开发模式

```bash
npm run dev
```

访问：http://localhost:3000

### 构建生产版本

```bash
npm run build
```

## 环境配置

### 代理配置（开发环境）

编辑 `vite.config.js`：

```javascript
export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  }
})
```

## 项目结构

```
frontend/
├── src/
│   ├── api/              # API 接口封装
│   │   ├── task.js       # 任务相关 API
│   │   ├── table.js      # 表相关 API
│   │   └── lineage.js    # 血缘相关 API
│   ├── views/            # 页面组件
│   │   ├── tasks/        # 任务管理
│   │   │   ├── TaskList.vue    # 任务列表
│   │   │   └── TaskForm.vue    # 任务表单
│   │   ├── tables/       # 表管理
│   │   └── lineage/      # 血缘管理
│   ├── router/           # 路由配置
│   ├── stores/           # 状态管理
│   └── App.vue           # 根组件
├── public/               # 静态资源
└── package.json
```

## 核心功能使用

### 创建 SQL 任务

1. 进入"任务管理"页面
2. 点击"新建任务"按钮
3. 填写表单：
   - **任务名称**：例如 "用户画像计算"
   - **任务编码**：例如 "user_profile_calc"
   - **任务类型**：批任务
   - **执行引擎**：DolphinScheduler
   - **节点类型**：SQL ⭐
   - **数据源名称**：doris_test ⭐
   - **数据源类型**：DORIS ⭐
   - **任务 SQL**：
     ```sql
     INSERT INTO dws.user_profile
     SELECT user_id, COUNT(*) as visit_count
     FROM ods.user_events
     GROUP BY user_id
     ```
   - **输入表**：选择 ods.user_events
   - **输出表**：选择 dws.user_profile
4. 点击"提交"保存任务
5. 在列表中找到任务，点击"发布"
6. 任务将同步到 DolphinScheduler 并自动上线

### 查看任务列表

任务列表显示：
- 任务名称和编码
- 任务类型（批任务/流任务）
- 执行引擎（DolphinScheduler/Dinky）
- **节点类型**（SQL/SHELL/PYTHON）⭐ 新增
- **数据源信息**（名称和类型）⭐ 新增
- 调度配置
- 状态（草稿/已发布/运行中）
- 负责人

### 操作按钮

- **编辑** - 修改任务配置
- **发布** - 同步到 DolphinScheduler（仅草稿状态）
- **执行** - 手动触发执行（仅已发布状态）
- **删除** - 删除任务

## API 接口

### 任务 API (`/v1/tasks`)

```javascript
import { taskApi } from '@/api/task'

// 查询任务列表
const { records, total } = await taskApi.list({
  pageNum: 1,
  pageSize: 20,
  taskType: 'batch',  // 可选：batch/stream
  status: 'draft'     // 可选：draft/published/running
})

// 获取任务详情
const task = await taskApi.getById(taskId)

// 创建任务
const newTask = await taskApi.create({
  task: {
    taskName: '任务名称',
    taskCode: 'task_code',
    taskType: 'batch',
    engine: 'dolphin',
    dolphinNodeType: 'SQL',      // ⭐ 节点类型
    datasourceName: 'doris_test', // ⭐ 数据源名称
    datasourceType: 'DORIS',      // ⭐ 数据源类型
    taskSql: 'SELECT * FROM table',
    priority: 5,
    timeoutSeconds: 60
  },
  inputTableIds: [1, 2],
  outputTableIds: [3]
})

// 更新任务
await taskApi.update(taskId, updatedTask)

// 发布任务
await taskApi.publish(taskId)

// 执行任务
await taskApi.execute(taskId)

// 删除任务
await taskApi.delete(taskId)
```

## 注意事项

### 数据源配置

⚠️ **SQL 任务的数据源必须在 DolphinScheduler 中预先创建！**

1. 访问 DolphinScheduler UI：http://localhost:12345/dolphinscheduler
2. 登录：admin / dolphinscheduler123
3. 数据源中心 → 创建数据源
4. 配置信息必须与前端表单一致：
   - 数据源名称：doris_test
   - 数据源类型：DORIS

否则发布时会报错：
```
Can not find any datasource by name doris_test and type DORIS
```

### 节点类型说明

- **SQL**：执行 SQL 查询或 DML 语句，需要配置数据源
- **SHELL**：执行 Shell 脚本，不需要数据源
- **PYTHON**：执行 Python 脚本，不需要数据源

### 任务编码规范

- 必须唯一
- 建议使用下划线命名：`table_name_operation`
- 例如：`user_profile_daily_calc`

## 常见问题

### Q: 任务提交后在 DolphinScheduler 中显示为 SHELL 而不是 SQL？

A: 确保在表单中正确选择了**节点类型为 SQL**，并填写了数据源配置。

### Q: 发布任务时报错"Can not find any datasource"？

A: 需要在 DolphinScheduler UI 中创建对应的数据源，确保名称和类型完全一致。

### Q: 如何查看任务执行日志？

A: 目前需要在 DolphinScheduler UI 中查看，后续版本会在数据中台集成日志展示。

## 技术栈

- **Vue 3** - 渐进式 JavaScript 框架
- **Element Plus** - Vue 3 UI 组件库
- **Vue Router** - 官方路由管理器
- **Pinia** - Vue 3 状态管理
- **Axios** - HTTP 客户端
- **Vite** - 下一代前端构建工具

## 开发规范

### 代码风格

- 使用 Composition API
- 使用 `<script setup>` 语法
- 组件名使用 PascalCase
- 文件名使用 PascalCase

### Git Commit 规范

```
feat: 新增功能
fix: 修复 bug
docs: 文档更新
style: 代码格式调整
refactor: 重构
test: 测试相关
chore: 构建/工具链相关
```

## 更新日志

### v1.1.0 (2025-10-18)

**新增**
- ✨ 任务表单支持选择 DolphinScheduler 节点类型（SQL/SHELL/PYTHON）
- ✨ SQL 任务支持配置数据源（名称和类型）
- ✨ 任务列表显示节点类型和数据源信息
- 📝 新增任务表单增强文档

**修复**
- 🐛 修复创建任务默认为 SHELL 节点的问题

### v1.0.0

**初始版本**
- ✨ 任务管理基础功能
- ✨ 表管理基础功能
- ✨ 血缘关系管理

## 许可证

MIT
