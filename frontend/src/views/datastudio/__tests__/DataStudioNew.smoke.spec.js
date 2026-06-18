// 运行时挂载冒烟（P2-2 F4+ 的安全网）。
// shallowMount 会真正执行 DataStudioNew 的 script setup / composables / computed /
// onMounted 与模板渲染（子组件被 stub）。若 composable 抽取破坏了引用或响应式，
// 挂载/渲染会抛错，本测试即失败 —— 这是 build/lint 之外能在本环境拿到的运行时验证。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'

// 所有被访问的方法都返回一个稳定的空响应，避免 onMounted 内的接口调用抛错。
// 用 vi.hoisted 让工厂可在被提升的 vi.mock 中安全引用。
const { apiStub } = vi.hoisted(() => {
  const apiStub = () =>
    new Proxy(
      {},
      {
        get: () => () =>
          Promise.resolve({ data: [], records: [], total: 0, code: 0, success: true }),
      }
    )
  return { apiStub }
})

vi.mock('@/api/table', () => ({ tableApi: apiStub() }))
vi.mock('@/api/lineage', () => ({ lineageApi: apiStub() }))
vi.mock('@/api/doris', () => ({ dorisClusterApi: apiStub() }))
vi.mock('@/api/query', () => ({ dataQueryApi: apiStub() }))
vi.mock('@/api/domain', () => ({ businessDomainApi: apiStub(), dataDomainApi: apiStub() }))

vi.mock('@/utils/loadEcharts', () => ({ loadEcharts: vi.fn(() => Promise.resolve({})) }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: () => false, showDemoReadonlyMessage: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, params: {}, path: '/datastudio', name: 'datastudio', fullPath: '/datastudio' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), currentRoute: { value: { query: {} } } }),
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  const msg = Object.assign(vi.fn(), {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    closeAll: vi.fn(),
  })
  return {
    ...actual,
    ElMessage: msg,
    ElMessageBox: {
      ...actual.ElMessageBox,
      confirm: vi.fn(() => Promise.resolve()),
      alert: vi.fn(() => Promise.resolve()),
    },
  }
})

import DataStudioNew from '../DataStudioNew.vue'

// jsdom 不带 IntersectionObserver/ResizeObserver；组件 onMounted 会用到，stub 掉以免
// 异步路径产生无关的未处理错误污染冒烟信号
class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return []
  }
}

describe('DataStudioNew runtime mount smoke', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('IntersectionObserver', NoopObserver)
    vi.stubGlobal('ResizeObserver', NoopObserver)
  })

  it('mounts and renders without throwing (setup + composables + onMounted)', () => {
    const wrapper = shallowMount(DataStudioNew, {
      global: {
        stubs: {
          // 重组件统一 stub，专注验证本组件自身的 setup/模板不抛错
          PersistentTabs: true,
          TaskEditDrawer: true,
          DataStudioResultGrid: true,
          DataStudioRightPanel: true,
          SqlEditor: true,
          CreateTableDrawer: true,
        },
        // 让未注册的 Element Plus 元素/指令不致命
        config: { warnHandler: () => {} },
        directives: {
          loading: {},
        },
      },
    })

    expect(wrapper.exists()).toBe(true)
    // 根容器渲染成功
    expect(wrapper.find('.data-studio, .datastudio, [class]').exists()).toBe(true)
    wrapper.unmount()
  })
})
