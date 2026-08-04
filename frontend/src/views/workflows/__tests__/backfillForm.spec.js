import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import {
  buildBackfillPayload,
  isAfterToday,
  parseScheduleDateList,
  validateBackfillForm,
  validateBackfillRange
} from '../backfillForm'

const FORMAT = 'YYYY-MM-DD HH:mm:ss'
const todayStart = dayjs().startOf('day').format(FORMAT)
const todayEnd = dayjs().endOf('day').format(FORMAT)
const tomorrow = dayjs().add(1, 'day').format(FORMAT)
const yesterday = dayjs().subtract(1, 'day').format(FORMAT)

describe('isAfterToday', () => {
  it('rejects only days after today, keeping all of today selectable', () => {
    expect(isAfterToday(dayjs().add(1, 'day').toDate())).toBe(true)
    expect(isAfterToday(dayjs().endOf('day').toDate())).toBe(false)
    expect(isAfterToday(dayjs().subtract(1, 'day').toDate())).toBe(false)
  })
})

describe('validateBackfillRange', () => {
  it('requires a complete range', () => {
    expect(validateBackfillRange(null)).toBe('请选择补数时间范围')
    expect(validateBackfillRange([todayStart])).toBe('请选择补数时间范围')
    expect(validateBackfillRange([todayStart, ''])).toBe('请选择补数时间范围')
  })

  it('rejects an inverted range', () => {
    expect(validateBackfillRange([todayStart, yesterday])).toBe('开始时间不能晚于结束时间')
  })

  it('rejects dates after today', () => {
    expect(validateBackfillRange([todayStart, tomorrow])).toBe('补数时间不能晚于今天')
  })

  it('accepts a range ending at the last moment of today', () => {
    expect(validateBackfillRange([yesterday, todayEnd])).toBe('')
  })

  it('rejects malformed timestamps', () => {
    expect(validateBackfillRange(['2026/08/01', todayEnd]))
      .toBe(`时间格式不正确，应为 ${FORMAT}`)
  })
})

describe('parseScheduleDateList', () => {
  it('requires at least one entry', () => {
    expect(parseScheduleDateList('').error).toBe('请填写时间列表')
    expect(parseScheduleDateList('  ,  ').error).toBe('请填写时间列表')
  })

  it('trims and keeps valid past entries', () => {
    const { items, error } = parseScheduleDateList(` ${yesterday} , ${todayStart} `)
    expect(error).toBe('')
    expect(items).toEqual([yesterday, todayStart])
  })

  it('rejects a malformed entry', () => {
    expect(parseScheduleDateList(`${yesterday},2026-13-45 00:00:00`).error)
      .toContain('时间格式不正确')
  })

  it('rejects an entry after today', () => {
    expect(parseScheduleDateList(`${yesterday},${tomorrow}`).error)
      .toBe(`补数时间不能晚于今天：${tomorrow}`)
  })
})

describe('validateBackfillForm', () => {
  const baseForm = {
    mode: 'range',
    dateRange: [yesterday, todayEnd],
    scheduleDateList: '',
    runMode: 'RUN_MODE_SERIAL',
    expectedParallelismNumber: 8
  }

  it('passes a valid range form', () => {
    expect(validateBackfillForm(baseForm)).toBe('')
  })

  it('surfaces the range error', () => {
    expect(validateBackfillForm({ ...baseForm, dateRange: [todayStart, tomorrow] }))
      .toBe('补数时间不能晚于今天')
  })

  it('surfaces the list error in list mode', () => {
    expect(validateBackfillForm({ ...baseForm, mode: 'list', scheduleDateList: tomorrow }))
      .toBe(`补数时间不能晚于今天：${tomorrow}`)
  })

  it('checks parallelism only in parallel mode', () => {
    expect(validateBackfillForm({
      ...baseForm,
      runMode: 'RUN_MODE_PARALLEL',
      expectedParallelismNumber: 0
    })).toBe('并行度必须大于 0')
    expect(validateBackfillForm({ ...baseForm, expectedParallelismNumber: 0 })).toBe('')
  })
})

describe('buildBackfillPayload', () => {
  it('sends range fields in range mode', () => {
    const payload = buildBackfillPayload({
      mode: 'range',
      dateRange: [yesterday, todayEnd],
      scheduleDateList: '',
      runMode: 'RUN_MODE_SERIAL',
      expectedParallelismNumber: 8,
      complementDependentMode: 'OFF_MODE',
      allLevelDependent: false,
      executionOrder: 'DESC_ORDER',
      failureStrategy: 'CONTINUE'
    })
    expect(payload.startTime).toBe(yesterday)
    expect(payload.endTime).toBe(todayEnd)
    expect(payload.scheduleDateList).toBeNull()
    expect(payload.expectedParallelismNumber).toBeNull()
  })

  it('sends a normalized list in list mode and keeps parallelism when parallel', () => {
    const payload = buildBackfillPayload({
      mode: 'list',
      dateRange: [],
      scheduleDateList: ` ${yesterday} , ${todayStart} `,
      runMode: 'RUN_MODE_PARALLEL',
      expectedParallelismNumber: 4,
      complementDependentMode: 'OFF_MODE',
      allLevelDependent: true,
      executionOrder: 'ASC_ORDER',
      failureStrategy: 'END'
    })
    expect(payload.startTime).toBeNull()
    expect(payload.endTime).toBeNull()
    expect(payload.scheduleDateList).toBe(`${yesterday},${todayStart}`)
    expect(payload.expectedParallelismNumber).toBe(4)
  })
})
