# Opendataagent 离线部署包实施计划

## Goal

为 `opendataagent` 增加一套独立的离线部署包流程，生成包含 Docker 镜像、共享技能快照和离线启动脚本的 `tar.gz` 交付物。

## Scope

本轮覆盖：

- 新增 `opendataagent` 离线打包脚本
- 新增 `opendataagent` 离线镜像加载与启动脚本
- 新增离线包目录结构与共享 skills 复制逻辑
- 更新 `opendataagent` 部署文档和 README
- 更新 GitHub release workflow，把 `opendataagent` 离线包纳入 tag/latest release 附件与正文链接
- 更新 GitHub release workflow，把 `opendataagent-server/web` 镜像纳入 release 正文链接；在线镜像构建由独立 `opendataagent-build.yml` workflow 负责
- 做脚本 help / compose config / 打包级别验证

本轮不覆盖：

- 镜像推送到远程仓库
- 把 `opendataagent` 并入根 `deploy/`
- 替换现有源码发布包

## Execution Steps

### Phase 1: 文档与产物结构

- 新增设计文档：
  - `docs/design/2026-04-15-opendataagent-offline-package-design.md`
- 新增实施计划：
  - `docs/plans/2026-04-15-opendataagent-offline-package-plan.md`
- 固定离线包根目录结构：
  - `deploy/`
  - `scripts/`
  - `shared-skills/`
  - `README.md`

### Phase 2: 脚本实现

- 新增：
  - `opendataagent/scripts/create-offline-package.sh`
  - `opendataagent/scripts/load-images.sh`
  - `opendataagent/scripts/load-package-and-start.sh`
  - `opendataagent/scripts/start.sh`
  - `opendataagent/scripts/stop.sh`
  - `opendataagent/scripts/restart.sh`
  - `opendataagent/scripts/lib/container-runtime.sh`
- 打包逻辑：
  - 同步根 `skills/`
  - 构建 `opendataagent-server`、`opendataagent-web`
  - 拉取 `mysql:8.0`
  - 只打包 `deploy/.env.example`，不把运行时 `deploy/.env` 放入制品
  - 导出镜像 tar、manifest、checksum
  - 打包成 `opendataagent-deployment-<tag>.tar.gz`

### Phase 3: 部署目录与文档

- 更新：
  - `opendataagent/README.md`
  - `opendataagent/deploy/.env.example`
- 新增：
  - `opendataagent/deploy/README.md`
  - `opendataagent/deploy/docker-images/.gitkeep`
- 文档明确：
  - 在线 compose 启动
  - 离线包生成
  - 离线包安装与启动

### Phase 4: GitHub Release 集成

- 更新 `.github/workflows/docker-build.yml`，让 tag/latest release 继续上传 `opendataagent` 离线包
- 新增或维护独立 `.github/workflows/opendataagent-build.yml`
- 在 `opendataagent-build.yml` 中构建并推送：
  - `opendataagent-server`
  - `opendataagent-web`
- `opendataagent-server` 在线镜像构建前执行 `opendataagent/scripts/sync-root-skills.sh`
- 在 tag release 中新增：
  - `opendataagent/scripts/create-offline-package.sh`
  - `opendataagent-deployment-<version>.tar.gz`
  - 对应 checksum
  - release body 下载链接
  - `opendataagent-server/web` 镜像链接与 `docker pull` 示例
- 在 latest release 中新增：
  - `opendataagent-deployment-latest.tar.gz`
  - 对应 checksum
  - latest release body 下载链接
  - `opendataagent-server/web` latest 镜像链接与 `docker pull` 示例

### Phase 5: 验证

- 脚本帮助输出：
  - `create-offline-package.sh --help`
  - `load-package-and-start.sh --help`
- compose 配置校验：
  - `docker compose config` 或 `podman compose config`
- workflow 结构校验：
  - YAML 可解析
  - release job 包含 `opendataagent` 资产变量和文件列表
- 打包级验证：
  - 在可用容器运行时下执行一次离线包生成
  - 检查 tar.gz 内是否包含 `deploy/docker-images`、`shared-skills`
- 若本机容器运行时不可用，明确报告缺失的验证层

## Touched Files

- `opendataagent/scripts/create-offline-package.sh`
- `opendataagent/scripts/load-images.sh`
- `opendataagent/scripts/load-package-and-start.sh`
- `opendataagent/scripts/start.sh`
- `opendataagent/scripts/stop.sh`
- `opendataagent/scripts/restart.sh`
- `opendataagent/scripts/lib/container-runtime.sh`
- `opendataagent/deploy/.env.example`
- `opendataagent/deploy/README.md`
- `opendataagent/deploy/docker-images/.gitkeep`
- `opendataagent/README.md`
- `docs/design/2026-04-15-opendataagent-offline-package-design.md`
- `docs/plans/2026-04-15-opendataagent-offline-package-plan.md`

## Verification

- `bash opendataagent/scripts/create-offline-package.sh --help`
- `bash opendataagent/scripts/load-package-and-start.sh --help`
- `podman compose -f opendataagent/deploy/docker-compose.yml config` 或 `docker compose -f ... config`
- 如容器运行时可用：
  - `bash opendataagent/scripts/create-offline-package.sh --output <temp>.tar.gz`
  - 校验压缩包结构

## Rollout

- 先补脚本与文档
- 再在有容器运行时的机器上生成标准离线包
- 后续如需要，可把该脚本纳入 release 流程，但本轮不强制合并

## Backout

- 如离线包脚本存在问题，可回退 `opendataagent/scripts/*offline*` 与相关文档
- 不影响 `opendataagent` 现有在线 compose 部署能力
