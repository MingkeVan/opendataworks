# DolphinScheduler Python 服务下线实施计划与关键设计（评审稿）

## 目标与范围
- 目标：用 Java 后端直接调用 DolphinScheduler OpenAPI，完全替代 Python dolphinscheduler-service（含 Java Gateway 依赖）。
- 范围：工作流创建/更新（含任务、依赖、坐标）、上线/下线、启动实例、实例查询/日志、数据源列表、删除流程。保持上层业务接口返回模型不变。
- 不做：Dinky 侧推送逻辑变更；DolphinScheduler 自身升级与 UI 定制。

## 里程碑与拆分
1) 功能对齐：实现 Java OpenAPI 客户端 + 最小端到端（create→release→start→query→delete）自测通过。  
2) 兼容层：替换现有 `DolphinSchedulerService` 的 HTTP 调用为新客户端，接口签名与返回保持兼容；日志接口落地真实实现。  
3) 可视化/布局：提交流程时落盘 `locations` (x/y)，如需宽高统一配置默认值（如 200x60）写入自定义扩展字段。  
4) 开关灰度：新增配置 `dolphin.use-python-service`（默认 false），双写或影子调试；验证后删除 Python 部署与相关配置。  
5) 回归与上线：自动化集成测试 + 核心业务回归，更新运维文档和部署脚本。

## 关键设计
- 模块：新增 `dolphin-openapi`（或在现有 service 包下增加 `DolphinOpenApiClient` + DTO）。  
- 认证：支持 session 登录（/login，用户名/密码）和 Token（安全中心 token；header `token` 或 cookie `sessionId`），统一封装。  
- 接口映射（核心路径按 DS 3.2+，需对照实际版本确认）：
  - 新建/更新流程：`POST/PUT /projects/{projectCode}/process-definition`，入参含 `taskDefinitionJson`、`taskRelationJson`、`locations`、`processDefinitionCode/name/tenantCode/executionType/releaseState`。  
  - 上下线：`POST /projects/{projectCode}/process-definition/{code}/release`。  
  - 运行：`POST /projects/{projectCode}/executors/start-process-instance`，传 `processDefinitionCode`、`workerGroup` 等，返回 `processInstanceId`。  
  - 实例列表/详情：`GET /projects/{projectCode}/process-instances`，`GET /projects/{projectCode}/process-instances/{instanceId}`。  
  - 日志：`GET /projects/{projectCode}/task-instances/{taskInstanceId}/log`（或 `/log/detail`，按版本适配）。  
  - 删除：`DELETE /projects/{projectCode}/process-definition/{code}`。  
  - 数据源：`GET /datasources?pageNo&pageSize&type&searchVal`。
- DTO 复用：沿用现有 Java 构造器（taskCode/version/name/taskParams/priority/workerGroup/timeout/flag/relations/locations），序列化为上述接口所需 JSON。  
- taskCode 策略：继续使用现有自增 long 序列；启动时扫描已存在的 code 初始化基线，避免冲突。如需官方生成可预留接口切换。  
- 布局/“长宽高”：落盘 `locations` 的 x/y；宽高可通过配置写入自定义扩展（若前端需要显示），默认 200x60。  
- 错误处理：统一解析 `success`/`code` 字段，非 0/false 抛业务异常；封装重试与超时（默认 5–10s）。  
- 日志处理：真实调取 DS 日志接口，支持 offset/page；防止大日志阻塞，必要时限制最大返回大小。  
- 配置项：`dolphin.use-python-service`（灰度开关）。

## 配置设计（对齐 Dinky，并补充可选扩展）
- 只使用三个配置项：
  - `dolphin.url`：DolphinScheduler 地址（含 /dolphinscheduler），用于 WebUI。
  - `dolphin.token`：安全中心 Token，放 header `token`；不考虑无 token 时可退回登录的情况。
  - `dolphin.project-name`：目标项目名，默认使用opendataworks。


### Dinky 现有真实配置（源码扫描）
- 定义位置：`dinky-common/src/main/java/org/dinky/data/model/SystemConfiguration.java`（key 前缀 `sys.dolphinscheduler.settings.*`）。
- 配置项：`enable`、`url`、`token`、`projectName`（默认值同源码）；目前未暴露 workerGroup/tenant/执行类型等。
- 兼容策略：Java 模块默认只依赖上述四项；扩展项仅在配置存在时启用，保持与现有 Dinky 行为兼容。

## 流程与验证
- 开发自测流程：  
  1) 手动配置token。  
  2) 创建流程（含 1 shell + 1 sql + 依赖 + locations）成功返回 code；确认 DS UI 可见、坐标生效。
  3) 上线→启动→拿到 instanceId；列表/详情状态正确；日志可读。  
  4) 下线→删除流程成功。  
- 自动化：新增集成测试（需 DS 可用环境），跑在 CI “可选”阶段；本地依赖 env var 控制。  
- 回归：现有业务流（发布、运行、监控、删除）走一遍，校验接口兼容性。

## 灰度与回滚
- 灰度：配置开关切换到新客户端；保留 Python 服务一段时间做对照；必要时双写（新客户端提交但不回流结果），观察错误率。  
- 回滚：开关切回 Python 服务；删除操作谨慎，确保不影响已创建的流程。  
- 清理：确认稳定后移除 Python 部署、镜像与配置项；删除 Java Gateway 相关 env。

## 风险与缓解
- DS 版本接口差异：在客户端按版本开关/路径兼容，先对照实际部署文档。  
- code 冲突：迁移前读取现有任务/流程 code，初始化序列；必要时使用官方生成接口。  
- 权限/CSRF：确认网关/代理对 token/header 的要求，若有 CSRF 需附带 header。  
- 大日志/超时：日志接口分页，HTTP 客户端设置超时与压缩。  
- 监控：对新客户端请求加入指标（成功率、P95、错误码分布）和日志，以便观察期追踪。

## 评审关注点
- 是否接受“完全不用 Py4J/SDK，纯 OpenAPI”方案。  
- 接口字段/路径版本是否与现网 DS 版本匹配。  
- taskCode/流程 code 生成与冲突策略是否可行。  
- 日志接口选型及返回体大小控制。  
- 灰度/回滚策略是否满足上线风险控制。
