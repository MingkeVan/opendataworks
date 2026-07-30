// 表元数据智能生成的纯函数层：prompt 组装、AI 返回解析、描述归一化与完善度计算。
// 纯函数、无 Vue 依赖、无副作用，便于单测（与 fieldEdit.js 同一约定）。

const line = (label, value) => `${label}: ${value == null || value === '' ? '（空）' : value}`

/**
 * 组装元数据推导 prompt。
 * 上下文来自表详情已加载的数据：DDL、字段、上下游血缘、关联任务代码。
 */
export function buildMetadataPrompt(context = {}) {
  const {
    dbName = '',
    tableName = '',
    tableType = '',
    layer = '',
    tableComment = '',
    ddl = '',
    fields = [],
    upstreamTables = [],
    downstreamTables = [],
    relatedTasks = [],
    columnValueProfiles = [],
    layerOptions = [],
    businessDomains = [],
    dataDomains = []
  } = context

  const fieldLines =
    (fields || [])
      .map((f) => `- ${f.fieldName} | ${f.fieldType || ''} | ${f.fieldComment ? f.fieldComment : '（空）'}`)
      .join('\n') || '（无字段）'

  const upstreamLines =
    (upstreamTables || []).map((t) => `- ${t.tableName} | ${t.tableComment || '（无注释）'}`).join('\n') || '（无）'

  const downstreamLines =
    (downstreamTables || []).map((t) => `- ${t.tableName} | ${t.tableComment || '（无注释）'}`).join('\n') || '（无）'

  const profiles = normalizeColumnValueProfiles(columnValueProfiles)
  const enumLines =
    profiles
      .map(
        (profile) =>
          `- ${profile.fieldName}: ` +
          profile.values.map((item) => `${item.value}(${item.count ?? '-'})`).join('、')
      )
      .join('\n') || '（本次未取到实测取值）'

  // 分层/业务域/数据域只能取平台已有编码，否则写回就是脏值；清单同时喂给模型并在写回前硬过滤
  const layerLine =
    (layerOptions || []).map((item) => String(item?.value ?? item ?? '').trim()).filter(Boolean).join(' | ') ||
    '（无可选分层）'
  const businessDomainLines =
    (businessDomains || [])
      .map((item) => `- ${item.domainCode}（${item.domainName || '未命名'}）`)
      .join('\n') || '（无可选业务域）'
  const dataDomainLines =
    (dataDomains || [])
      .map((item) => `- ${item.domainCode}（${item.domainName || '未命名'}，属于业务域 ${item.businessDomain || '未指定'}）`)
      .join('\n') || '（无可选数据域）'

  const taskBlocks =
    (relatedTasks || [])
      .filter((t) => String(t.taskSql || '').trim())
      .map(
        (t) =>
          `【任务】${t.taskName || '-'} | 引擎:${t.engine || '-'} | 关系:${t.relationType || '-'}\n` +
          '```sql\n' +
          `${t.taskSql}\n` +
          '```'
      )
      .join('\n\n') || '（无关联任务代码）'

  return [
    '你是 OpenDataWorks 的数据元数据业务语义专家。',
    '基于下面这张缺少业务注释的表的结构、上下游血缘与关联任务代码，推导中文业务元数据。',
    '',
    '# 表',
    line('库名', dbName),
    line('表名', tableName),
    line('表类型', tableType),
    line('分层', layer),
    line('现有表注释', tableComment),
    '',
    '# 建表语句(DDL)',
    String(ddl || '（无）').trim(),
    '',
    '# 字段(字段名 | 类型 | 现有注释)',
    fieldLines,
    '',
    '# 上游表(表名 | 注释)',
    upstreamLines,
    '',
    '# 下游表(表名 | 注释)',
    downstreamLines,
    '',
    '# 关联任务代码',
    taskBlocks,
    '',
    '# 字段实测取值(字段名: 取值(出现行数)…)',
    '以下取值由平台直接查询该表真实数据得到，是本次唯一可用的枚举取值来源。',
    enumLines,
    '',
    '# 可选的表属性取值(只能从下列编码中原样选择)',
    `分层: ${layerLine}`,
    '业务域:',
    businessDomainLines,
    '数据域:',
    dataDomainLines,
    '',
    '# 要求',
    '1. 推导表级业务说明(table_comment) 与每个字段业务含义(comment)，使用简体中文。',
    '2. comment 先给字段业务含义；若能从关联任务代码推断该字段加工逻辑，追加一句「加工逻辑：……」，点明来源表、分组维度与计算方式。',
    '3. enum_values 的 value 只能逐字复制「字段实测取值」中列出的取值：未出现在该清单里的字段一律返回空数组，也不得补充清单外的取值。label 由你结合 DDL、任务代码与字段名给出中文含义；含义无法确定时保留 value 并给空 label，不要猜。',
    '4. 只依据给定上下文推导，不编造无依据的含义；无把握时给保守简短说明。',
    '5. field_name 必须与上面字段列表完全一致，并覆盖全部字段。',
    '6. table_attributes 推断该表的分层与归属：layer / business_domain / data_domain 的取值必须逐字复制「可选的表属性取值」中的编码；data_domain 必须属于所选 business_domain；任一项无法确定就留空字符串，不要猜。',
    '7. 只输出一个 JSON 代码块，不要任何解释文字。结构严格如下：',
    '```json',
    '{"table_comment":"...","table_attributes":{"layer":"DWD","business_domain":"","data_domain":""},"fields":[{"field_name":"...","comment":"...","enum_values":[{"value":"0","label":"待支付"}]}]}',
    '```'
  ].join('\n')
}

