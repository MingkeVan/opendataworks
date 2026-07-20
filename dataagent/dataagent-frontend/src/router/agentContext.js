export function normalizeAgentId(value) {
  const first = Array.isArray(value) ? value[0] : value
  return String(first || '').trim()
}

export function agentContextQuery(sourceQuery = {}) {
  const agentId = normalizeAgentId(sourceQuery.agent_id)
  return agentId ? { agent_id: agentId } : {}
}

// Page navigation carries only the selected assistant. Chat-local topic/message
// coordinates must never leak into Skills, agent administration, or settings.
export function withAgentContext(location, sourceQuery = {}) {
  const target = typeof location === 'string' ? { path: location } : { ...location }
  const targetQuery = { ...(target.query || {}) }
  const explicitAgentId = normalizeAgentId(targetQuery.agent_id)
  const inheritedAgentId = normalizeAgentId(sourceQuery.agent_id)

  if (explicitAgentId) targetQuery.agent_id = explicitAgentId
  else if (inheritedAgentId) targetQuery.agent_id = inheritedAgentId
  else delete targetQuery.agent_id

  return { ...target, query: targetQuery }
}
