import {
  buildChartRenderModel,
  extractChartSpec,
  extractChartSpecsFromText,
  parseChartSpec,
  splitChartSpecText,
  stripChartSpecsFromText,
  validateChartSpec
} from '../chartSpec'

describe('chartSpec', () => {
  it('parses and validates line chart specs without reordering dataset', () => {
    const spec = {
      kind: 'chart_spec',
      version: 1,
      chart_type: 'line',
      title: '最近30天工作流发布趋势',
      description: '按天展示工作流发布次数',
      x_field: 'stat_day',
      series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
      dataset: [
        { stat_day: '2026-03-03', publish_cnt: 8 },
        { stat_day: '2026-03-01', publish_cnt: 3 },
        { stat_day: '2026-03-02', publish_cnt: 5 }
      ],
      error: null
    }

    const parsed = parseChartSpec(spec)
    const validation = validateChartSpec(parsed)
    const renderModel = buildChartRenderModel(parsed)

    expect(validation.valid).toBe(true)
    expect(renderModel.state).toBe('renderable')
    expect(renderModel.kind).toBe('echarts')
    expect(renderModel.option.xAxis.data).toEqual(['2026-03-03', '2026-03-01', '2026-03-02'])
    expect(renderModel.option.series[0].data).toEqual([8, 3, 5])
  })

  it('validates pie chart with a single series only', () => {
    const renderModel = buildChartRenderModel({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'pie',
      title: '各工作流发布操作类型占比',
      x_field: 'operation',
      series: [
        { name: '发布次数', field: 'publish_cnt', type: 'pie' },
        { name: '占比', field: 'ratio', type: 'pie' }
      ],
      dataset: [
        { operation: 'deploy', publish_cnt: 33, ratio: 0.68 },
        { operation: 'online', publish_cnt: 9, ratio: 0.18 }
      ],
      error: null
    })

    expect(renderModel.state).toBe('invalid')
    expect(renderModel.errorText).toContain('pie 类型必须且只能提供一个 series')
  })

  it('builds table render models only when columns are explicit', () => {
    const renderModel = buildChartRenderModel({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'table',
      title: '最近工作流发布记录',
      columns: ['workflow_id', 'status'],
      dataset: [{ workflow_id: 173, status: 'success' }],
      error: null
    })

    expect(renderModel.state).toBe('renderable')
    expect(renderModel.kind).toBe('table')
    expect(renderModel.columns).toEqual(['workflow_id', 'status'])
    expect(renderModel.rows).toEqual([{ workflow_id: 173, status: 'success' }])
  })

  it('fails invalid specs with explicit field errors', () => {
    const validation = validateChartSpec({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'bar',
      title: '各数据层表数量对比',
      dataset: [{ layer: 'DWD', table_cnt: 18 }],
      error: null
    })

    expect(validation.valid).toBe(false)
    expect(validation.errors).toContain('bar 类型必须提供 x_field')
    expect(validation.errors).toContain('bar 类型必须提供 series')
  })

  it('extracts fenced chart specs using the same parser', () => {
    const message = `
结论如下：

\`\`\`chart
{"kind":"chart_spec","version":1,"chart_type":"pie","title":"各工作流发布操作类型占比","x_field":"operation","series":[{"name":"发布次数","field":"publish_cnt","type":"pie"}],"dataset":[{"operation":"deploy","publish_cnt":33},{"operation":"online","publish_cnt":9}],"error":null}
\`\`\`
`

    const specs = extractChartSpecsFromText(message)

    expect(specs).toHaveLength(1)
    expect(specs[0].chart_type).toBe('pie')
    expect(specs[0].series[0].field).toBe('publish_cnt')
  })

  it('extracts chart specs from tool-result content blocks for conclusion-area promotion', () => {
    const spec = {
      kind: 'chart_spec',
      version: 1,
      chart_type: 'line',
      title: '最近30天工作流发布趋势',
      x_field: 'stat_day',
      series: [{ name: '发布次数', field: 'publish_cnt', type: 'line' }],
      dataset: [{ stat_day: '2026-03-01', publish_cnt: 3 }],
      error: null
    }

    // Tool result delivered as Claude content blocks (array of {type,text}).
    const fromContentBlocks = extractChartSpec([
      { type: 'text', text: JSON.stringify(spec) }
    ])
    expect(fromContentBlocks?.chart_type).toBe('line')
    expect(fromContentBlocks?.series[0].field).toBe('publish_cnt')

    // Tool result delivered as raw stdout text with surrounding noise.
    const fromStdout = extractChartSpec(`build ok\n${JSON.stringify(spec)}\n`)
    expect(fromStdout?.chart_type).toBe('line')

    // Direct object passthrough still works.
    expect(extractChartSpec(spec)?.title).toBe('最近30天工作流发布趋势')

    // Non-chart output stays null so unrelated tools are not promoted.
    expect(extractChartSpec([{ type: 'text', text: 'no chart here' }])).toBeNull()
  })

  it('extracts and strips xml-style chart spec blocks', () => {
    const message = `
结论如下：

<chart_spec>
{"kind":"chart_spec","version":1,"chart_type":"line","title":"最近30天工作流发布趋势","x_field":"stat_day","series":[{"name":"发布次数","field":"publish_cnt","type":"line"}],"dataset":[{"stat_day":"2026-03-10","publish_cnt":3}],"error":null}
</chart_spec>
`

    const specs = extractChartSpecsFromText(message)
    const stripped = stripChartSpecsFromText(message)

    expect(specs).toHaveLength(1)
    expect(specs[0].chart_type).toBe('line')
    expect(stripped).toContain('结论如下：')
    expect(stripped).not.toContain('<chart_spec>')
    expect(stripped).not.toContain('"chart_type":"line"')
  })

  it('extracts and strips raw inline chart_spec JSON written in the conclusion prose', () => {
    const message = `结论如下：发布次数整体上升。
{"kind":"chart_spec","version":1,"chart_type":"line","title":"最近30天工作流发布趋势","x_field":"stat_day","series":[{"name":"发布次数","field":"publish_cnt","type":"line"}],"dataset":[{"stat_day":"2026-03-10","publish_cnt":3}],"error":null}
以上为本次结论。`

    const specs = extractChartSpecsFromText(message)
    const stripped = stripChartSpecsFromText(message)

    expect(specs).toHaveLength(1)
    expect(specs[0].chart_type).toBe('line')
    expect(stripped).toContain('结论如下：发布次数整体上升。')
    expect(stripped).toContain('以上为本次结论。')
    expect(stripped).not.toContain('chart_type')
    expect(stripped).not.toContain('{')
  })

  it('extracts chart specs from ```json fences', () => {
    const message = '前置说明\n\n```json\n{"kind":"chart_spec","version":1,"chart_type":"bar","title":"各数据层表数量对比","x_field":"layer","series":[{"name":"表数量","field":"table_cnt","type":"bar"}],"dataset":[{"layer":"DWD","table_cnt":18}],"error":null}\n```'

    const specs = extractChartSpecsFromText(message)
    expect(specs).toHaveLength(1)
    expect(specs[0].chart_type).toBe('bar')
  })

  it('splits prose into ordered text and chart segments', () => {
    const message = `开头说明。
{"kind":"chart_spec","version":1,"chart_type":"line","title":"趋势","x_field":"stat_day","series":[{"name":"次数","field":"cnt","type":"line"}],"dataset":[{"stat_day":"2026-03-10","cnt":3}],"error":null}
结尾说明。`

    const segments = splitChartSpecText(message)

    expect(segments.map((seg) => seg.type)).toEqual(['text', 'chart', 'text'])
    expect(segments[0].value).toBe('开头说明。')
    expect(segments[1].spec.chart_type).toBe('line')
    expect(segments[2].value).toBe('结尾说明。')
  })

  it('leaves an incomplete streaming chart_spec as plain text until it closes', () => {
    const partial = '结论：\n{"kind":"chart_spec","version":1,"chart_type":"line","dataset":[{"stat_day":"2026-03-10"'

    const segments = splitChartSpecText(partial)

    expect(segments.every((seg) => seg.type === 'text')).toBe(true)
    expect(extractChartSpecsFromText(partial)).toHaveLength(0)
  })

  it('keeps non-chart json fences untouched', () => {
    const message = '说明\n\n```json\n{"foo":"bar"}\n```'
    expect(extractChartSpecsFromText(message)).toHaveLength(0)
    expect(stripChartSpecsFromText(message)).toContain('"foo":"bar"')
  })

  it('builds an area chart with filled line series', () => {
    const renderModel = buildChartRenderModel({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'area',
      title: '活跃用户趋势',
      x_field: 'stat_day',
      area: true,
      series: [{ name: '活跃用户', field: 'active_users', type: 'line' }],
      dataset: [
        { stat_day: '2026-03-01', active_users: 120 },
        { stat_day: '2026-03-02', active_users: 150 }
      ],
      error: null
    })

    expect(renderModel.state).toBe('renderable')
    expect(renderModel.kind).toBe('echarts')
    expect(renderModel.option.series[0].type).toBe('line')
    expect(renderModel.option.series[0].areaStyle).toEqual({})
    expect(renderModel.option.series[0].data).toEqual([120, 150])
  })

  it('builds a scatter chart with numeric x and [x, y] points', () => {
    const renderModel = buildChartRenderModel({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'scatter',
      title: '行数与字段数相关性',
      x_field: 'table_rows',
      series: [{ name: '字段数', field: 'column_count', type: 'scatter' }],
      dataset: [
        { table_rows: 100, column_count: 12 },
        { table_rows: 250, column_count: 18 }
      ],
      error: null
    })

    expect(renderModel.state).toBe('renderable')
    expect(renderModel.option.xAxis.type).toBe('value')
    expect(renderModel.option.series[0].type).toBe('scatter')
    expect(renderModel.option.series[0].data).toEqual([[100, 12], [250, 18]])
  })

  it('builds a combo chart with dual value axes', () => {
    const renderModel = buildChartRenderModel({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'combo',
      title: '金额与增速',
      x_field: 'month',
      series: [
        { name: '金额', field: 'amount', type: 'bar', axis: 'left' },
        { name: '增速', field: 'growth_rate', type: 'line', axis: 'right' }
      ],
      dataset: [
        { month: '2026-01', amount: 1200, growth_rate: 0.12 },
        { month: '2026-02', amount: 1500, growth_rate: 0.25 }
      ],
      error: null
    })

    expect(renderModel.state).toBe('renderable')
    expect(Array.isArray(renderModel.option.yAxis)).toBe(true)
    expect(renderModel.option.yAxis).toHaveLength(2)
    expect(renderModel.option.series[0].type).toBe('bar')
    expect(renderModel.option.series[0].yAxisIndex).toBe(0)
    expect(renderModel.option.series[1].type).toBe('line')
    expect(renderModel.option.series[1].yAxisIndex).toBe(1)
  })

  it('builds a radar chart with one indicator per dataset row', () => {
    const renderModel = buildChartRenderModel({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'radar',
      title: '数据质量评估',
      x_field: 'metric',
      series: [{ name: '评分', field: 'score', type: 'radar' }],
      dataset: [
        { metric: '完整性', score: 90 },
        { metric: '及时性', score: 80 },
        { metric: '准确性', score: 85 }
      ],
      error: null
    })

    expect(renderModel.state).toBe('renderable')
    expect(renderModel.option.radar.indicator.map((i) => i.name)).toEqual(['完整性', '及时性', '准确性'])
    expect(renderModel.option.series[0].type).toBe('radar')
    expect(renderModel.option.series[0].data[0].value).toEqual([90, 80, 85])
  })

  it('builds a funnel chart from stage + single value rows', () => {
    const renderModel = buildChartRenderModel({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'funnel',
      title: '转化漏斗',
      x_field: 'stage',
      series: [{ name: '人数', field: 'cnt', type: 'funnel' }],
      dataset: [
        { stage: '曝光', cnt: 1000 },
        { stage: '点击', cnt: 400 }
      ],
      error: null
    })

    expect(renderModel.state).toBe('renderable')
    expect(renderModel.option.series[0].type).toBe('funnel')
    expect(renderModel.option.series[0].data).toEqual([
      { name: '曝光', value: 1000 },
      { name: '点击', value: 400 }
    ])
  })

  it('builds a gauge chart from the first row without requiring x_field', () => {
    const renderModel = buildChartRenderModel({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'gauge',
      title: '完成率',
      series: [{ name: '完成率', field: 'completion_rate', type: 'gauge' }],
      dataset: [{ completion_rate: 73 }],
      error: null
    })

    expect(renderModel.state).toBe('renderable')
    expect(renderModel.option.series[0].type).toBe('gauge')
    expect(renderModel.option.series[0].data[0].value).toBe(73)
  })

  it('rejects funnel specs with more than one series', () => {
    const validation = validateChartSpec({
      kind: 'chart_spec',
      version: 1,
      chart_type: 'funnel',
      title: '转化漏斗',
      x_field: 'stage',
      series: [
        { name: '人数', field: 'cnt', type: 'funnel' },
        { name: '占比', field: 'ratio', type: 'funnel' }
      ],
      dataset: [{ stage: '曝光', cnt: 1000, ratio: 1 }],
      error: null
    })

    expect(validation.valid).toBe(false)
    expect(validation.errors).toContain('funnel 类型必须且只能提供一个 series')
  })
})
