# 离线部署包环境配置保护设计

## Current State

OpenDataWorks 主离线包和 OpenDataAgent 独立离线包都会复制整个 `deploy/`
目录。打包机存在 `deploy/.env` 时，该运行时配置会进入交付物；部分逻辑还会在
`.env` 缺失时由 `.env.example` 生成一个 `.env`。

目标服务器若直接把新包解压到已有部署目录，包内 `.env` 会覆盖服务器已经调整过的
数据库、认证、网络和运行时配置。打包机上的敏感配置也存在进入制品的风险。

## Scope

- `scripts/create-offline-package.sh`
- `opendataagent/scripts/create-offline-package.sh`
- 离线部署说明和对应回归测试

加载与启动脚本现有的“仅在 `.env` 不存在时从 `.env.example` 初始化”行为保持不变。

## Solution

两种离线包统一采用以下契约：

1. 复制部署资产时显式排除 `deploy/.env`。
2. 包内只保留并重写 `deploy/.env.example`，让镜像标签和离线路径符合当前制品。
3. 不从仓库根目录、本机部署目录或模板生成包内 `.env`。
4. 创建最终归档前扫描包工作区；发现任意名为 `.env` 的文件时立即终止打包。
5. 首次安装仍可由加载或启动脚本在目标 `.env` 不存在时复制模板；升级解压不会修改
   已存在的目标 `.env`。

## Interfaces and Compatibility

- 离线包布局移除 `deploy/.env`，保留 `deploy/.env.example`。
- `load-package-and-start.sh`、`start.sh` 的命令行接口不变。
- 新安装用户仍会得到模板初始化能力；已有部署用户的运行时配置得到保留。
- 旧离线包仍可能携带 `.env`，因此只有使用修复后生成的新包才能获得此保护。

## Tradeoffs

- 离线包不再是解压后立即带配置的制品；首次手工部署需要复制模板，自动加载脚本则会
  在配置缺失时完成复制。
- 对包工作区做最终扫描会让包含意外 `.env` 的打包直接失败。这是有意的
  fail-closed 行为，用于避免配置或密钥泄漏。