/**
 * 归一化后端 `GET /v1/tables/{id}/column-values` 的实测取值分布。
 * 结构不合法的条目直接丢弃：缺了实测取值只会少写枚举，不会写错枚举。
 */
export function normalizeColumnValueProfiles(raw) {
  if (!Array.isArray(raw)) return []
  return raw
    .map((item) => {
      if (!item || typeof item !== 'object') return null
      const fieldName = String(item.fieldName ?? item.field_name ?? '').trim()
      if (!fieldName) return null
      const values = (Array.isArray(item.values) ? item.values : [])
        .map((entry) => {
          if (!entry || typeof entry !== 'object') return null
          const value = entry.value
          if (value === null || value === undefined || String(value).trim() === '') return null
          const count = Number(entry.count)
          return { value: String(value).trim(), count: Number.isFinite(count) ? count : null }
        })
        .filter(Boolean)
      if (!values.length) return null
      return { fieldName, values }
    })
    .filter(Boolean)
}

/**
 * 字段名 -> 实测取值集合（小写归一，用于比对模型给出的 value）。
 */
export function buildObservedValueIndex(profiles) {
  const index = new Map()
  normalizeColumnValueProfiles(profiles).forEach((profile) => {
    index.set(
      profile.fieldName,
      new Set(profile.values.map((item) => item.value.toLowerCase()))
    )
  })
  return index
}

/**
 * 丢弃未在真实数据中出现过的枚举取值。
 *
 * 这是「枚举不许编造」的硬约束：prompt 只是要求，实际写回前一律按实测取值过滤。
 * 该字段没有实测取值（不是枚举候选列、取值过于发散、或统计失败）时返回空数组。
 */
export function filterEnumValuesByObserved(enumValues, observedValues) {
  const list = normalizeEnumValues(enumValues)
  if (!list.length || !observedValues || !observedValues.size) return []
  return list.filter((item) => observedValues.has(item.value.toLowerCase()))
}

/**
 * 从助手回复中提取 JSON 文本：优先取最后一个 ``` 围栏，回退取首 { 到末 } 的子串。
 */
export function extractJsonBlock(text) {
  const source = String(text || '')
  const fence = /```(?:json)?\s*([\s\S]*?)```/gi
  let match
  let last = null
  while ((match = fence.exec(source)) !== null) {
    if (match[1] && match[1].trim()) last = match[1].trim()
  }
  if (last) return last

  const start = source.indexOf('{')
  const end = source.lastIndexOf('}')
  if (start !== -1 && end > start) return source.slice(start, end + 1).trim()
  return source.trim()
}

const normalizeEnumValues = (raw) => {
  if (!Array.isArray(raw)) return []
  return raw
    .map((item) => {
      if (!item || typeof item !== 'object') return null
      const value = item.value ?? item.key ?? item.code
      const label = item.label ?? item.desc ?? item.name ?? ''
      if (value === null || value === undefined || String(value).trim() === '') return null
      return { value: String(value).trim(), label: String(label || '').trim() }
    })
    .filter(Boolean)
}

