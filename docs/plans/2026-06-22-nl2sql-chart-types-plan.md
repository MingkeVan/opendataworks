# 智能问数图表类型扩展 计划

关联设计：`docs/design/2026-06-22-nl2sql-chart-types-design.md`

## 受影响栈

- DataAgent 技能脚本（Python）
- DataAgent 前端（Vue/ECharts）
- 技能契约文档与图表模板
- 系统提示词
- 单元/契约测试（pytest + vitest）

## 任务

1. 技能脚本 `scripts/build_chart_spec.py`
   - `choose_chart` 支持 `area/scatter/combo/radar/funnel/gauge` 偏好分支。
   - 新增 `--stack` 参数，写入 `stack`。
   - `combo` 设置 `series[0]` 为 `bar`/左轴，其余 `line`/右轴；`scatter` series type=`scatter`；`area` series type=`line` 且 `area=true`。
   - `gauge` 允许空 `x_field`。

2. 前端解析 `chartSpec.js`
   - 扩展类型白名单与 `SERIES_TYPES`；`normalizeSeries` 透传 `axis`。
   - 扩展 `validateChartSpec` 分组规则。
   - 新增专用 option builder，并在 `buildAxisOption` 兼容 area/combo。

3. 前端渲染 `ChartSpecView.vue`
   - 注册新增 ECharts 图表与 `RadarComponent`。

4. 契约与提示词
   - 更新 `reference/50-tool-output-contract.md` 图表类型枚举、规则、`--chart-type` 模板。
   - 更新 `prompts/data_agent_system_prompt.md` 调用模板与类型选用指引。
   - 新增图表模板 `assets/chart-template/{area,scatter,combo,radar,funnel,gauge}.json`。

5. 测试
   - `tests/test_build_chart_spec_script.py`：新增各类型用例与 `--stack`。
   - `__tests__/chartSpec.spec.js`：新增各类型解析/校验/option 用例。

## 验证

- `python -m pytest dataagent/dataagent-backend/tests/test_build_chart_spec_script.py`
- `dataagent/dataagent-frontend` 下 `nvm use` 后 `npm run test`（chartSpec 用例）。
- 不涉及后端 API/schema/部署变更，无需 alembic 或全链路 smoke；记录未跑 UI 全链路。

## 回滚

- 改动集中在脚本、前端解析/渲染、文档、模板、测试；如需回滚直接 revert 本分支提交，契约向后兼容（新增枚举，不改旧字段语义）。
