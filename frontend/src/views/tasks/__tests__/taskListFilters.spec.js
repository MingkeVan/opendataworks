import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { buildRelationCountFilter } from '../taskListFilters'

describe('task list relation count filters', () => {
  it('fills downstreamTaskId when the upstream count is clicked', () => {
    expect(buildRelationCountFilter('upstream', 12)).toEqual({
      upstreamTaskId: '',
      downstreamTaskId: 12
    })
  })

  it('fills upstreamTaskId when the downstream count is clicked', () => {
    expect(buildRelationCountFilter('downstream', '18')).toEqual({
      upstreamTaskId: 18,
      downstreamTaskId: ''
    })
  })

  it('rejects invalid task ids and directions', () => {
    expect(buildRelationCountFilter('upstream', '')).toBeNull()
    expect(buildRelationCountFilter('upstream', 0)).toBeNull()
    expect(buildRelationCountFilter('unknown', 1)).toBeNull()
  })

  it('keeps task id and name while removing internal list columns', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/tasks/TaskTable.vue'), 'utf8')

    expect(source).toContain('label="任务ID"')
    expect(source).toContain('label="任务名称"')
    expect(source).not.toContain('label="任务编码"')
    expect(source).not.toContain('label="调度配置"')
    expect(source).not.toContain('label="负责人"')
  })
})
