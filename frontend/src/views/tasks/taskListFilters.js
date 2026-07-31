const toPositiveTaskId = (value) => {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

export const buildRelationCountFilter = (direction, taskId) => {
  const resolvedTaskId = toPositiveTaskId(taskId)
  if (!resolvedTaskId) return null

  if (direction === 'upstream') {
    return {
      upstreamTaskId: '',
      downstreamTaskId: resolvedTaskId
    }
  }
  if (direction === 'downstream') {
    return {
      upstreamTaskId: resolvedTaskId,
      downstreamTaskId: ''
    }
  }
  return null
}
