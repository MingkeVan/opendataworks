# 测试与质量

整合 `MANUAL_TEST_GUIDE.md`、`workflow-integration-test-guide.md`、`integration-test-summary.md`、`TASK_EXECUTION_WORKFLOW_LIFECYCLE.md`、`BROWSER_TEST_RESULTS.md` 中的信息，形成可执行的质量指引。

## 测试分层

| 层级 | 覆盖内容 | 入口 |
| --- | --- | --- |
| 单元/服务测试 | Mapper、Service、调度编排 | `./mvnw test`，可通过 `-Dtest=TaskExecutionWorkflowTest` 定点 |
| 集成测试 | 真正调用 DolphinService、MySQL | `scripts/run-workflow-test.sh` + `backend/src/test/java/...` |
| 手工回归 | 浏览器操作、工作流生命周期、异常场景 | 本指南 “手工测试剧本” |
| 前端冒烟 | 关键页面渲染、交互 | `docs/reports/BROWSER_TEST_RESULTS.md` 中列出的脚本 |

## 手工测试剧本

### 1. 工作流生命周期

1. 登录 DolphinScheduler (默认 `admin/dolphinscheduler123`)，统计当前工作流数量。
2. 在 Portal 前端执行 `sample_batch_user_daily` 任务；如前端未启动，可调用 API：
   ```bash
   curl -X POST http://localhost:8080/api/tasks/1/execute
   ```
3. 回到 DolphinScheduler → Workflow Definition 页面，确认多出 `test-task-<timestamp>`。
4. 手动删除：
   ```bash
   WORKFLOW_CODE=123456789
   curl -X POST "http://localhost:5001/workflows/$WORKFLOW_CODE/delete" \
        -H "Content-Type: application/json" \
        -d '{"projectName": "data-portal"}'
   ```
5. 刷新页面确认工作流数量恢复；Portal 侧 `task_execution_log` 存在执行记录，`data_lineage` 不残留孤儿记录。

### 2. SQL 任务端到端

1. 在 Portal 新建任务 (SQL/Shell) 并配置调度策略。
2. 检查 `table_task_relation` 自动写入的 read/write 关系。
3. 在 DolphinScheduler 启动工作流，确认日志回传 Portal；失败场景需写入 `error_message`。
4. 对于 Doris 表，执行 `刷新统计`，确认 `table_statistics_history` 新增记录。

### 3. 前端页面巡检

- 依据 `docs/reports/BROWSER_TEST_RESULTS.md`，在 Chrome/Edge 最新版上手动点击：表详情 → 统计图表 → 血缘 DAG → 任务执行记录 → 巡检中心。
- 若发现 UI 与后端能力不一致，记录在 `docs/reports/TEST_REPORT.md`。

## 自动化脚本

| 脚本 | 功能 |
| --- | --- |
| `scripts/run-workflow-test.sh` | 清理测试数据、执行 `WorkflowLifecycleFullTest` |
| `scripts/dev/run-integration-test.sh` | 启动临时 MySQL、执行后端/前端集成测试、校验 Dolphin API |
| `scripts/test/test-workflow-lifecycle.sh` (参考) | 旧版 Bash 测试，可作为 CLI 校验模板 |

运行示例：

```bash
# 运行集成测试
DB_ROOT_PASSWORD=root DB_PASSWORD=onedata123 \
scripts/dev/run-integration-test.sh
```

## 数据验证项

- `SELECT COUNT(*) FROM task_execution_log WHERE status='failed';` → 确认失败任务被巡检捕获。
- `SELECT * FROM inspection_issue WHERE status='open';` → 验证命名/Owner/注释规则是否生效。
- `SELECT COUNT(*) FROM data_lineage` → 执行任务前后是否匹配期望。
- `SELECT COUNT(*) FROM table_statistics_history WHERE table_name='dwd_order';` → 数据统计是否持续累积。

## 缺陷复盘

- **WORKFLOW_CODE_MISMATCH_FIX.md**：记录了由于 DolphinScheduler 代码不一致导致的任务删除失败，现已修复；遇到同类问题优先查看该文档。
- **FIX_SUMMARY.md / COMPLETION_REPORT.md**：保存大版本修复/交付记录。

所有遗留问题/新发现缺陷请在 `docs/reports/TEST_REPORT.md` 更新并同步到 issue tracker。
