import { computed, onBeforeUnmount, ref, unref, watch } from 'vue'

function calcStep(pending, streaming, options) {
  const minStep = Number(options.minStep || 1)
  const maxStep = Number(options.maxStep || 20)
  const settleBoost = Number(options.settleBoost || 28)

  if (!streaming) {
    return Math.min(settleBoost, Math.max(minStep, pending))
  }
  if (pending > 240) return Math.min(maxStep, 18)
  if (pending > 120) return Math.min(maxStep, 10)
  if (pending > 60) return Math.min(maxStep, 6)
  if (pending > 24) return Math.min(maxStep, 3)
  return Math.min(maxStep, minStep)
}

function sharedPrefixLength(a, b) {
  const left = String(a || '')
  const right = String(b || '')
  const n = Math.min(left.length, right.length)
  let i = 0
  while (i < n && left[i] === right[i]) i += 1
  return i
}

export function usePacedText(sourceText, isStreaming, options = {}) {
  const renderedText = ref('')
  const initialized = ref(false)
  let timerId = null

  const scheduleFrame = (fn) => {
    if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
      return window.requestAnimationFrame(fn)
    }
    return setTimeout(() => fn(Date.now()), 16)
  }

  const cancelFrame = (id) => {
    if (id == null) return
    if (typeof window !== 'undefined' && typeof window.cancelAnimationFrame === 'function') {
      window.cancelAnimationFrame(id)
      return
    }
    clearTimeout(id)
  }

  const normalizedText = computed(() => String(unref(sourceText) || ''))
  const streaming = computed(() => Boolean(unref(isStreaming)))

  const tick = () => {
    timerId = null
    const target = normalizedText.value

    if (!target) {
      renderedText.value = ''
      return
    }

    if (!target.startsWith(renderedText.value)) {
      const keep = sharedPrefixLength(target, renderedText.value)
      renderedText.value = target.slice(0, keep)
    }

    const pending = target.length - renderedText.value.length
    if (pending <= 0) return

    const step = calcStep(pending, streaming.value, options)
    const nextPos = renderedText.value.length + step
    renderedText.value = target.slice(0, nextPos)

    if (renderedText.value.length < target.length) {
      timerId = scheduleFrame(tick)
    }
  }

  const ensureTick = () => {
    if (timerId != null) return
    timerId = scheduleFrame(tick)
  }

  watch(
    [normalizedText, streaming],
    ([target, nowStreaming]) => {
      if (!initialized.value) {
        renderedText.value = target
        initialized.value = true
        return
      }

      if (!target) {
        renderedText.value = ''
        cancelFrame(timerId)
        timerId = null
        return
      }

      const pending = target.length - renderedText.value.length
      if (!nowStreaming && pending <= 0) {
        renderedText.value = target
        return
      }

      ensureTick()
    },
    { immediate: true }
  )

  onBeforeUnmount(() => {
    cancelFrame(timerId)
    timerId = null
  })

  return {
    renderedText
  }
}
