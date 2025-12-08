# 常见问题

## 1. 无法连接到 DolphinScheduler

**问题**: 发布任务时提示连接失败

**解决方案**:
- 检查 DolphinScheduler 是否运行，`DOLPHIN_URL` 是否可访问
- 确认配置文件中的 `dolphin.url`/`dolphin.token` 正确
- 查看后端日志是否存在 401/404 等 OpenAPI 错误码

## 2. 前端调用后端接口 CORS 错误

**问题**: 浏览器控制台显示 CORS 错误

**解决方案**:
- 确保后端 `WebConfig` 中配置了正确的前端地址
- 检查 Vite 配置中的 proxy 设置

## 3. 血缘图不显示

**问题**: 血缘关系页面显示空白

**解决方案**:
- 确保已创建任务并配置了输入输出表
- 检查浏览器控制台是否有 JavaScript 错误
- 确认 ECharts 库已正确加载

## 4. 任务执行状态无法同步

**问题**: 任务已执行但状态仍显示 pending

**解决方案**:
- 检查 DolphinScheduler 工作流是否实际执行
- 查看后端日志是否有错误信息
- 确认数据库连接正常

## 5. 项目编码查询失败

**问题**: 启动时提示无法查询 project-code

**解决方案**:
- 确保 DolphinScheduler 中已创建对应项目
- 检查 `dolphin.project-name` 配置是否正确
- 查看后端日志：`backend/logs/*.log` 或容器日志，确认 OpenAPI 是否返回错误码/鉴权失败
