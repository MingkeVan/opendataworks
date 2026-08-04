// 工作流补数表单的纯校验与载荷构造（同 workflowDisplay / globalParams 套路）。
// 纯函数、无 Vue 依赖、无副作用，便于单测。
import dayjs from 'dayjs'
import customParseFormat from 'dayjs/plugin/customParseFormat'

dayjs.extend(customParseFormat)

export const BACKFILL_DATE_FORMAT = 'YYYY-MM-DD HH:mm:ss'

/**
 * 补数只针对已经过去的调度周期，禁止选择晚于今天的日期。
 * 按日粒度判断：今天当天的任意时刻都允许。
 * 同时用作 el-date-picker 的 disabled-date（入参是 Date）。
 */
export const isAfterToday = (value) => dayjs(value).isAfter(dayjs(), 'day')

const parseBackfillDate = (value) => dayjs(String(value ?? '').trim(), BACKFILL_DATE_FORMAT, true)

export const validateBackfillRange = (dateRange) => {
  if (!Array.isArray(dateRange) || dateRange.length !== 2 || !dateRange[0] || !dateRange[1]) {
    return '请选择补数时间范围'
  }
  const start = parseBackfillDate(dateRange[0])
  const end = parseBackfillDate(dateRange[1])
  if (!start.isValid() || !end.isValid()) {
    return `时间格式不正确，应为 ${BACKFILL_DATE_FORMAT}`
  }
  if (start.isAfter(end)) {
    return '开始时间不能晚于结束时间'
  }
  if (isAfterToday(end)) {
    return '补数时间不能晚于今天'
  }
  return ''
}

/**
 * 解析逗号分隔的时间列表，逐项校验格式与「不晚于今天」。
 * @returns {{ items: string[], error: string }}
 */
export const parseScheduleDateList = (text) => {
  const items = String(text ?? '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
  if (!items.length) {
    return { items: [], error: '请填写时间列表' }
  }
  for (const item of items) {
    const parsed = parseBackfillDate(item)
    if (!parsed.isValid()) {
      return { items: [], error: `时间格式不正确：${item}，应为 ${BACKFILL_DATE_FORMAT}` }
    }
    if (parsed.isAfter(dayjs(), 'day')) {
      return { items: [], error: `补数时间不能晚于今天：${item}` }
    }
  }
  return { items, error: '' }
}

/**
 * 校验补数表单，返回空串表示通过。
 * 工作流本身的前置条件（是否存在、是否已上线）由调用方判断。
 */
export const validateBackfillForm = (form) => {
  const dateError = form.mode === 'list'
    ? parseScheduleDateList(form.scheduleDateList).error
    : validateBackfillRange(form.dateRange)
  if (dateError) {
    return dateError
  }
  if (form.runMode === 'RUN_MODE_PARALLEL'
    && (!form.expectedParallelismNumber || form.expectedParallelismNumber < 1)) {
    return '并行度必须大于 0'
  }
  return ''
}

export const buildBackfillPayload = (form) => ({
  mode: form.mode,
  startTime: form.mode === 'range' ? form.dateRange[0] : null,
  endTime: form.mode === 'range' ? form.dateRange[1] : null,
  scheduleDateList: form.mode === 'list'
    ? parseScheduleDateList(form.scheduleDateList).items.join(',')
    : null,
  runMode: form.runMode,
  expectedParallelismNumber: form.runMode === 'RUN_MODE_PARALLEL'
    ? form.expectedParallelismNumber
    : null,
  complementDependentMode: form.complementDependentMode,
  allLevelDependent: form.allLevelDependent,
  executionOrder: form.executionOrder,
  failureStrategy: form.failureStrategy
})
