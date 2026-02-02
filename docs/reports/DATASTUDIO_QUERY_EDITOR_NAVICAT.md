# DataStudio 查询编辑器（Navicat 风格）设计与实现说明

更新时间：2026-02-02

## 目标
- 将 DataStudio 中间区域“查询部分”调整为 Navicat 风格：
  - 外边框顶部：数据源、Schema(Database)、Limit、运行/停止等操作
  - 编辑器区域：SQL 语法高亮、左侧行号、关键字/表名补全、Tab 接受补全
  - 结果区：支持多语句执行与多结果集展示（Result 1/2/…），并在结果页内展示耗时/状态/错误信息（不单独 Message 页）

## 已确认交互（产品决策）
- Run 默认执行 **全部 SQL**；若存在选中内容，则执行 **选中 SQL**。
- 支持 **多语句**（以 `;` 分隔）并展示 **多结果集**。
- Stop 必须 **终止数据库端执行**（对齐 Navicat Stop 语义），而不是仅取消前端请求。

## 后端实现要点
- `/v1/data-query/execute`
  - 支持 SQL 拆分为多语句并逐条执行（只读白名单：SELECT/WITH/SHOW/DESCRIBE/EXPLAIN）
  - 返回 `resultSets`（index 从 1 开始，对齐 Navicat 的 Result 1/2/3…）
- `/v1/data-query/stop`
  - 以 `clientQueryId`（建议传 DataStudio 的 tabId）定位当前运行中的 JDBC Statement/Connection
  - 执行 `Statement.cancel()` + `Connection.close()` 终止数据库端执行

## 前端实现要点
- 查询编辑器替换为 CodeMirror 6：
  - 行号（gutter）、SQL 语法高亮、关键字+表名补全、Tab 接受补全
- 查询框顶部工具条：
  - 数据源/Schema 选择、Limit、运行（运行全部/运行已选择）、停止、重置、存为任务
- 结果区：
  - Result 1/2/…（多结果集）
  - 查询耗时、执行时间、Stop 状态、以及错误信息统一展示在 Result 页（对齐“不要弹窗提示查询报错”诉求）
