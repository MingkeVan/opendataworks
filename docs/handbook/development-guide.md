# 开发指南

合并原 `START.md`、`QUICK_START_GUIDE.md` 以及 backend/README 中的关键步骤，覆盖本地开发的准备、启动、调试流程。

## 环境要求

| 组件 | 版本建议 |
| --- | --- |
| Java | 8 或 11 (Gradle/IntelliJ) |
| Node.js | 18+ (支持 Vite 5) |
| MySQL | 8.0+ |
| Docker (可选) | 24+ |

## 第一步：准备数据库

使用本地 MySQL 创建数据库和用户（Flyway 首次启动会自动建表/迁移）：

```bash
mysql -u root -p <<'SQL'
CREATE DATABASE opendataworks DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'opendataworks'@'%' IDENTIFIED BY 'opendataworks123';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'%';
FLUSH PRIVILEGES;
SQL
```

> 如果已经通过 `deploy/docker-compose.dev.yml` 启动了 MySQL，可复用该容器的账号/密码，或在 `deploy/.env` 中调整后重启。

## 第二步：启动后端 (Spring Boot)

```bash
cd backend
./mvnw spring-boot:run
# 或使用 IntelliJ 运行 DataPortalApplication
```

- 默认端口 `8080`，上下文路径 `/api`。
- 配置文件 `application.yml` 中的 `spring.datasource.url` 已指向 `opendataworks`。
- 开启调试日志：`./mvnw spring-boot:run -Dspring-boot.run.arguments="--logging.level.com.onedata.portal=DEBUG"`。

## 第三步：启动前端 (Vue3)

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
| “Access denied for user 'opendataworks'” | 确认数据库用户/密码已创建；`mysql -uopendataworks -popendataworks123 opendataworks -e "SHOW TABLES;"` |
| 调度接口 500 | 检查后端日志；确认 `dolphin.url`/`token`/Project 配置正确且 OpenAPI 可访问 |
| 前端跨域 | 运行前端时设置 `VITE_API_BASE=http://localhost:8080/api npm run dev`，或在 backend 开启 CORS |
| 示例数据缺失 | 确认 Flyway 迁移已完成；如需演示数据可根据业务手工插入 |
| Doris 集群不可用 | 在 `doris_cluster` 表中配置 FE 地址，并在 Portal 中标记 `is_default=1` |

## 推荐工作流

1. **代码变更**：使用 feature 分支；需要的情况下更新数据库脚本并编写对应变更说明。
2. **单元/集成测试**：`./mvnw test`（支持 `-Dtest=TaskExecutionWorkflowTest` 等定点执行）。
3. **前端校验**：`npm run test` (如有) + 手动验证关键流程。
4. **文档同步**：凡是增加接口、表字段、配置项，都在本手册中更新相应章节。
