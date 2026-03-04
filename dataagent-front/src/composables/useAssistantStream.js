import { onBeforeUnmount } from 'vue'

export function useAssistantStream(assistantApi) {
  if (!assistantApi) {
    throw new Error('assistantApi client is required for useAssistantStream')
  }
  const streamMap = new Map()

  const closeRun = (runId) => {
    const source = streamMap.get(runId)
    if (!source) return
    source.close()
    streamMap.delete(runId)
  }

  const subscribeRun = (runId, handlers = {}) => {
    if (!runId) return () => {}
    closeRun(runId)

    const source = assistantApi.openRunStream(runId)
    streamMap.set(runId, source)

    source.onmessage = (event) => {
      let payload = null
      try {
        payload = JSON.parse(event.data)
        if (typeof payload === 'string') {
          payload = JSON.parse(payload)
        }
      } catch (error) {
        payload = { event: 'parse_error', data: event.data }
      }
      if (handlers.onEvent) {
        handlers.onEvent(payload)
      }
    }

    source.onerror = (error) => {
      if (handlers.onError) {
        handlers.onError(error)
      }
    }

    return () => closeRun(runId)
  }

  const closeAll = () => {
    for (const [runId, source] of streamMap.entries()) {
      source.close()
      streamMap.delete(runId)
    }
  }

  onBeforeUnmount(() => {
    closeAll()
  })

  return {
    subscribeRun,
    closeRun,
    closeAll
  }
}
