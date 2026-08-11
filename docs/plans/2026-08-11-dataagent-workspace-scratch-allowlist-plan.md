# DataAgent 工作区可写目录白名单实施计划

## Goal

让边界钩子的目录白名单可配置，默认放行 `/tmp`，使 agent 能在沙箱本就可写的临时盘
上落中间文件，同时保持「最终交付文件写入工作区 `output/`」的契约不变。

## Tasks

1. `config.py`：新增 `dataagent_workspace_scratch_dirs`（默认 `"/tmp"`）与
   `resolve_workspace_scratch_dirs(cfg)` 解析函数，拒绝相对路径、根目录和含 `..` 的项。
2. `core/agent_runtime.py`：
   - `_build_workspace_allowed_roots` 增加 `scratch_dirs` 参数并并入白名单根目录
   - `_build_workspace_boundary_hooks` 从配置解析后传入
   - `_build_system_prompt` 在运行时上下文声明可写临时目录
3. `prompts/data_agent_system_prompt.md`：硬性约束改写为「工作区 + 运行时声明的
   可写临时目录」，并说明临时目录不放交付文件。
4. `sandbox_runner_main.py`：`DATAAGENT_WORKSPACE_SCRATCH_DIRS` 加入
   `_FORWARDED_ENV_KEYS`，保证沙箱子容器拿到同一份配置。
5. `deploy/docker-compose.dev.yml`、`deploy/docker-compose.prod.yml`：
   给 `dataagent-backend` 和 `dataagent-sandbox-runner` 透传该 env。
6. 测试：
   - `tests/test_agent_runtime.py`：白名单放行 scratch 目录（Write / Bash 重定向）、
     非白名单同级目录仍拒绝、钩子端到端放行、系统提示词声明临时目录
   - `tests/test_agent_runtime.py`：`resolve_workspace_scratch_dirs` 的默认值与
     非法项过滤
   - `tests/test_sandbox_runner_main.py`：子容器 env 转发该配置

## Verification

- `pytest tests/test_agent_runtime.py tests/test_sandbox_runner_main.py`
- `pytest tests/test_task_executor.py`（边界钩子装配调用点未回归）
- `python -c "import config, core.agent_runtime, sandbox_runner_main"` 冒烟导入
- 文档：确认 design / plan 同 slug、目录与命名符合仓库规则

未执行本地完整 NL2SQL 端到端冒烟（需要本地 MySQL/Redis/provider 凭据），
验证范围限于上述定向测试，实际 agent 运行链路未在本次变更中实测。

## Rollout and Backout

- 上线：默认值 `/tmp` 随发布生效，无需改部署；沙箱模式需要新版 runner 镜像
  才能转发该 env（旧 runner 会退回工作区-only 行为，不会出错）。
- 回退：设 `DATAAGENT_WORKSPACE_SCRATCH_DIRS=""` 即恢复旧边界，无需回滚代码。
- 不涉及数据库迁移、容器卷或运行中会话状态。
