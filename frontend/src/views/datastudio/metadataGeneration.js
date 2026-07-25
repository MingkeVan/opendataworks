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
    relatedTasks = []
  } = context

  const fieldLines =
    (fields || [])
      .map((f) => `- ${f.fieldName} | ${f.fieldType || ''} | ${f.fieldComment ? f.fieldComment : '（空）'}`)
      .join('\n') || '（无字段）'

  const upstreamLines =
    (upstreamTables || []).map((t) => `- ${t.tableName} | ${t.tableComment || '（无注释）'}`).join('\n') || '（无）'

  const downstreamLines =
    (downstreamTables || []).map((t) => `- ${t.tableName} | ${t.tableComment || '（无注释）'}`).join('\n') || '（无）'

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
    '# 要求',
    '1. 推导表级业务说明(table_comment) 与每个字段业务含义(comment)，使用简体中文。',
    '2. comment 先给字段业务含义；若能从关联任务代码推断该字段加工逻辑，追加一句「加工逻辑：……」，点明来源表、分组维度与计算方式。',
    '3. 疑似枚举/状态/类型字段，结合 DDL 与任务代码中的取值(如 CASE WHEN status=0 ...)推导 enum_values；value 为原始取值，label 为中文含义；无法确定时该字段返回空数组。',
    '4. 只依据给定上下文推导，不编造无依据的含义；无把握时给保守简短说明。',
    '5. field_name 必须与上面字段列表完全一致，并覆盖全部字段。',
    '6. 只输出一个 JSON 代码块，不要任何解释文字。结构严格如下：',
    '```json',
    '{"table_comment":"...","fields":[{"field_name":"...","comment":"...","enum_values":[{"value":"0","label":"待支付"}]}]}',
    '```'
  ].join('\n')
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

  return {
    tableComment: String(parsed.table_comment ?? parsed.tableComment ?? '').trim(),
    fields
  }
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
