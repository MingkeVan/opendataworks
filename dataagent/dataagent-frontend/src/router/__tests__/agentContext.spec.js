import { describe, expect, it } from 'vitest'

import { agentContextQuery, normalizeAgentId, withAgentContext } from '../agentContext'

describe('agent route context', () => {
  it('normalizes repeated query values and exposes only agent_id', () => {
    expect(normalizeAgentId(['agent_sales', 'agent_other'])).toBe('agent_sales')
    expect(agentContextQuery({ agent_id: 'agent_sales', topic_id: 'topic-1' })).toEqual({
      agent_id: 'agent_sales'
    })
  })

  it('inherits agent_id without leaking chat-local coordinates', () => {
    expect(withAgentContext(
      { path: '/skills' },
      { agent_id: 'agent_sales', topic_id: 'topic-1', message_id: 'a1' },
    )).toEqual({ path: '/skills', query: { agent_id: 'agent_sales' } })
  })

  it('lets an explicit assistant selection replace the inherited assistant', () => {
    expect(withAgentContext(
      { path: '/chat', query: { agent_id: 'agent_modeling' } },
      { agent_id: 'agent_sales' },
    )).toEqual({ path: '/chat', query: { agent_id: 'agent_modeling' } })
  })
})