/**
 * 解析 AI 返回的元数据；结构不合法时抛错，不做静默降级。
 */
export function parseMetadataResponse(text) {
  const block = extractJsonBlock(text)
  let parsed
  try {
    parsed = JSON.parse(block)
  } catch {
    throw new Error('无法解析 AI 返回的元数据（非合法 JSON）')
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('AI 返回的元数据结构不正确')
  }

  const fields = (Array.isArray(parsed.fields) ? parsed.fields : [])
    .map((item) => {
      if (!item || typeof item !== 'object') return null
      const fieldName = String(item.field_name ?? item.fieldName ?? '').trim()
      if (!fieldName) return null
      return {
        fieldName,
        comment: String(item.comment ?? '').trim(),
        enumValues: normalizeEnumValues(item.enum_values ?? item.enumValues)
      }
    })
    .filter(Boolean)

  const rawAttrs = parsed.table_attributes ?? parsed.tableAttributes
  const attrs = rawAttrs && typeof rawAttrs === 'object' && !Array.isArray(rawAttrs) ? rawAttrs : {}

  return {
    tableComment: String(parsed.table_comment ?? parsed.tableComment ?? '').trim(),
    tableAttributes: {
      layer: String(attrs.layer ?? '').trim(),
      businessDomain: String(attrs.business_domain ?? attrs.businessDomain ?? '').trim(),
      dataDomain: String(attrs.data_domain ?? attrs.dataDomain ?? '').trim()
    },
    fields
  }
}

/**
 * 按平台已有取值硬过滤表属性建议。
 *
 * prompt 只是要求，实际写回前一律在这里过滤：分层必须是已知分层，业务域/数据域必须是
 * 已有编码；数据域还必须归属于所选业务域。业务域被丢弃时数据域一并丢弃（存在依赖关系）。
 */
export function filterTableAttributes(attributes, options = {}) {
  const { layerOptions = [], businessDomains = [], dataDomains = [] } = options
  const attrs = attributes && typeof attributes === 'object' ? attributes : {}

  const layerSet = new Set(
    (layerOptions || []).map((item) => String(item?.value ?? item ?? '').trim().toUpperCase()).filter(Boolean)
  )
  const rawLayer = String(attrs.layer || '').trim().toUpperCase()
  const layer = layerSet.has(rawLayer) ? rawLayer : ''

  const businessSet = new Set(
    (businessDomains || []).map((item) => String(item?.domainCode || '').trim()).filter(Boolean)
  )
  const rawBusiness = String(attrs.businessDomain || '').trim()
  const businessDomain = businessSet.has(rawBusiness) ? rawBusiness : ''

  let dataDomain = ''
  const rawData = String(attrs.dataDomain || '').trim()
  if (businessDomain && rawData) {
    const matched = (dataDomains || []).find((item) => String(item?.domainCode || '').trim() === rawData)
    // 数据域必须归属所选业务域，否则是跨域的无效组合
    if (matched && String(matched.businessDomain || '').trim() === businessDomain) {
      dataDomain = rawData
    }
  }

  return { layer, businessDomain, dataDomain }
}

/**
 * 枚举值描述并入字段注释（平台无独立枚举列，见设计文档）。
 */
export function formatFieldComment(comment, enumValues) {
  const base = String(comment || '').trim()
  const list = normalizeEnumValues(enumValues)
  if (!list.length) return base
  const enumText = list.map((item) => `${item.value}=${item.label}`).join('；')
  return base ? `${base}。枚举：${enumText}` : `枚举：${enumText}`
}

/**
 * 描述“弱”= 为空或与字段名相同，对应弹窗「仅看描述为空/描述与名称相同的字段」过滤项。
 */
export function isWeakDescription(fieldName, comment) {
  const text = String(comment || '').trim()
  if (!text) return true
  return text.toLowerCase() === String(fieldName || '').trim().toLowerCase()
}

/**
 * 元数据完善度：表描述算 1 项，每个字段描述各算 1 项，返回 0-100 的整数。
 */
export function computeMetadataCompleteness({ tableComment = '', fields = [] } = {}) {
  const list = Array.isArray(fields) ? fields : []
  const total = list.length + 1
  let filled = String(tableComment || '').trim() ? 1 : 0
  list.forEach((field) => {
    if (!isWeakDescription(field.fieldName, field.fieldComment)) filled += 1
  })
  return Math.round((filled / total) * 100)
}
