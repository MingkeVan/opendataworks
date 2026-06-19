// 运行时挂载冒烟（WorkflowDetail 有状态切片的安全网，同 DataStudio 套路）。
// shallowMount 执行完整 script setup / computed / onMounted（子组件 stub），
// 若后续抽 composable 破坏引用或响应式接线，挂载/渲染会抛错、本测试失败。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'

const { apiStub } = vi.hoisted(() => {
  const apiStub = () =>
    new Proxy(
      {},
      {
        get: () => () =>
          Promise.resolve({ data: null, records: [], total: 0, code: 0, success: true }),
      }
    )
  return { apiStub }
})

vi.mock('@/api/workflow', () => ({ workflowApi: apiStub() }))
vi.mock('@/api/task', () => ({ taskApi: apiStub() }))
vi.mock('@/api/settings', () => ({ settingsApi: apiStub() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '1' }, query: {}, path: '/workflows/1', name: 'workflow-detail' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
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
    ElMessageBox: { ...actual.ElMessageBox, confirm: vi.fn(() => Promise.resolve()), alert: vi.fn(() => Promise.resolve()) },
  }
})

import WorkflowDetail from '../WorkflowDetail.vue'

class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return []
  }
}

describe('WorkflowDetail runtime mount smoke', () => {
  beforeEach(() => {
    vi.stubGlobal('IntersectionObserver', NoopObserver)
    vi.stubGlobal('ResizeObserver', NoopObserver)
  })

  it('mounts and renders without throwing (setup + computed + onMounted)', () => {
    const wrapper = shallowMount(WorkflowDetail, {
      global: {
        stubs: {
          WorkflowTaskManager: true,
          WorkflowBackfillDialog: true,
          WorkflowVersionComparePanel: true,
          WorkflowPublishPreviewDialog: true,
          QuartzCronBuilder: true,
        },
        config: { warnHandler: () => {} },
        directives: { loading: {} },
      },
    })
    expect(wrapper.exists()).toBe(true)
    wrapper.unmount()
  })
})
