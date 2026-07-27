# 脚本调用契约

先结论：只有三个脚本，统一通过
`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/<name>.py" ...` 调用。

不要自己拼脚本路径或脚本名，不要用 primary `DATAAGENT_SKILL_ROOT`、部署绝对路径、
裸相对路径，也不要猜测其他脚本名。三个脚本之外没有别的入口。

标准链路：`lookup_methodology.py` → 命中就 `run_methodology.py`，未命中就回落平台工具链路。

## lookup_methodology.py

- 用途：检索注册表，返回语义与参数槽位。**不执行任何查询。**
- 适用场景：拿到用户问题后的第一步。
- 命令模板：

  ```bash
  "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/lookup_methodology.py" --query "<用户问题>"
  "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/lookup_methodology.py" --id <id>
  "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/lookup_methodology.py" --list
  ```

- 输出 `kind=methodology_lookup`，含 `matched`、`results[]`。每个候选带
  `intent`、`caliber`、`params[]`、`output_fields`、`score`。
- `matched=0` 时按 `stop_reason` 回落常规问数链路，**不要换关键词反复检索**。

## run_methodology.py

- 用途：执行一个已注册方法论。
- 必须满足：`id` 来自 `lookup_methodology.py` 的返回，必填参数已齐。
- 命令模板：

  ```bash
  "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/run_methodology.py" --id <id> --params '<JSON 对象>'
  ```

- 参数：

  | 参数 | 说明 |
  |---|---|
  | `--id` | 方法论 id，必填 |
  | `--params` | JSON 对象；缺省为 `{}` |
  | `--mock` | mock 模式的节点结果 JSON 文件，见下 |
  | `--total-timeout` | 单次运行总预算秒数，默认 240，或取 `DATAAGENT_METHODOLOGY_TOTAL_TIMEOUT_SECONDS` |
  | `--node-timeout` | 单节点超时秒数，默认取 `DATAAGENT_SQL_READ_TIMEOUT_SECONDS` |
  | `--limit` | 每个查询节点的行数上限，默认取 `DATAAGENT_QUERY_LIMIT` |

- 输出 `kind=sql_execution`，与 `run_sql.py` 同构，可直接收口回答或喂给
  `build_chart_spec.py`。详见 [`40-output-contract.md`](40-output-contract.md)。
- 结果归因：
  - `result_state=success`：已拿到真实结果，直接收口，并在回答里带上 `methodology.caliber`。
  - `result_state=empty_result`：口径下确实无数据，说明口径与空结果，不换方法论试探。
  - `result_state=failed`：按 `error_code`、`failure_attribution`、`stop_reason` 说明，不等价重试。

## validate_methodology.py

- 用途：静态校验方法论工件。作者写完必跑；运行前 `run_methodology.py` 也会自动跑一遍。
- 命令模板：

  ```bash
  "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/validate_methodology.py" --all
  "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/validate_methodology.py" --path <file.json>
  "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/validate_methodology.py" --id <id>
  ```

- 加 `--check-sql` 会用桩参数绑定每个 `sql` 节点，再交给平台工具的 `validate_sql.py`。
  平台工具不可达时该项被跳过并记为 warning，不会误报为失败。
- 输出 `kind=methodology_validation`，退出码 0 表示全部通过。

## mock 模式

用作者提供的节点结果替代真实执行，**完全不访问任何数据存储**。
一个方法论因此可以像普通单元测试一样被断言。

mock 文件形如：

```json
{
  "current":  {"rows": [{"layer": "ODS", "table_cnt": 12}]},
  "previous": {"rows": [{"layer": "ODS", "table_cnt": 6}]}
}
```

键是节点名，值是该节点的结果（`rows` 必填，`columns` 可省略，由 `rows` 推断）。
被 mock 的节点直接返回给定结果，其余节点照常执行——所以上面的例子仍然会真的跑一遍
`growth` 的内存 join。

```bash
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT}/scripts/run_methodology.py" \
  --id table_growth_ratio --params '{"days":30}' --mock <mock.json>
```

mock 模式主要给方法论作者和回归测试用；回答用户问题时不要用它，
mock 出来的不是真实结果。

## 环境依赖

| 变量 | 用途 | 缺失后果 |
|---|---|---|
| `DATAAGENT_PYTHON_BIN` | 解释器 | 回落到当前解释器 |
| `DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT` | 本技能根目录 | 无法定位脚本 |
| `DATAAGENT_PLATFORM_SKILL_ROOT` | 平台工具根目录，`sql` 节点靠它执行 | `error_code=platform_tools_unavailable` |
| `DATAAGENT_QUERY_LIMIT` | 查询节点行数上限 | 默认 1000 |
| `DATAAGENT_SQL_READ_TIMEOUT_SECONDS` | 单节点超时 | 默认 60 |

不要执行环境探测或依赖安装命令。脚本报错时优先收敛参数或向用户追问，
不要切换解释器反复试探。
