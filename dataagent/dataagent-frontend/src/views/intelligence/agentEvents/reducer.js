/**
 * Neutral Pi AgentEvent -> the same chat blocks the SDK stream produces.
 *
 * The Pi data plane writes `record_type: "pi_event"` rows into the same
 * da_agent_sdk_record table as the SDK path. Rendering must not be able to tell
 * which engine ran a turn, so this reducer deliberately produces the *identical*
 * block shape as `v2StreamParser` — `output`/`is_error`/`inputJson`, not
 * `result`/`error`. The render adapter `blockToToolProp` reads `block.output`
 * and `block.is_error`; a block that used different names would render every
 * Pi tool call as a permanently pending call with no output.
 *
 * That equality is not a convention to remember: the shared fixture at
 * dataagent/contracts/sdk-block-projection/cases.json carries "pi " cases whose
 * expected output is byte-identical to their SDK counterparts, and both this
 * reducer and the backend projection are held to it.
 */

/** Neutral event types, mirroring dataagent/contracts/agent-events/v1. */
export const AgentEventType = Object.freeze({
  RUN_STARTED: 'run.started',
  TURN_STARTED: 'turn.started',
  CONTENT_DELTA: 'content.delta',
  TOOL_STARTED: 'tool.started',
  TOOL_PROGRESS: 'tool.progress',
  TOOL_COMPLETED: 'tool.completed',
  TOOL_DENIED: 'tool.denied',
  USAGE_UPDATED: 'usage.updated',
  TURN_COMPLETED: 'turn.completed',
  RUN_COMPLETED: 'run.completed',
  RUN_FAILED: 'run.failed',
  RUN_CANCELLED: 'run.cancelled',
  RUN_SUSPENDED: 'run.suspended',
})

const TERMINAL_TYPES = new Set([
  AgentEventType.RUN_COMPLETED,
  AgentEventType.RUN_FAILED,
  AgentEventType.RUN_CANCELLED,
  AgentEventType.RUN_SUSPENDED,
])

/**
 * Apply one `pi_event` record to the chat state.
 *
 * @param {object} state  Created by createChatState() in v2StreamParser
 * @param {object} record { record_type: 'pi_event', event_type, data }
 */
export function reducePiEvent(state, record) {
  const type = String(record.event_type || '')
  const data = record.data || {}

  if (!state._piContentBlocks) {
    // Pi addresses content blocks by an opaque content_id rather than the SDK's
    // integer index, so it needs its own lookup. Underscore-prefixed so the
    // canonical projection drops it, exactly as the backend does with _idx.
    state._piContentBlocks = {}
  }

  switch (type) {
    case AgentEventType.RUN_STARTED: {
      state.status = 'streaming'
      break
    }

    case AgentEventType.TURN_STARTED: {
      state.turns.push({ blocks: [] })
      state._piContentBlocks = {}
      break
    }

    case AgentEventType.CONTENT_DELTA: {
      const turn = _currentTurn(state)
      if (!turn) break
      const isReasoning = String(data.kind || '') === 'reasoning'
      // 'text', not the backend's 'main_text': the two projections use
      // different names for this block and the shared fixture normalizes each
      // to the same canonical kind.
      const blockType = isReasoning ? 'thinking' : 'text'
      const key = String(data.content_id || '')
      let block = key ? state._piContentBlocks[key] : null
      if (!block || block.type !== blockType) {
        block = { type: blockType, content: '', status: 'streaming' }
        turn.blocks.push(block)
        state.blocks.push(block)
        if (key) state._piContentBlocks[key] = block
      }
      block.content += String(data.delta || '')
      break
    }

    case AgentEventType.TOOL_STARTED: {
      const turn = _currentTurn(state)
      if (!turn) break
      const block = {
        type: 'tool_use',
        id: String(data.tool_call_id || ''),
        name: String(data.tool_name || 'Tool'),
        content: '',
        inputJson: '',
        input: data.input ?? null,
        output: null,
        is_error: false,
        status: 'streaming',
      }
      turn.blocks.push(block)
      state.blocks.push(block)
      break
    }

    case AgentEventType.TOOL_COMPLETED: {
      const block = _findToolBlock(state, data.tool_call_id)
      if (block) {
        block.output = data.output ?? null
        block.is_error = Boolean(data.is_error)
        block.status = 'done'
      }
      break
    }

    case AgentEventType.TOOL_DENIED: {
      const block = _findToolBlock(state, data.tool_call_id)
      if (block) {
        block.output = String(data.reason || '工具调用被工作区边界策略拒绝')
        block.is_error = true
        block.status = 'done'
      }
      break
    }

    case AgentEventType.USAGE_UPDATED: {
      state.usage = data.usage ?? state.usage
      break
    }

    case AgentEventType.RUN_FAILED: {
      state.status = 'error'
      state.errorText = String(data.message || data.error_code || '执行出错')
      break
    }

    default:
      break
  }

  if (TERMINAL_TYPES.has(type)) {
    // A suspended run is terminal too: leaving it 'streaming' would spin the
    // UI forever waiting for events that will never arrive.
    if (type !== AgentEventType.RUN_FAILED) {
      state.status = 'done'
    }
    for (const block of state.blocks) {
      if (block.status === 'streaming') block.status = 'done'
    }
  }
}

function _currentTurn(state) {
  if (!state.turns.length) {
    // Tolerate a missing turn.started so a dropped event costs one grouping,
    // not the whole answer.
    state.turns.push({ blocks: [] })
  }
  return state.turns[state.turns.length - 1]
}

function _findToolBlock(state, toolCallId) {
  const id = String(toolCallId || '')
  if (!id) return null
  for (let i = state.blocks.length - 1; i >= 0; i -= 1) {
    const block = state.blocks[i]
    if (block.type === 'tool_use' && block.id === id) return block
  }
  return null
}
