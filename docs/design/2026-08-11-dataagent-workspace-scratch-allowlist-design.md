# DataAgent 工作区可写目录白名单设计

## Current State

DataAgent 运行时用 `PreToolUse` 边界钩子约束 agent 的文件访问范围。
`core/agent_runtime.py` 的 `_build_workspace_allowed_roots` 组装白名单根目录，
当前只包含：

- 会话工作区 `project_cwd`
- 已启用 Skill 的 `enabled_roots` / `primary_root`
- `opendataworks-platform-tools` 的同级目录

`_validate_workspace_tool_boundary` 对 `Read`/`LS`/`Glob`/`Grep`/`Write`/`Edit`/
`MultiEdit`/`NotebookEdit` 的路径参数、以及 `Bash` 命令里的绝对路径逐个校验，
不在白名单内一律拒绝，唯一例外是 CLI 卸载的超大工具结果文件。

问题在于白名单和运行时真实可写面已经不一致：

- 沙箱子容器在 `dataagent_sandbox_read_only_rootfs=true` 时显式挂载
  `--tmpfs /tmp:rw,nosuid,nodev,size=<tmpfs_size>`，`/tmp` 本来就是给
  Bash/Python 准备的临时可写盘；未开启只读根文件系统时 `/tmp` 也是容器内可写层。
- 但边界钩子会把 `/tmp/...` 判成越界，返回
  `Bash command references absolute path outside workspace: /tmp/xxx`。

结果是 agent 想落一个中间文件（排序输出、脚本临时产物、导出中转文件）时被拒，
只能把临时文件写进工作区，污染 `output/` 所在的交付目录；模型还会反复重试、
猜路径，白白消耗轮次和单次运行时长。

## Scope

- `dataagent/dataagent-backend/config.py`：新增可写目录白名单配置与解析函数
- `dataagent/dataagent-backend/core/agent_runtime.py`：白名单根目录组装、系统提示词运行时上下文
- `dataagent/dataagent-backend/prompts/data_agent_system_prompt.md`：硬性约束措辞
- `dataagent/dataagent-backend/sandbox_runner_main.py`：把新配置转发进子容器
- `deploy/docker-compose.dev.yml`、`deploy/docker-compose.prod.yml`：env 透传

不在范围内：

- 不改 `permission_gate` 的写工具确认策略，本变更只影响文件路径边界，不影响
  portal MCP 写工具门控
- 不改沙箱挂载本身（`--tmpfs /tmp` 的行为保持现状）
- 不给边界钩子引入读/写分离的双层白名单

## Solution

引入一份显式的「工作区外仍允许读写的目录白名单」，默认 `/tmp`。

1. 新增配置项 `dataagent_workspace_scratch_dirs`（env
   `DATAAGENT_WORKSPACE_SCRATCH_DIRS`），逗号分隔的绝对路径，默认 `"/tmp"`。
2. 新增 `resolve_workspace_scratch_dirs(cfg)`，作为解析的唯一来源：
   - 只接受绝对路径
   - 拒绝根目录 `/`
   - 拒绝含 `..` 的路径
   - 去掉尾部 `/` 后按顺序去重
   - 非法项直接丢弃，配置写错只会收紧白名单，不会意外放开更多目录
3. `_build_workspace_allowed_roots` 增加显式的 `scratch_dirs` 参数，把解析后的
   目录 `expanduser().resolve()` 后并入白名单根目录列表。参数默认空元组，
   白名单来源仍然只有调用方传入的一处，不在函数内部隐式读全局配置。
4. `_build_workspace_boundary_hooks` 在装配钩子时从配置解析 scratch 目录，
   `task_executor` 调用点不变。
5. `_build_system_prompt` 在「运行时上下文」里声明当前可写临时目录，
   系统提示词模板的硬性约束同步改写：临时目录只放中间过程文件，
   最终交付文件仍必须写入工作区 `output/`（前端下载链接依赖工作区相对路径）。
6. `sandbox_runner_main` 把 `DATAAGENT_WORKSPACE_SCRATCH_DIRS` 加入
   `_FORWARDED_ENV_KEYS`。边界钩子实际是在子容器进程里执行的，不转发这个 env，
   沙箱模式下配置不会生效。

## Interfaces and Compatibility

- 新增配置项，默认 `/tmp`，不需要改任何部署即可生效。
- `_build_workspace_allowed_roots(project_cwd, skill_runtime)` 保持原有两参形态可用，
  新增第三个可选参数，现有调用与测试不受影响。
- `_build_workspace_boundary_hooks` / `_build_system_prompt` 签名不变。
- 置 `DATAAGENT_WORKSPACE_SCRATCH_DIRS=""` 即可完整回到「只有工作区和 Skill 根」
  的旧行为。

## Tradeoffs

- 白名单是读写合一的：`/tmp` 进白名单意味着既可写也可读，边界钩子不区分读写工具。
  拆成读白名单和写白名单会引入第二层策略，和当前单层边界模型不符，
  收益也有限——能写入的目录本来就能被同一个 agent 读回。
- 沙箱模式下 `/tmp` 是子容器独占的 tmpfs，随容器销毁，风险自然受限。
  非沙箱本地执行时 `/tmp` 是宿主共享临时目录，白名单确实放宽了访问面；
  这类部署应按需把该配置改成一个专用目录（例如 `/var/lib/dataagent/scratch`）或置空。
- 临时目录里的文件不会出现在会话文件列表，也无法通过前端下载。
  这是有意的：交付物路径契约仍然只有工作区 `output/`。
