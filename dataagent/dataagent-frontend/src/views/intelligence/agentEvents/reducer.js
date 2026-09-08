import { AgentEventType } from './types.js'

/**
 * Creates a fresh state for rendering one assistant reply turn from neutral AgentEvents.
 */
export function createAgentEventChatState() {
  return {
    turns: [],
    blocks: [],
    status: 'idle', // 'idle' | 'streaming' | 'done' | 'error'
    usage: null,
    errorText: null,
    lastSequence: 0,
    interactions: {},
  }
}

/**
 * Reduce a neutral AgentEvent into the chat state.
 * Mutates state in place for Vue reactivity.
 *
 * @param {object} state Created by createAgentEventChatState()
 * @param {object} event Neutral AgentEvent
 */
export function reduceAgentEvent(state, event) {
  if (!event || typeof event.sequence !== 'number') return

  // Sequence deduplication
  if (event.sequence <= state.lastSequence) {
    return
  }
  state.lastSequence = event.sequence

  const type = event.type
  const payload = event.payload || {}

  switch (type) {
    case AgentEventType.RUN_STARTED: {
      state.status = 'streaming'
      if (state.turns.length === 0) {
        _ensureTurn(state, 0)
      }
      break
    }

    case AgentEventType.TURN_STARTED: {
      const turnIndex = payload.turn_index ?? state.turns.length
      _ensureTurn(state, turnIndex)
      break
    }

    case AgentEventType.CONTENT_STARTED: {
      const turn = _ensureCurrentTurn(state)
      const isReasoning = payload.kind === 'reasoning'
      const block = {
        turnIndex: turn.turnIndex,
        blockIndex: turn.blocks.length,
        type: isReasoning ? 'thinking' : 'text',
        contentId: payload.content_id || '',
        content: '',
        status: 'streaming',
      }
      turn.blocks.push(block)
      state.blocks.push(block)
      break
    }

    case AgentEventType.CONTENT_DELTA: {
      const delta = payload.delta || ''
      const contentId = payload.content_id
      let targetBlock = null

      if (contentId) {
        targetBlock = state.blocks.find((b) => b.contentId === contentId)
      }
      if (!targetBlock) {
        const isReasoning = payload.kind === 'reasoning'
        const expectedType = isReasoning ? 'thinking' : 'text'
        targetBlock = [...state.blocks].reverse().find((b) => b.type === expectedType && b.status === 'streaming')
      }

      if (!targetBlock) {
        // Auto-create block if content.started was not emitted
        const turn = _ensureCurrentTurn(state)
        targetBlock = {
          turnIndex: turn.turnIndex,
          blockIndex: turn.blocks.length,
          type: payload.kind === 'reasoning' ? 'thinking' : 'text',
          contentId: contentId || '',
          content: '',
          status: 'streaming',
        }
        turn.blocks.push(targetBlock)
        state.blocks.push(targetBlock)
      }

      targetBlock.content += delta
      break
    }

    case AgentEventType.CONTENT_COMPLETED: {
      const contentId = payload.content_id
      const targetBlock = contentId
        ? state.blocks.find((b) => b.contentId === contentId)
        : [...state.blocks].reverse().find((b) => b.status === 'streaming')

      if (targetBlock) {
        if (payload.content !== undefined) {
          targetBlock.content = payload.content
        }
        targetBlock.status = 'done'
      }
      break
    }

    case AgentEventType.TOOL_STARTED: {
      const turn = _ensureCurrentTurn(state)
      const toolCallId = payload.tool_call_id || `tool-${state.blocks.length}`
      const block = {
        turnIndex: turn.turnIndex,
        blockIndex: turn.blocks.length,
        type: 'tool_use',
        id: toolCallId,
        name: payload.tool_name || payload.canonical_tool_id || 'unknown_tool',
        canonicalId: payload.canonical_tool_id,
        input: payload.input || {},
        status: 'streaming',
        result: null,
      }
      turn.blocks.push(block)
      state.blocks.push(block)
      break
    }

    case AgentEventType.TOOL_COMPLETED: {
      const toolCallId = payload.tool_call_id
      const block = state.blocks.find((b) => b.type === 'tool_use' && b.id === toolCallId)
      if (block) {
        block.status = 'done'
        block.result = payload.result
        block.error = payload.error
      }
      break
    }

    case AgentEventType.INTERACTION_REQUESTED: {
      const turn = _ensureCurrentTurn(state)
      const kind = payload.kind
      const interactionId = payload.interaction_id || `inter-${state.blocks.length}`
      const requestData = payload.request || {}

      const block = {
        turnIndex: turn.turnIndex,
        blockIndex: turn.blocks.length,
        type: kind === 'permission' ? 'permission_request' : 'question_request',
        interactionId,
        requestId: interactionId,
        kind,
        status: 'done',
        ...requestData,
      }
      turn.blocks.push(block)
      state.blocks.push(block)
      state.interactions[interactionId] = block
      break
    }

    case AgentEventType.INTERACTION_RESOLVED: {
      const interactionId = payload.interaction_id
      const block = state.interactions[interactionId]
      if (block) {
        block.interactionStatus = 'resolved'
        block.response = payload.response
      }
      break
    }

    case AgentEventType.USAGE_UPDATED: {
      state.usage = payload.usage
      break
    }

    case AgentEventType.RUN_COMPLETED: {
      state.status = 'done'
      // Finish any pending streaming blocks
      for (const block of state.blocks) {
        if (block.status === 'streaming') {
          block.status = 'done'
        }
      }
      break
    }

    case AgentEventType.RUN_FAILED: {
      state.status = 'error'
      state.errorText = String(payload.message || payload.error || '执行出错')
      for (const block of state.blocks) {
        if (block.status === 'streaming') {
          block.status = 'done'
        }
      }
      break
    }

    case AgentEventType.RUN_CANCELLED: {
      state.status = 'done'
      for (const block of state.blocks) {
        if (block.status === 'streaming') {
          block.status = 'done'
        }
      }
      break
    }

    default:
      break
  }
}

function _ensureTurn(state, turnIndex) {
  let turn = state.turns.find((t) => t.turnIndex === turnIndex)
  if (!turn) {
    turn = { turnIndex, blocks: [] }
    state.turns.push(turn)
  }
  return turn
}

function _ensureCurrentTurn(state) {
  if (state.turns.length === 0) {
    return _ensureTurn(state, 0)
  }
  return state.turns[state.turns.length - 1]
}
