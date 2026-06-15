# NL2SQL 输入区斜杠命令 执行计划

- 配套设计：`docs/design/2026-06-15-nl2sql-input-slash-commands-design.md`
- 范围：仅前端 `dataagent/dataagent-frontend`

## 任务

1. 新增 `src/views/intelligence/useSlashCommands.js`
   - 纯函数：`parseSlashQuery`、`filterCommands`、`buildSkillCommand`、`buildSkillCommands`。
   - 组合式：`useSlashCommands({ getCommands, inputText, focusInput })`，含 `visible/query/activeIndex/filtered` 与 `syncFromInput/handleKeydown/select/close/setActive`。
2. 新增 `src/views/intelligence/SlashCommandMenu.vue`
   - Props `visible/commands/activeIndex`，事件 `select/hover`；`@mousedown.prevent` 选中。
3. 接入主聊天 `src/views/intelligence/NL2SqlChatV2.vue`
   - `listAgents()` 归一化保留 `skill_folders`。
   - 命令 = 当前 agent 技能 + `/clear`、`/new`。
   - textarea 改 `@keydown="onComposerKeydown"`、`@input` 加 `syncFromInput()`；渲染菜单；`handleSend` 调 `close()`。
4. 接入嵌入式 `src/widget/WidgetChat.vue`
   - textarea 加 ref；命令 = 当前 agent 技能 + `/clear`。
   - `@keydown` 统一处理；渲染菜单；`send`/`handleSuggestion` 调 `close()`。
5. 新增 `src/views/intelligence/__tests__/useSlashCommands.spec.js`，覆盖解析、过滤、技能命令、键盘选中。

## 验证

- `export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use`
- 运行 `useSlashCommands` 单测；执行最小相关构建/lint。
- 未跑端到端 UI 时，在报告中显式说明仅做了组件级与单测验证。

## 回滚

- 改动均为新增文件 + 两个组件局部接入；回滚即删除新增文件并还原两个组件的 textarea/命令接入片段，无数据或接口迁移。
