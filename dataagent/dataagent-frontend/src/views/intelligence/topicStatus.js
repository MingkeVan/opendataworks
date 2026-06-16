/**
 * Map a topic's persisted current_task_status (da_agent_topic.current_task_status,
 * mirrored from da_agent_task.task_status) to a UI badge kind for the session /
 * conversation record list.
 *
 *   waiting_input      -> 'awaiting'   (parked: AskUserQuestion needs the user)
 *   waiting | waiting_* | running -> 'running'  (in-progress; loading spinner)
 *   error              -> 'error'      (failed; red dot)
 *   suspended          -> 'suspended'  (cancelled; grey dot)
 *   finished | (none)  -> ''           (no badge; timestamp only)
 *
 * ``waiting_input`` is a parked-but-active run blocked on the user's selection,
 * so it gets its own 'awaiting' kind (distinct "待输入" badge, not the spinner).
 * Other ``waiting_*`` states (e.g. ``waiting_permission``) read as in-progress.
 * Both still count as active so callers keep the stream/polling alive.
 *
 * Returns '' for any unknown / terminal-success value so callers can treat the
 * empty string as "render nothing extra".
 */
export function topicStatusKind(currentTaskStatus) {
  const status = String(currentTaskStatus || '').trim()
  if (status === 'waiting_input') return 'awaiting'
  if (status === 'running' || status === 'waiting' || status.startsWith('waiting_')) return 'running'
  if (status === 'error') return 'error'
  if (status === 'suspended') return 'suspended'
  return ''
}

/** Kinds that represent a live run; callers keep streaming/polling for these. */
export function isActiveStatusKind(kind) {
  return kind === 'running' || kind === 'awaiting'
}
