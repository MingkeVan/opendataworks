# 工具 Recipes

先结论：脚本调用必须按“先澄清、再定位、后执行”的顺序进行。`run_sql.py` 不是第一步。

## inspect_metadata.py

- 用途：定位数据库、表、字段、血缘
- 适用场景：
  - 用户没有给出明确表名
  - 需要确认指标字段和维度字段
  - 需要判断候选数据库
- 典型调用参数：
  - `--database`
  - `--table`
  - `--keyword`
- 典型顺序：
  - 统计 / 对比 / 趋势 / 占比 / 明细 / 诊断 的第一脚本

## resolve_datasource.py

- 用途：根据 database 判断引擎和数据源
- 适用场景：
  - metadata 已经确定 database
  - 还不清楚是 MySQL 还是 Doris
- 典型顺序：
  - `inspect_metadata.py` 之后
  - `run_sql.py` 之前

## run_sql.py

- 用途：执行只读 SQL
- 适用场景：
  - 数据库明确
  - 引擎明确
  - SQL 已形成
- 必须先满足：
  - 指标清楚
  - 时间范围清楚
  - 维度清楚
  - 数据库清楚
- 禁止：
  - 没定位到数据库就执行
  - 用来“试着猜一下”

## build_chart_spec.py

- 用途：把 SQL 结果转换成图表规范
- 典型决策：
  - 分类对比 -> `bar`
  - 时间趋势 -> `line`
  - 占比分析 -> `pie`
  - 其他 -> 只保留表格
- 默认保底：
  - 不适合图表时不输出图表，直接保留 `sql_execution`

## format_answer.py

- 用途：整理最终中文结论
- 使用时机：
  - 已经拿到 SQL 执行结果
  - 需要压缩成用户可直接消费的结论

## 推荐脚本序列

- 统计：`inspect_metadata.py` -> `run_sql.py`
- 对比：`inspect_metadata.py` -> `run_sql.py` -> `build_chart_spec.py`
- 趋势：`inspect_metadata.py` -> `run_sql.py` -> `build_chart_spec.py`
- 占比：`inspect_metadata.py` -> `run_sql.py` -> `build_chart_spec.py`
- 明细：`inspect_metadata.py` -> `run_sql.py`
- 诊断：`inspect_metadata.py` -> `resolve_datasource.py` -> `run_sql.py`

## 何时必须先追问

- 活跃用户定义不清
- 指标口径不清
- 用户说“对比”但没说维度
- 用户说“趋势”但没说指标
- 数据库或表名存在多个候选
