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
        -d '{"projectName": "opendataworks"}'
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
| `backend/scripts/prepare-test-env.sh` | 校验 DolphinScheduler 连接、创建默认项目/工作流 |
| `backend/scripts/run-integration-test.sh` | 针对 `DolphinSchedulerClientIntegrationTest` 的快捷入口 |
| `scripts/test/test-workflow-lifecycle.sh` (参考) | 旧版 Bash 测试，可作为 CLI 校验模板 |

运行示例：

```bash
# 运行集成测试
DB_ROOT_PASSWORD=root DB_PASSWORD=opendataworks123 \
scripts/dev/run-integration-test.sh
```

## DolphinScheduler 集成测试

### 覆盖范围
- `DolphinSchedulerClient` REST 调用链路（登录、拉取/发布/下线工作流、任务增删改）
- 与 `backend/src/test/resources/application-test.yml` 同步的项目/工作流配置
- 控制台及 DolphinScheduler UI 中的工作流拓扑/版本历史

### 测试前准备
1. 启动 DolphinScheduler，本地默认地址 `http://localhost:12345/dolphinscheduler`。
2. 运行 `backend/scripts/prepare-test-env.sh`，确认登录、创建项目 `opendataworks-test`、创建工作流 `opendataworks-test-workflow` 均成功。
3. 若脚本失败，可在 UI 手动创建，并记录 `project-code` 与 `workflow-code`；也可使用 README 中的 cURL 模板调用 REST API 创建。
4. 确认 MySQL 存在 `opendataworks` 数据库，字符集 `utf8mb4`。

### 配置参数
编辑 `backend/src/test/resources/application-test.yml`：

```yaml
dolphin:
  service-url: http://localhost:8081
  project-name: opendataworks-test
  workflow-code: <FROM_UI_OR_API>
  workflow-name: opendataworks-test-workflow
  tenant-code: default
  worker-group: default
  execution-type: PARALLEL
logging:
  level:
    com.onedata.portal.client.dolphin: TRACE   # 需要定位问题时打开
```

### 执行方式
- 推荐：`backend/scripts/run-integration-test.sh`
- Maven：`mvn test -Dtest=DolphinSchedulerClientIntegrationTest -Dspring.profiles.active=test`
- IDE：右键 `DolphinSchedulerClientIntegrationTest` 运行；排查时可开启 `-Dlogging.level.com.onedata.portal=DEBUG`

### 验证要点
- 控制台预期输出 6 个用例依次通过，形如 `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。
- DolphinScheduler UI 中，目标工作流的 DAG/版本历史与测试操作保持一致。
- 勾选清单：
  - [ ] 登录并获取工作流定义成功，返回的 code/name 与预期匹配。
  - [ ] 生成的任务编码可用于新增任务。
  - [ ] 工作流下线、添加任务、发布、删除任务均无错误日志。
  - [ ] 最终状态为 ONLINE，且 UI 中历史记录可见。

### 常见问题速查
| 症状 | 排查步骤 |
| --- | --- |
| `Connection refused` | 检查服务端口、`service-url` |
| 登录失败 | 确认账号 `admin/dolphinscheduler123`，必要时重置密码 |
| `工作流不存在` | 校验 `workflow-code`、`project-code`，确认已在 UI 创建 |
| `权限不足` | 确认使用 admin 或具备权限的租户 |
| `JsonMappingException` | 打开 TRACE 日志，核对 DTO 与 API 响应字段 |

### 调试技巧
- `logging.level.reactor.netty.http.client=DEBUG` 查看 HTTP 请求。
- 在测试中对关键响应设断点，确认 Dolphin API 返回值。
- 使用 Postman/Insomnia 重放脚本中的请求，快速定位接口问题。

### 测试报告模板
```
测试时间: ______
执行人: ______
DolphinScheduler 版本: ______
测试环境: ______

| 测试用例 | 状态(PASS/FAIL) | 备注 |
| 登录并获取工作流定义 | | |
| 生成任务编码 | | |
| 下线工作流 | | |
| 添加任务到工作流 | | |
| 发布工作流 | | |
| 从工作流删除任务 | | |

问题记录:
- 描述:
- 原因:
- 解决方案:
- 状态: (已解决/待解决)
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
