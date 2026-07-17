import { describe, it, expect } from 'vitest'
import { computed, defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { usePanelVerticalResize } from '../composables/usePanelVerticalResize'

function setup({ tabId = 't1', hasTable = true } = {}) {
  const activeTab = ref(tabId)
  const enabled = ref(hasTable)
  let api
  const wrapper = mount(
    defineComponent({
      setup() {
        api = usePanelVerticalResize({
          activeTabId: computed(() => activeTab.value),
          hasTableTab: computed(() => enabled.value),
        })
        return () => h('div')
      },
    })
  )
  return { api, activeTab, enabled, wrapper }
}

describe('usePanelVerticalResize (P2-2 F17b)', () => {
  it('clampTopHeight enforces min top and reserves bottom space', () => {
    const { api, wrapper } = setup()
    expect(api.clampTopHeight(100, 1000)).toBe(260) // below min -> min
    expect(api.clampTopHeight(500, 1000)).toBe(500) // within range
    expect(api.clampTopHeight(900, 1000)).toBe(1000 - 280 - 6) // clipped by bottom+resizer
    expect(api.clampTopHeight(9999, 0)).toBe(520) // no container -> legacy cap
    wrapper.unmount()
  })

  it('getCurrentTopHeight falls back to the default and remembers per-tab values', () => {
    const { api, wrapper } = setup()
    expect(api.getCurrentTopHeight('')).toBe(340)
    expect(api.getCurrentTopHeight('unknown')).toBe(340)
    api.panelTopHeights.value = { t1: 400 }
    expect(api.getCurrentTopHeight('t1')).toBe(400)
    wrapper.unmount()
  })

  it('panelShellStyle exposes --right-top only when a table tab is active', async () => {
    const { api, enabled, wrapper } = setup()
    api.panelTopHeights.value = { t1: 410 }
    expect(api.panelShellStyle.value).toEqual({ '--right-top': '410px' })
    enabled.value = false
    await wrapper.vm.$nextTick()
    expect(api.panelShellStyle.value).toEqual({})
    wrapper.unmount()
  })

  it('ensurePanelTopHeight seeds 42% of the container height once, clamped', async () => {
    const { api, wrapper } = setup()
    api.panelShellRef.value = { getBoundingClientRect: () => ({ height: 1000 }) }
    await api.ensurePanelTopHeight('t9')
    expect(api.panelTopHeights.value.t9).toBe(420)
    api.panelShellRef.value = { getBoundingClientRect: () => ({ height: 2000 }) }
    await api.ensurePanelTopHeight('t9') // already set -> unchanged
    expect(api.panelTopHeights.value.t9).toBe(420)
    wrapper.unmount()
  })

  it('startPanelResize tracks mouse movement and stop cleans listeners', () => {
    const { api, wrapper } = setup()
    api.panelShellRef.value = { getBoundingClientRect: () => ({ height: 1000 }) }
    api.panelTopHeights.value = { t1: 400 }
    api.startPanelResize({ preventDefault: () => {}, clientY: 100 })
    expect(api.isPanelResizing.value).toBe(true)
    window.dispatchEvent(new MouseEvent('mousemove', { clientY: 150 }))
    expect(api.panelTopHeights.value.t1).toBe(450)
    window.dispatchEvent(new MouseEvent('mouseup'))
    expect(api.isPanelResizing.value).toBe(false)
    window.dispatchEvent(new MouseEvent('mousemove', { clientY: 500 }))
    expect(api.panelTopHeights.value.t1).toBe(450) // listener removed
    wrapper.unmount()
  })
})
