# 开发指南

合并原 `START.md`、`QUICK_START_GUIDE.md` 以及 backend/README 中的关键步骤，覆盖本地开发的准备、启动、调试流程。

## 环境要求

| 组件 | 版本建议 |
| --- | --- |
| Java | 8 或 11 (Gradle/IntelliJ) |
| Node.js | 18+ (支持 Vite 5) |
| Python | 3.10+ (FastAPI 依赖) |
| MySQL | 8.0+ |
| Docker (可选) | 24+ |

## 第一步：初始化数据库

```bash
# 1. 进入脚本目录
cd scripts/dev

# 2. 填写 root/admin 密码并执行
DB_ROOT_PASSWORD=root DB_PASSWORD=opendataworks123 \
./init-database.sh -s
```

- 脚本会创建 `opendataworks` 数据库、`opendataworks` 用户、核心表以及示例数据。
- 需要生成大量演示数据时，执行 `mysql < database/mysql/addons/40-init-test-data.sql`。

## 第二步：启动后端 (Spring Boot)

```bash
cd backend
./mvnw spring-boot:run
# 或使用 IntelliJ 运行 DataPortalApplication
```

- 默认端口 `8080`，上下文路径 `/api`。
- 配置文件 `application.yml` 中的 `spring.datasource.url` 已指向 `opendataworks`。
- 开启调试日志：`./mvnw spring-boot:run -Dspring-boot.run.arguments="--logging.level.com.onedata.portal=DEBUG"`。

## 第三步：启动 DolphinScheduler 适配层 (Python)

```bash
cd dolphinscheduler-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn dolphinscheduler_service.main:app --host 0.0.0.0 --port 5001
```

- `.env` 文件存放 DolphinScheduler API 地址/凭证。
- 本地调试可将 `DOLPHIN_HOST=localhost`, `DOLPHIN_PORT=12345`。
- 若要使用 gunicorn：`./scripts/run-prod.sh`。

## 第四步：启动前端 (Vue3)

```bash
cd frontend
npm install
npm run dev -- --host 0.0.0.0 --port 5173
```

- 通过 `VITE_API_BASE=http://localhost:8080/api npm run dev` 可自定义后端地址。
- 生产构建：`npm run build` → 静态文件输出到 `frontend/dist`。

## 联调确认清单

1. 打开 `http://localhost:5173`，登录演示账号 (`admin/admin123`)。
2. 表管理 → 查看示例表 `ods_user`，确认字段 & Doris 配置渲染正常。
3. 任务管理 → 执行 `sample_batch_user_daily`，在 DolphinScheduler 中新增临时工作流。
4. 进入血缘视图，确认 DAG 渲染、节点点击信息正常。
5. 检查 `task_execution_log`、`inspection_issue` 是否写入记录。

## 常见问题

| 问题 | 排查方式 |
| --- | --- |
| “Access denied for user 'opendataworks'” | 确认 init 脚本执行成功；`mysql -uopendataworks -popendataworks123 opendataworks -e "SHOW TABLES;"` |
| 调度接口 500 | 检查 Python service 日志；确认 `.env` 的 Token/Project/Worker Group 正确 |
| 前端跨域 | 调整 `frontend/.env.development` 的 `VITE_API_BASE`，或在 backend 开启 CORS |
| 示例数据缺失 | 重新运行 init script 并确认 `LOAD_SAMPLE_DATA=true` |
| Doris 集群不可用 | 在 `doris_cluster` 表中配置 FE 地址，并在 Portal 中标记 `is_default=1` |

## 推荐工作流

1. **代码变更**：使用 feature 分支；需要的情况下更新数据库脚本并编写对应变更说明。
2. **单元/集成测试**：`./mvnw test`（支持 `-Dtest=TaskExecutionWorkflowTest` 等定点执行）。
3. **前端校验**：`npm run test` (如有) + 手动验证关键流程。
4. **文档同步**：凡是增加接口、表字段、配置项，都在本手册中更新相应章节。
