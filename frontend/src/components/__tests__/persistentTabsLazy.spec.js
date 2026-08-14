import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PersistentTabs from '../PersistentTabs.vue'

// el-tab-pane 的 lazy 语义：shouldBeRender = !lazy || loaded || active，且 loaded 一旦
// 置位就不再回落。所以 lazy 只推迟"首次渲染"，激活过的面板依然常驻，
// PersistentTabs 的持久化语义不变。
const paneStub = {
  name: 'ElTabPane',
  props: ['name', 'lazy'],
  template: '<div class="pane-stub" :data-name="name" :data-lazy="String(lazy)"><slot /></div>'
}

const stubs = {
  'el-tabs': { template: '<div><slot /></div>' },
  'el-tab-pane': paneStub,
  teleport: true
}

const tabs = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]

describe('PersistentTabs lazy', () => {
  it('does not defer pane rendering by default', () => {
    const wrapper = mount(PersistentTabs, { props: { tabs }, global: { stubs } })

    wrapper.findAll('.pane-stub').forEach((pane) => {
      expect(pane.attributes('data-lazy')).toBe('false')
    })
  })

  it('forwards lazy to every pane so only the active one mounts up front', () => {
    const wrapper = mount(PersistentTabs, { props: { tabs, lazy: true }, global: { stubs } })

    const panes = wrapper.findAll('.pane-stub')
    expect(panes).toHaveLength(3)
    panes.forEach((pane) => {
      expect(pane.attributes('data-lazy')).toBe('true')
    })
  })

  it('is enabled on the Data Studio workspace tabs', () => {
    // 恢复十几个查询标签页时，非 lazy 会一次性挂载每个面板的 CodeMirror 实例，
    // 切回 Data Studio 会卡住主线程几秒。
    const source = readFileSync(
      resolve(process.cwd(), 'src/views/datastudio/DataStudioNew.vue'),
      'utf8'
    )
    const usage = source.match(/<PersistentTabs[\s\S]*?>/)

    expect(usage).toBeTruthy()
    expect(usage[0]).toMatch(/\blazy\b/)
  })
})
