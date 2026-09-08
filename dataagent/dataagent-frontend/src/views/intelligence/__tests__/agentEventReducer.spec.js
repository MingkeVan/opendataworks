import { describe, it, expect } from 'vitest'
import { createAgentEventChatState, reduceAgentEvent } from '../agentEvents/reducer.js'
import { AgentEventType } from '../agentEvents/types.js'

describe('AgentEventReducer', () => {
  it('transitions state on run.started and streams text deltas', () => {
    const state = createAgentEventChatState()
    expect(state.status).toBe('idle')

    reduceAgentEvent(state, {
      sequence: 1,
      type: AgentEventType.RUN_STARTED,
    })
    expect(state.status).toBe('streaming')

    reduceAgentEvent(state, {
      sequence: 2,
      type: AgentEventType.CONTENT_STARTED,
      payload: { content_id: 'c-1', kind: 'text' },
    })

    reduceAgentEvent(state, {
      sequence: 3,
      type: AgentEventType.CONTENT_DELTA,
      payload: { content_id: 'c-1', delta: 'Hello ' },
    })

    reduceAgentEvent(state, {
      sequence: 4,
      type: AgentEventType.CONTENT_DELTA,
      payload: { content_id: 'c-1', delta: 'world!' },
    })

    reduceAgentEvent(state, {
      sequence: 5,
      type: AgentEventType.CONTENT_COMPLETED,
      payload: { content_id: 'c-1' },
    })

    expect(state.blocks.length).toBe(1)
    expect(state.blocks[0].content).toBe('Hello world!')
    expect(state.blocks[0].status).toBe('done')
  })

  it('deduplicates duplicate or older sequence events', () => {
    const state = createAgentEventChatState()

    reduceAgentEvent(state, {
      sequence: 1,
      type: AgentEventType.RUN_STARTED,
    })
    reduceAgentEvent(state, {
      sequence: 2,
      type: AgentEventType.CONTENT_DELTA,
      payload: { delta: 'Initial' },
    })
    // Duplicate sequence 2
    reduceAgentEvent(state, {
      sequence: 2,
      type: AgentEventType.CONTENT_DELTA,
      payload: { delta: 'DUP' },
    })

    expect(state.blocks[0].content).toBe('Initial')
  })

  it('handles tool execution and completion lifecycle', () => {
    const state = createAgentEventChatState()

    reduceAgentEvent(state, {
      sequence: 1,
      type: AgentEventType.TOOL_STARTED,
      payload: {
        tool_call_id: 'call-1',
        tool_name: 'ExecuteSql',
        canonical_tool_id: 'tool.portal.query',
        input: { sql: 'SELECT 1' },
      },
    })

    expect(state.blocks.length).toBe(1)
    const toolBlock = state.blocks[0]
    expect(toolBlock.type).toBe('tool_use')
    expect(toolBlock.name).toBe('ExecuteSql')
    expect(toolBlock.status).toBe('streaming')

    reduceAgentEvent(state, {
      sequence: 2,
      type: AgentEventType.TOOL_COMPLETED,
      payload: {
        tool_call_id: 'call-1',
        result: [{ col: 1 }],
      },
    })

    expect(toolBlock.status).toBe('done')
    expect(toolBlock.result).toEqual([{ col: 1 }])
  })

  it('handles interaction requested and resolved', () => {
    const state = createAgentEventChatState()

    reduceAgentEvent(state, {
      sequence: 1,
      type: AgentEventType.INTERACTION_REQUESTED,
      payload: {
        interaction_id: 'inter-1',
        kind: 'permission',
        request: { tool: 'Bash', command: 'ls -la' },
      },
    })

    expect(state.blocks.length).toBe(1)
    expect(state.blocks[0].type).toBe('permission_request')
    expect(state.blocks[0].interactionId).toBe('inter-1')

    reduceAgentEvent(state, {
      sequence: 2,
      type: AgentEventType.INTERACTION_RESOLVED,
      payload: {
        interaction_id: 'inter-1',
        response: { allow: true },
      },
    })

    expect(state.blocks[0].interactionStatus).toBe('resolved')
    expect(state.blocks[0].response).toEqual({ allow: true })
  })

  it('handles terminal run.completed and run.failed', () => {
    const state = createAgentEventChatState()

    reduceAgentEvent(state, {
      sequence: 1,
      type: AgentEventType.RUN_STARTED,
    })
    reduceAgentEvent(state, {
      sequence: 2,
      type: AgentEventType.RUN_FAILED,
      payload: { message: 'Database unreachable' },
    })

    expect(state.status).toBe('error')
    expect(state.errorText).toBe('Database unreachable')
  })
})
