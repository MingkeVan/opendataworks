# Opendataagent 离线部署包设计

## Summary

`opendataagent` 已经具备独立部署目录和独立镜像构建能力，但还缺少一套可直接交付给内网环境的离线部署包。
本轮新增一条独立于主仓库 `opendataworks-deployment` 的离线打包链路，为 `opendataagent` 生成：

- 独立 tar.gz 部署包
- 已导出的 Docker/Podman 镜像
- 离线加载镜像脚本
- 离线启动脚本
- 共享 `skills/` 源码快照

目标是让 `opendataagent` 可以在不依赖主仓库在线镜像仓库、不依赖外部源码目录的情况下完成独立部署。

## Current State

- `opendataagent/deploy/docker-compose.yml` 已可独立启动 `opendataagent-server`、`opendataagent-web` 和 MySQL
- `opendataagent/scripts/build-release.sh` 已可构建源码发布包，但不包含容器镜像
- `opendataagent` 运行时依赖仓库根 `skills/` 作为共享技能源码
- 主仓库已有一套 `scripts/create-offline-package.sh` 用于 `opendataworks-deployment`，但它服务的是主门户 + DataAgent + Portal MCP，不适合直接复用给 `opendataagent`

## Problem

当前缺失的是一套独立的离线交付物：

1. `opendataagent` 无法像主仓库那样直接交付一个包含镜像的离线包
2. 现有部署 compose 默认依赖宿主机仓库里的 `../../skills`
3. 现有部署目录缺少离线加载镜像与启动脚本
4. 发布文档没有说明 `opendataagent` 的离线部署方式

## Goals

- 新增 `opendataagent` 独立离线包脚本
- 离线包包含 `opendataagent-server`、`opendataagent-web` 和 `mysql:8.0` 镜像
- 离线包包含根 `skills/` 的只读快照，供 `opendataagent` 使用
- 离线包提供 `load-images.sh`、`start.sh`、`stop.sh`、`restart.sh`、`load-package-and-start.sh`
- 文档明确离线包格式、生成命令、安装命令和路径约定
- GitHub Release 在 tag 发布与 latest 发布时都上传 `opendataagent` 离线包，并在 release body 中提供下载链接
- GitHub Actions 同时构建并推送 `mikefan2019/opendataagent-server` 与 `mikefan2019/opendataagent-web`，并在 release body 中提供镜像链接与 `docker pull` 命令

## Non-Goals

- 不把 `opendataagent` 并入根 `deploy/`
- 不修改主仓库现有 `opendataworks-deployment` 离线包格式
- 不在本轮引入镜像仓库发布流程
- 不要求离线包替代源码发布包

## Package Layout

离线包根目录固定为：

```text
opendataagent-deployment/
├── README.md
├── deploy/
│   ├── .env
│   ├── .env.example
│   ├── docker-compose.yml
│   └── docker-images/
│       ├── opendataagent-server.tar
│       ├── opendataagent-web.tar
│       ├── mysql-8.0.tar
│       ├── manifest.json
│       └── checksums.sha256
├── scripts/
│   ├── lib/container-runtime.sh
│   ├── load-images.sh
│   ├── start.sh
│   ├── stop.sh
│   ├── restart.sh
│   └── load-package-and-start.sh
└── shared-skills/
    ├── bin/
    ├── lib/
    ├── platform/
    └── generic/
```

## Image Strategy

离线包包含三类镜像：

- `opendataagent-server:<tag>`
- `opendataagent-web:<tag>`
- `mysql:8.0`

其中：

- 应用镜像默认由本地源码构建
- MySQL 镜像由容器运行时拉取并导出
- 镜像 tar 文件统一放在 `deploy/docker-images/`
- 额外输出 `manifest.json` 与 `checksums.sha256`

## Shared Skills Strategy

- 打包脚本从仓库根 `skills/` 复制共享技能源码到离线包 `shared-skills/`
- 离线包内 `deploy/.env` 和 `deploy/.env.example` 默认把 `OPENDATAAGENT_SHARED_SKILLS_PATH` 改写为 `../shared-skills`
- 这样离线部署无需依赖原仓库路径，也不要求宿主机单独挂载 `skills/`

## Script Boundary

新增脚本职责如下：

- `create-offline-package.sh`
  - 构建应用镜像
  - 拉取基础镜像
  - 复制 deploy、scripts、skills
  - 导出镜像 tar
  - 生成离线包 tar.gz
- `load-images.sh`
  - 校验并加载 `deploy/docker-images/*.tar`
  - 修复 Podman 可能引入的 `localhost/` 前缀
- `start.sh`
  - 读取 `deploy/.env`
  - 执行 `docker compose`/`podman compose up -d`
- `stop.sh`
  - 执行 `compose down`
- `restart.sh`
  - 先停再起
- `load-package-and-start.sh`
  - 解压 tar.gz
  - 加载镜像
  - 自动补 `.env`
  - 启动服务

## Release Relationship

- 现有 `build-release.sh` 继续负责源码发布包
- 新增离线包脚本单独负责镜像型交付物
- 两者互补，不强行合并成单一脚本，避免把“无容器环境的源码构建”和“需要容器运行时的镜像导出”绑死在一起
- GitHub Actions `docker-build.yml` 继续负责主仓库的镜像、主离线包和 `opendataagent` 离线包附件
- `opendataagent-server` 与 `opendataagent-web` 在线镜像由独立的 `opendataagent-build.yml` workflow 构建和推送
- Release 附件中同时保留：
  - `opendataworks-deployment-<tag>.tar.xz`
  - `opendataagent-deployment-<tag>.tar.gz`
  - 对应 `.sha256` 文件
- Release 正文中的 Docker 镜像区同时保留：
  - `opendataworks-*`
  - `opendataagent-server`
  - `opendataagent-web`

## Tradeoffs

- 离线包会复制一份 `skills/`，体积会变大，但换来独立部署能力
- `mysql:8.0` 也一起导出，能减少内网环境临时缺镜像的问题
- 不把离线包逻辑塞回主仓库 `scripts/`，可以保持 `opendataagent` 交付链路边界清晰

## Affected Areas

- `opendataagent/scripts/`
- `opendataagent/deploy/`
- `opendataagent/README.md`
- `docs/design/`
- `docs/plans/`
