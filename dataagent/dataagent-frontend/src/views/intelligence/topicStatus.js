/**
 * Map a topic's persisted current_task_status (da_agent_topic.current_task_status,
 * mirrored from da_agent_task.task_status) to a UI badge kind for the session /
 * conversation record list.
 *
 *   waiting | waiting_* | running -> 'running'  (in-progress; loading spinner)
 *   error              -> 'error'      (failed; red dot)
 *   suspended          -> 'suspended'  (cancelled; grey dot)
 *   finished | (none)  -> ''           (no badge; timestamp only)
 *
 * The ``waiting_*`` family (``waiting_permission`` while a write awaits
 * confirmation, ``waiting_input`` while an AskUserQuestion awaits the user's
 * selection) is a parked-but-active run: it must read as in-progress so the
 * spinner shows and callers keep the stream/polling alive.
 *
 * Returns '' for any unknown / terminal-success value so callers can treat the
 * empty string as "render nothing extra".
 */
export function topicStatusKind(currentTaskStatus) {
  const status = String(currentTaskStatus || '').trim()
  if (status === 'running' || status === 'waiting' || status.startsWith('waiting_')) return 'running'
  if (status === 'error') return 'error'
  if (status === 'suspended') return 'suspended'
  return ''
}
