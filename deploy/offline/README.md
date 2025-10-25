# OpenDataWorks 离线部署包模板

使用 `scripts/offline/create-offline-package-from-dockerhub.sh` 脚本时，会基于当前仓库生成一个可在无外网环境导入的压缩包。最终离线包包含：

- `deploy/`：Docker Compose 与环境变量模板
- `deploy/docker-images/`：脚本拉取并保存的镜像 tar 包
- `database/mysql/`：数据库初始化脚本 (bootstrap/schema/sample/addons)
- `scripts/deploy/`：启动、停止、加载镜像等运维脚本
- `README_OFFLINE.md`：离线环境操作指南

生成离线包示例：

```bash
# 在有外网的机器上执行
scripts/offline/create-offline-package-from-dockerhub.sh \
  --namespace <dockerhub-namespace> \
  --tag <image-tag> \
  --platform linux/amd64

# 输出: opendataworks-deployment-YYYYMMDD-HHMMSS.tar.gz
```

将压缩包传入内网后，解压并按 `README_OFFLINE.md` 中的说明执行即可。
