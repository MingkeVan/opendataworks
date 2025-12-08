# OpenDataWorks 离线部署指南

1. **解压部署包**
   ```bash
   tar -xzf opendataworks-deployment-*.tar.gz
   cd opendataworks-deployment
   ```

2. **加载镜像**
   ```bash
   deploy/load-images.sh
   ```

3. **配置环境变量**
   ```bash
   cp deploy/.env.example deploy/.env
   vim deploy/.env   # 修改 DolphinScheduler 等参数
   ```

4. **启动服务**
   ```bash
   deploy/start.sh
   ```

5. **常用操作**
   - 查看状态：`deploy/start.sh` 输出中的命令参考
   - 停止服务：`deploy/stop.sh`
   - 重启服务：`deploy/restart.sh`

更多部署细节可参考仓库 `docs/handbook/operations-guide.md` 与 `docs/handbook/testing-guide.md`。
