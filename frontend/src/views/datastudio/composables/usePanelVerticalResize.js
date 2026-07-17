import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

// 右侧面板上下分栏（P2-2 F17b）：顶部 tabs 与底部血缘区的高度分配、
// 拖拽调整与按 tab 记忆，从 DataStudioRightPanel.vue 逐字抽出。
export function usePanelVerticalResize({
  activeTabId,
  hasTableTab,
  defaultTopHeight = 340,
  minTopHeight = 260,
  minBottomHeight = 280,
  resizerHeight = 6,
}) {
  const panelShellRef = ref(null)
  const panelTopHeights = ref({})
  const isPanelResizing = ref(false)
  let panelResizeMoveHandler = null
  let panelResizeUpHandler = null

  const clampTopHeight = (height, containerHeight = 0) => {
    const maxTop = containerHeight > 0
      ? Math.max(minTopHeight, containerHeight - minBottomHeight - resizerHeight)
      : 520
    return Math.max(minTopHeight, Math.min(maxTop, height))
  }

  const getCurrentTopHeight = (tabId) => {
    if (!tabId) return defaultTopHeight
    const stored = panelTopHeights.value[tabId]
    return Number.isFinite(stored) ? stored : defaultTopHeight
  }

  const panelShellStyle = computed(() => {
    if (!hasTableTab.value) return {}
    return {
      '--right-top': `${getCurrentTopHeight(activeTabId.value)}px`
    }
  })

  const ensurePanelTopHeight = async (tabId) => {
    if (!tabId || !hasTableTab.value) return
    if (Number.isFinite(panelTopHeights.value[tabId])) return

    await nextTick()
    const containerHeight = panelShellRef.value?.getBoundingClientRect()?.height || 0
    const expected = containerHeight > 0 ? Math.round(containerHeight * 0.42) : defaultTopHeight
    const next = clampTopHeight(expected, containerHeight)
    panelTopHeights.value = {
      ...panelTopHeights.value,
      [tabId]: next
    }
  }

  watch(
    () => [activeTabId.value, hasTableTab.value],
    ([tabId, enabled]) => {
      if (!enabled || !tabId) return
      void ensurePanelTopHeight(tabId)
    },
    { immediate: true }
  )

  const stopPanelResize = () => {
    isPanelResizing.value = false
    if (panelResizeMoveHandler) {
      window.removeEventListener('mousemove', panelResizeMoveHandler)
      panelResizeMoveHandler = null
    }
    if (panelResizeUpHandler) {
      window.removeEventListener('mouseup', panelResizeUpHandler)
      panelResizeUpHandler = null
    }
  }

  const startPanelResize = (event) => {
    const tabId = activeTabId.value
    const container = panelShellRef.value
    if (!tabId || !container) return
    event.preventDefault()

    const containerRect = container.getBoundingClientRect()
    const startY = event.clientY
    const startHeight = getCurrentTopHeight(tabId)
    isPanelResizing.value = true

    panelResizeMoveHandler = (moveEvent) => {
      const delta = moveEvent.clientY - startY
      const next = clampTopHeight(startHeight + delta, containerRect.height)
      panelTopHeights.value = {
        ...panelTopHeights.value,
        [tabId]: next
      }
    }

    panelResizeUpHandler = () => {
      stopPanelResize()
    }

    window.addEventListener('mousemove', panelResizeMoveHandler)
    window.addEventListener('mouseup', panelResizeUpHandler)
  }

  onBeforeUnmount(() => {
    stopPanelResize()
  })

  return {
    panelShellRef,
    panelTopHeights,
    isPanelResizing,
    panelShellStyle,
    clampTopHeight,
    getCurrentTopHeight,
    ensurePanelTopHeight,
    startPanelResize,
    stopPanelResize,
  }
}
