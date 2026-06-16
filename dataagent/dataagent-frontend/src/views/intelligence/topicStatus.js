/**
 * Map a topic's persisted current_task_status (da_agent_topic.current_task_status,
 * mirrored from da_agent_task.task_status) to a UI badge kind for the session /
 * conversation record list.
 *
 *   waiting_input      -> 'awaiting'             (parked: AskUserQuestion needs the user)
 *   waiting_permission -> 'awaiting_permission'  (parked: a write awaits confirmation)
 *   waiting | waiting_* | running -> 'running'   (in-progress; loading spinner)
 *   error              -> 'error'      (failed; red dot)
 *   suspended          -> 'suspended'  (cancelled; grey dot)
 *   finished | (none)  -> ''           (no badge; timestamp only)
 *
 * ``waiting_input`` / ``waiting_permission`` are parked-but-active runs blocked
 * on the user, so each gets its own kind (distinct "待输入" / "待确认" badge, not
 * the spinner). Other ``waiting_*`` states read as in-progress. All count as
 * active so callers keep the stream/polling alive.
 *
 * Returns '' for any unknown / terminal-success value so callers can treat the
 * empty string as "render nothing extra".
 */
export function topicStatusKind(currentTaskStatus) {
  const status = String(currentTaskStatus || '').trim()
  if (status === 'waiting_input') return 'awaiting'
  if (status === 'waiting_permission') return 'awaiting_permission'
  if (status === 'running' || status === 'waiting' || status.startsWith('waiting_')) return 'running'
  if (status === 'error') return 'error'
  if (status === 'suspended') return 'suspended'
  return ''
}

/** Kinds that represent a live run; callers keep streaming/polling for these. */
export function isActiveStatusKind(kind) {
  return kind === 'running' || kind === 'awaiting' || kind === 'awaiting_permission'
}
