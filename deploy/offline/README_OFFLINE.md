# OpenDataWorks 离线部署指南

1. **解压部署包**
   ```bash
   tar -xzf opendataworks-deployment-*.tar.gz
   cd opendataworks-deployment
   ```

2. **加载镜像**
   ```bash
   scripts/deploy/load-images.sh
   ```

3. **配置环境变量**
   ```bash
   cp deploy/.env.example deploy/.env
   vim deploy/.env   # 修改 DolphinScheduler 等参数
   ```

4. **启动服务**
   ```bash
   scripts/deploy/start.sh
   ```

5. **常用操作**
   - 查看状态：`scripts/deploy/start.sh` 输出中的命令参考
   - 停止服务：`scripts/deploy/stop.sh`
   - 重启服务：`scripts/deploy/restart.sh`

相关文档请参考仓库中的 `docs/deployment/`：
- `DOCKER_QUICK_START.md`
- `DOCKER_DEPLOYMENT.md`
- `DEPLOYMENT_GUIDE.md`
