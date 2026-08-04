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

const { getWorkflowExecutionInstances } = vi.hoisted(() => ({
  getWorkflowExecutionInstances: vi.fn(() => Promise.resolve({ records: [], total: 0 })),
}))

vi.mock('@/api/workflow', () => ({ workflowApi: apiStub() }))
vi.mock('@/api/task', () => ({ taskApi: apiStub() }))
vi.mock('@/api/settings', () => ({ settingsApi: apiStub() }))
vi.mock('@/api/execution', () => ({
  getWorkflowExecutionInstances,
  getWorkflowExecutionTasks: vi.fn(() => Promise.resolve([])),
}))

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

const mountDetail = () =>
  shallowMount(WorkflowDetail, {
    global: {
      stubs: {
        WorkflowTaskManager: true,
        WorkflowBackfillDialog: true,
        WorkflowVersionComparePanel: true,
        WorkflowPublishPreviewDialog: true,
        WorkflowInstanceTable: true,
        QuartzCronBuilder: true,
      },
      config: { warnHandler: () => {} },
      directives: { loading: {} },
    },
  })

describe('WorkflowDetail runtime mount smoke', () => {
  beforeEach(() => {
    vi.stubGlobal('IntersectionObserver', NoopObserver)
    vi.stubGlobal('ResizeObserver', NoopObserver)
    getWorkflowExecutionInstances.mockClear()
  })

  it('mounts and renders without throwing (setup + computed + onMounted)', () => {
    const wrapper = mountDetail()
    expect(wrapper.exists()).toBe(true)
    wrapper.unmount()
  })

  it('does not fetch execution history until the tab is opened', () => {
    const wrapper = mountDetail()
    expect(getWorkflowExecutionInstances).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  // 作用域断言：执行历史只看当前工作流，请求必须带 workflowId。
  // 若后续误改成不传 workflowId，就会拉到全量实例，这里应当失败。
  it('scopes execution history to the current workflow', async () => {
    const wrapper = mountDetail()
    wrapper.vm.workflow = { workflow: { id: 42 } }
    wrapper.vm.activeTab = 'executions'
    await wrapper.vm.$nextTick()

    expect(getWorkflowExecutionInstances).toHaveBeenCalledTimes(1)
    expect(getWorkflowExecutionInstances.mock.calls[0][0]).toMatchObject({ workflowId: 42 })
    wrapper.unmount()
  })
})
