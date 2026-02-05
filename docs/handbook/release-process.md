# OpenDataWorks 版本发布流程

本文档描述 OpenDataWorks 的版本发布流程，后续发版请按此流程执行。

## 发布前检查

- [ ] 确认所有计划功能已合并到 `main` 分支
- [ ] 本地 `main` 与远程同步
- [ ] 通过 CI 构建

---

## 一、明确版本号

按 [语义化版本](https://semver.org/lang/zh-CN/) 确定新版本号，例如 `0.2.0`：

- **主版本号（Major）**：不兼容的 API 变更
- **次版本号（Minor）**：向下兼容的功能新增
- **修订号（Patch）**：向下兼容的问题修正

---

## 二、修改版本号与配置

### 2.1 代码中的版本号

| 文件 | 修改内容 |
|------|----------|
| `backend/pom.xml` | `<version>0.2.0</version>` |
| `frontend/package.json` | `"version": "0.2.0"` |

### 2.2 Docker Compose 正式部署

| 文件 | 说明 |
|------|------|
| `deploy/docker-compose.prod.yml` | 使用 `${OPENDATAWORKS_BACKEND_IMAGE:-mikefan2019/opendataworks-backend:0.2.0}` 等形式，将 `latest` 替换为当前版本号 |
| `deploy/.env.example` | 注释中说明离线部署时的镜像变量示例 |

### 2.3 构建相关（如有）

- `scripts/build/docker-build.env.example` 中 `VERSION`
- 其他引用版本号的地方

---

## 三、构建与发布

### 3.1 本地验证

```bash
# 构建镜像
cd frontend && npm run build
cd ../backend && mvn clean package -DskipTests

# 可选：本地构建 Docker 镜像
scripts/build/build-images.sh
```

### 3.2 打 Tag 并推送

```bash
git add -A
git commit -m "chore: release v0.2.0"
git tag v0.2.0
git push origin main
git push origin v0.2.0
```

推送 `v*` 标签会触发 GitHub Actions 的 [Build and Release](../../.github/workflows/docker-build.yml) 工作流。

### 3.3 CI 自动执行

工作流将依次执行：

1. **构建并推送 Docker 镜像**：`mikefan2019/opendataworks-frontend`、`mikefan2019/opendataworks-backend`，打上版本 tag
2. **生成离线部署包**：调用 `scripts/create-offline-package.sh --tag 0.2.0`
3. **创建 GitHub Release**：上传离线包为 Release 附件，并自动生成 Release Notes（含提交记录）

---

## 四、离线部署包与 Release

### 4.1 离线包生成（CI 中执行）

```bash
scripts/create-offline-package.sh --tag 0.2.0 --output opendataworks-deployment-0.2.0.tar.gz
```

- 使用 `--tag` 指定版本，对应 Docker Hub 上的镜像 tag
- 输出文件名建议为 `opendataworks-deployment-{version}.tar.gz`

### 4.2 Release 内容

GitHub Release 中应包含：

- **Release Notes**：GitHub Actions 自动生成（包含提交记录）
- **下载区块**：由工作流写入 Release 正文，包含 Docker 镜像拉取命令与离线包链接
- **附件**：
  - `opendataworks-deployment-0.2.0.tar.gz`（离线部署包）
  - 可选：`opendataworks-deployment-0.2.0.tar.gz.sha256`（校验和）

### 4.3 用户下载方式

1. **Docker 镜像**：
   ```bash
   docker pull mikefan2019/opendataworks-frontend:0.2.0
   docker pull mikefan2019/opendataworks-backend:0.2.0
   ```

2. **离线部署包**：在 Release 页面下载 `opendataworks-deployment-0.2.0.tar.gz`，按 [deploy/README.md](../../deploy/README.md) 进行离线部署。

---

## 五、发布后

- [ ] 在 GitHub Releases 页面检查附件是否上传成功
- [ ] 验证 Release Notes 中的下载链接可用
- [ ] 如有文档站，更新「最新版本」等说明

---

## 流程概览

```
1. 明确版本号 (如 0.2.0)
       ↓
2. 修改 backend/pom.xml、frontend/package.json、deploy/docker-compose.prod.yml 等
       ↓
3. git commit、git tag v0.2.0、git push
       ↓
4. CI 构建镜像、生成离线包、创建 Release 并上传附件
       ↓
5. 检查 Release 页面，确认下载可用
```
