import { describe, it, expect } from 'vitest'
import {
  buildMetadataPrompt,
  buildObservedValueIndex,
  computeMetadataCompleteness,
  extractJsonBlock,
  filterEnumValuesByObserved,
  formatFieldComment,
  isWeakDescription,
  normalizeColumnValueProfiles,
  parseMetadataResponse
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
})
