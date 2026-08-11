import { describe, it, expect } from 'vitest'
import {
  buildMetadataPrompt,
  buildObservedValueIndex,
  computeMetadataCompleteness,
  describeFreshnessConfig,
  extractJsonBlock,
  filterEnumValuesByObserved,
  filterFreshness,
  formatFieldComment,
  formatFreshnessContract,
  filterTableAttributes,
  isWeakDescription,
  normalizeColumnValueProfiles,
  normalizeFreshnessConfig,
  parseMetadataResponse,
  sameFreshnessContract
} from '../metadataGeneration'

describe('metadataGeneration', () => {
  it('buildMetadataPrompt 内嵌 DDL、字段、血缘与任务代码，并要求加工逻辑与 JSON 输出', () => {
    const prompt = buildMetadataPrompt({
      dbName: 'ods',
      tableName: 'orders',
      ddl: 'CREATE TABLE orders(status INT)',
      fields: [{ fieldName: 'status', fieldType: 'INT', fieldComment: '' }],
      upstreamTables: [{ tableName: 'src_orders', tableComment: '源订单' }],
      downstreamTables: [{ tableName: 'dws_orders' }],
      relatedTasks: [
        { taskName: 't1', engine: 'dolphin', relationType: 'write', taskSql: 'INSERT INTO orders SELECT ...' }
      ]
    })

    expect(prompt).toContain('CREATE TABLE orders(status INT)')
    expect(prompt).toContain('status | INT')
    expect(prompt).toContain('src_orders')
    expect(prompt).toContain('dws_orders')
    expect(prompt).toContain('INSERT INTO orders SELECT ...')
    expect(prompt).toContain('加工逻辑')
    expect(prompt).toContain('table_comment')
  })

  it('buildMetadataPrompt 在上下文缺失时给出占位而不是抛错', () => {
    const prompt = buildMetadataPrompt({ tableName: 'orders' })
    expect(prompt).toContain('（无字段）')
    expect(prompt).toContain('（无关联任务代码）')
    expect(prompt).toContain('（本次未取到实测取值）')
  })

  it('buildMetadataPrompt 内嵌实测取值，并声明枚举只能取自该清单', () => {
    const prompt = buildMetadataPrompt({
      tableName: 'orders',
      fields: [{ fieldName: 'status', fieldType: 'INT' }],
      columnValueProfiles: [
        {
          fieldName: 'status',
          values: [
            { value: '0', count: 1200 },
            { value: '1', count: 830 }
          ]
        }
      ]
    })

    expect(prompt).toContain('# 字段实测取值(字段名: 取值(出现行数)…)')
    expect(prompt).toContain('- status: 0(1200)、1(830)')
    expect(prompt).toContain('只能逐字复制')
  })

  it('normalizeColumnValueProfiles 丢弃缺字段名或缺取值的条目', () => {
    const profiles = normalizeColumnValueProfiles([
      { fieldName: 'status', values: [{ value: 0, count: '12' }, { value: '', count: 1 }] },
      { fieldName: '', values: [{ value: '1', count: 1 }] },
      { fieldName: 'empty', values: [] },
      'bad'
    ])

    expect(profiles).toEqual([{ fieldName: 'status', values: [{ value: '0', count: 12 }] }])
  })

  it('filterEnumValuesByObserved 丢弃实测数据里没出现过的取值', () => {
    const index = buildObservedValueIndex([
      { fieldName: 'status', values: [{ value: '0', count: 5 }, { value: '1', count: 3 }] }
    ])

    expect(
      filterEnumValuesByObserved(
        [
          { value: '0', label: '待支付' },
          { value: '1', label: '已支付' },
          { value: '9', label: '模型编造的取值' }
        ],
        index.get('status')
      )
    ).toEqual([
      { value: '0', label: '待支付' },
      { value: '1', label: '已支付' }
    ])
  })

  it('filterEnumValuesByObserved 对没有实测取值的字段一律清空枚举', () => {
    const index = buildObservedValueIndex([])

    expect(filterEnumValuesByObserved([{ value: '0', label: '待支付' }], index.get('status'))).toEqual([])
    expect(filterEnumValuesByObserved([{ value: '0', label: '待支付' }], undefined)).toEqual([])
  })

  it('buildMetadataPrompt 内嵌可选的分层与业务域/数据域清单', () => {
    const prompt = buildMetadataPrompt({
      tableName: 'orders',
      layerOptions: [{ value: 'ODS' }, { value: 'DWD' }],
      businessDomains: [{ domainCode: 'TRADE', domainName: '交易' }],
      dataDomains: [{ domainCode: 'REFUND', domainName: '退款', businessDomain: 'TRADE' }]
    })

    expect(prompt).toContain('# 可选的表属性取值(只能从下列编码中原样选择)')
    expect(prompt).toContain('分层: ODS | DWD')
    expect(prompt).toContain('- TRADE（交易）')
    expect(prompt).toContain('- REFUND（退款，属于业务域 TRADE）')
    expect(prompt).toContain('table_attributes')
  })

  it('parseMetadataResponse 解析 table_attributes，缺失时给空值', () => {
    const withAttrs = parseMetadataResponse(
      '{"table_comment":"订单","table_attributes":{"layer":"DWD","business_domain":"TRADE","data_domain":"REFUND"},"fields":[]}'
    )
    expect(withAttrs.tableAttributes).toEqual({ layer: 'DWD', businessDomain: 'TRADE', dataDomain: 'REFUND' })

    const without = parseMetadataResponse('{"table_comment":"订单","fields":[]}')
    expect(without.tableAttributes).toEqual({ layer: '', businessDomain: '', dataDomain: '' })
  })

  it('filterTableAttributes 只保留平台已有的取值', () => {
    const options = {
      layerOptions: [{ value: 'ODS' }, { value: 'DWD' }],
      businessDomains: [{ domainCode: 'TRADE' }],
      dataDomains: [{ domainCode: 'REFUND', businessDomain: 'TRADE' }]
    }

    expect(
      filterTableAttributes({ layer: 'dwd', businessDomain: 'TRADE', dataDomain: 'REFUND' }, options)
    ).toEqual({ layer: 'DWD', businessDomain: 'TRADE', dataDomain: 'REFUND' })

    // 分层不在清单、业务域编造 -> 一律丢弃；业务域丢弃时数据域也不保留
    expect(
      filterTableAttributes({ layer: 'DWM', businessDomain: 'FAKE', dataDomain: 'REFUND' }, options)
    ).toEqual({ layer: '', businessDomain: '', dataDomain: '' })
  })

  it('filterTableAttributes 丢弃与所选业务域不匹配的数据域', () => {
    const options = {
      layerOptions: [{ value: 'DWD' }],
      businessDomains: [{ domainCode: 'TRADE' }, { domainCode: 'USER' }],
      dataDomains: [{ domainCode: 'REFUND', businessDomain: 'TRADE' }]
    }
    // REFUND 属于 TRADE，与所选 USER 不匹配
    expect(
      filterTableAttributes({ layer: 'DWD', businessDomain: 'USER', dataDomain: 'REFUND' }, options)
    ).toEqual({ layer: 'DWD', businessDomain: 'USER', dataDomain: '' })
  })

  it('extractJsonBlock 从带散文的 json 围栏中提取', () => {
    const text = '好的，结果如下：\n```json\n{"table_comment":"订单表","fields":[]}\n```\n以上。'
    expect(JSON.parse(extractJsonBlock(text)).table_comment).toBe('订单表')
  })

  it('extractJsonBlock 无围栏时回退到首尾花括号', () => {
    expect(JSON.parse(extractJsonBlock('前缀 {"table_comment":"订单"} 后缀')).table_comment).toBe('订单')
  })

  it('parseMetadataResponse 归一化字段名与枚举值', () => {
    const parsed = parseMetadataResponse(
      '```json\n{"table_comment":"订单","fields":[{"field_name":"status","comment":"订单状态","enum_values":[{"value":0,"label":"待支付"}]}]}\n```'
    )
    expect(parsed.tableComment).toBe('订单')
    expect(parsed.fields).toHaveLength(1)
    expect(parsed.fields[0].fieldName).toBe('status')
    expect(parsed.fields[0].enumValues[0]).toEqual({ value: '0', label: '待支付' })
  })

  it('parseMetadataResponse 丢弃缺少字段名的条目', () => {
    const parsed = parseMetadataResponse('{"table_comment":"订单","fields":[{"comment":"没有字段名"}]}')
    expect(parsed.fields).toHaveLength(0)
  })

  it('parseMetadataResponse 对非法 JSON 抛错', () => {
    expect(() => parseMetadataResponse('抱歉，我无法生成')).toThrow()
  })

  it('formatFieldComment 把枚举并入描述', () => {
    expect(
      formatFieldComment('订单状态', [
        { value: '0', label: '待支付' },
        { value: '1', label: '已支付' }
      ])
    ).toBe('订单状态。枚举：0=待支付；1=已支付')
    expect(formatFieldComment('', [{ value: '1', label: '是' }])).toBe('枚举：1=是')
    expect(formatFieldComment('金额', [])).toBe('金额')
  })

  it('isWeakDescription 覆盖空描述与同名描述', () => {
    expect(isWeakDescription('status', '')).toBe(true)
    expect(isWeakDescription('status', 'status')).toBe(true)
    expect(isWeakDescription('status', '订单状态')).toBe(false)
  })

  it('computeMetadataCompleteness 按表描述与字段描述计算比例', () => {
    // 4 项中命中 2 项（表描述 + 字段 a）
    expect(
      computeMetadataCompleteness({
        tableComment: '订单表',
        fields: [
          { fieldName: 'a', fieldComment: '甲' },
          { fieldName: 'b', fieldComment: '' },
          { fieldName: 'c', fieldComment: 'c' }
        ]
      })
    ).toBe(50)
    expect(computeMetadataCompleteness({ tableComment: '', fields: [] })).toBe(0)
    expect(computeMetadataCompleteness({ tableComment: '订单表', fields: [{ fieldName: 'a', fieldComment: '甲' }] })).toBe(100)
  })

  it('buildMetadataPrompt 内嵌数据新鲜度段与 JSON 结构，并回显现有配置', () => {
    const prompt = buildMetadataPrompt({
      tableName: 'orders',
      fields: [{ fieldName: 'etl_time', fieldType: 'DATETIME' }],
      currentFreshness: {
        mode: 'column',
        loadedAtField: 'etl_time',
        warnAfterCount: 1,
        warnAfterPeriod: 'day',
        errorAfterCount: 1,
        errorAfterPeriod: 'day'
      }
    })
    expect(prompt).toContain('# 数据新鲜度(可选建议)')
    expect(prompt).toContain('loaded_at_field')
    expect(prompt).toContain('现有新鲜度配置: 字段 · etl_time ｜ 预警 1天 · 过期 1天')
  })

  it('parseMetadataResponse 解析 freshness，缺失时为 null', () => {
    const withFresh = parseMetadataResponse(
      '{"table_comment":"订单","freshness":{"loaded_at_field":"etl_time","warn_after_count":1,"warn_after_period":"day","error_after_count":2,"error_after_period":"day"},"fields":[]}'
    )
    expect(withFresh.freshness).toMatchObject({ loadedAtField: 'etl_time', errorAfterCount: 2 })

    const without = parseMetadataResponse('{"table_comment":"订单","fields":[]}')
    expect(without.freshness).toBeNull()
  })

  it('filterFreshness 只认真实时间列，编造列一律丢弃', () => {
    const fields = [{ fieldName: 'etl_time' }, { fieldName: 'id' }]
    expect(
      filterFreshness(
        { loadedAtField: 'ETL_TIME', warnAfterCount: 1, warnAfterPeriod: 'day', errorAfterCount: 1, errorAfterPeriod: 'day' },
        { fields }
      )
    ).toEqual({
      mode: 'column',
      loadedAtField: 'etl_time',
      warnAfterCount: 1,
      warnAfterPeriod: 'day',
      errorAfterCount: 1,
      errorAfterPeriod: 'day',
      enabled: true
    })
    // 表里没有的列 -> 整体丢弃
    expect(filterFreshness({ loadedAtField: 'not_a_col' }, { fields })).toBeNull()
    expect(filterFreshness(null, { fields })).toBeNull()
  })

  it('filterFreshness 归一化非法阈值：period 回落 day、count 回落 1', () => {
    expect(
      filterFreshness(
        { loadedAtField: 'etl_time', warnAfterCount: 0, warnAfterPeriod: 'week', errorAfterCount: 3, errorAfterPeriod: 'hour' },
        { fields: [{ fieldName: 'etl_time' }] }
      )
    ).toMatchObject({ warnAfterCount: 1, warnAfterPeriod: 'day', errorAfterCount: 3, errorAfterPeriod: 'hour' })
  })

  it('normalizeFreshnessConfig 只归一化 column 模式，其余返回 null', () => {
    expect(normalizeFreshnessConfig({ mode: 'column', loadedAtField: 'ct', warnAfterCount: 1, warnAfterPeriod: 'day', errorAfterCount: 1, errorAfterPeriod: 'day' }))
      .toMatchObject({ loadedAtField: 'ct' })
    expect(normalizeFreshnessConfig({ mode: 'metadata' })).toBeNull()
    expect(normalizeFreshnessConfig(null)).toBeNull()
  })

  it('describeFreshnessConfig 覆盖未配置与非 column 模式', () => {
    expect(describeFreshnessConfig(null)).toBe('未配置')
    expect(describeFreshnessConfig({ mode: 'custom_sql' })).toBe('自定义查询（已配置）')
    expect(describeFreshnessConfig({ mode: 'metadata' })).toBe('表元数据（已配置）')
  })

  it('sameFreshnessContract 用于判断建议是否与现状一致', () => {
    const a = { loadedAtField: 'etl_time', warnAfterCount: 1, warnAfterPeriod: 'day', errorAfterCount: 1, errorAfterPeriod: 'day' }
    expect(sameFreshnessContract(a, { ...a })).toBe(true)
    expect(sameFreshnessContract(a, { ...a, errorAfterCount: 2 })).toBe(false)
    expect(sameFreshnessContract(null, a)).toBe(false)
  })

  it('formatFreshnessContract 输出一行中文描述', () => {
    expect(
      formatFreshnessContract({ loadedAtField: 'etl_time', warnAfterCount: 1, warnAfterPeriod: 'day', errorAfterCount: 2, errorAfterPeriod: 'hour' })
    ).toBe('字段 · etl_time ｜ 预警 1天 · 过期 2小时')
    expect(formatFreshnessContract(null)).toBe('')
  })
})
