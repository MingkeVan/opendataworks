/**
 * Neutral Agent Event types for DataAgent execution plane.
 */
export const AgentEventType = Object.freeze({
  RUN_STARTED: 'run.started',
  TURN_STARTED: 'turn.started',
  CONTENT_STARTED: 'content.started',
  CONTENT_DELTA: 'content.delta',
  CONTENT_COMPLETED: 'content.completed',
  TOOL_STARTED: 'tool.started',
  TOOL_PROGRESS: 'tool.progress',
  TOOL_COMPLETED: 'tool.completed',
  INTERACTION_REQUESTED: 'interaction.requested',
  INTERACTION_RESOLVED: 'interaction.resolved',
  USAGE_UPDATED: 'usage.updated',
  TURN_COMPLETED: 'turn.completed',
  RUN_COMPLETED: 'run.completed',
  RUN_FAILED: 'run.failed',
  RUN_CANCELLED: 'run.cancelled',
  RUN_SUSPENDED: 'run.suspended',
})

export const InteractionKind = Object.freeze({
  PERMISSION: 'permission',
  QUESTION: 'question',
  PLAN: 'plan',
})
