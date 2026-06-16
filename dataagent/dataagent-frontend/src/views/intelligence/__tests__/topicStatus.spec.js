import { describe, expect, it } from 'vitest'
import { topicStatusKind, isActiveStatusKind } from '../topicStatus'

describe('topicStatusKind', () => {
  it('maps in-progress statuses to running', () => {
    expect(topicStatusKind('waiting')).toBe('running')
    expect(topicStatusKind('running')).toBe('running')
  })

  it('gives waiting_input its own awaiting kind, others stay in-progress', () => {
    expect(topicStatusKind('waiting_input')).toBe('awaiting')
    expect(topicStatusKind('waiting_permission')).toBe('running')
  })

  it('treats running and awaiting as active (keep streaming)', () => {
    expect(isActiveStatusKind('running')).toBe(true)
    expect(isActiveStatusKind('awaiting')).toBe(true)
    expect(isActiveStatusKind('error')).toBe(false)
    expect(isActiveStatusKind('')).toBe(false)
  })

  it('maps terminal failure/cancel to their own kinds', () => {
    expect(topicStatusKind('error')).toBe('error')
    expect(topicStatusKind('suspended')).toBe('suspended')
  })

  it('returns empty string for finished, unknown, or missing status', () => {
    expect(topicStatusKind('finished')).toBe('')
    expect(topicStatusKind('')).toBe('')
    expect(topicStatusKind(null)).toBe('')
    expect(topicStatusKind(undefined)).toBe('')
    expect(topicStatusKind('something-else')).toBe('')
  })
})
