// 补数弹窗的守卫测试：未来日期既不可选，也不可提交。
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import dayjs from 'dayjs'

const { backfill, warning } = vi.hoisted(() => ({
  backfill: vi.fn(() => Promise.resolve('trigger-1')),
  warning: vi.fn(),
}))

vi.mock('@/api/workflow', () => ({ workflowApi: { backfill } }))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    ElMessage: Object.assign(vi.fn(), {
      success: vi.fn(),
      warning,
      error: vi.fn(),
      info: vi.fn(),
    }),
  }
})

import WorkflowBackfillDialog from '../WorkflowBackfillDialog.vue'
import { isAfterToday } from '../backfillForm'

const FORMAT = 'YYYY-MM-DD HH:mm:ss'
const onlineWorkflow = { id: 1, workflowName: 'wf_order', workflowCode: 5001, status: 'online' }

const mountDialog = () =>
  mount(WorkflowBackfillDialog, {
    props: { modelValue: true, workflow: onlineWorkflow },
    attachTo: document.body,
    global: {
      config: { warnHandler: () => {} },
    },
  })

describe('WorkflowBackfillDialog', () => {
  beforeEach(() => {
    backfill.mockClear()
    warning.mockClear()
  })

  it('wires the future-date guard onto the range picker', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const picker = wrapper.findComponent({ name: 'ElDatePicker' })
    expect(picker.exists()).toBe(true)
    expect(picker.props('disabledDate')).toBe(isAfterToday)
    wrapper.unmount()
  })

  it('refuses to submit a range reaching past today', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.dateRange = [
      dayjs().subtract(1, 'day').format(FORMAT),
      dayjs().add(1, 'day').format(FORMAT),
    ]

    await wrapper.vm.handleSubmit()

    expect(backfill).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('补数时间不能晚于今天')
    wrapper.unmount()
  })

  it('refuses to submit a list containing a future entry', async () => {
    const wrapper = mountDialog()
    const tomorrow = dayjs().add(1, 'day').format(FORMAT)
    wrapper.vm.form.mode = 'list'
    wrapper.vm.form.scheduleDateList = `${dayjs().subtract(2, 'day').format(FORMAT)},${tomorrow}`

    await wrapper.vm.handleSubmit()

    expect(backfill).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith(`补数时间不能晚于今天：${tomorrow}`)
    wrapper.unmount()
  })

  it('submits a past range', async () => {
    const wrapper = mountDialog()
    const start = dayjs().subtract(3, 'day').format(FORMAT)
    const end = dayjs().subtract(1, 'day').format(FORMAT)
    wrapper.vm.form.dateRange = [start, end]

    await wrapper.vm.handleSubmit()

    expect(backfill).toHaveBeenCalledTimes(1)
    expect(backfill.mock.calls[0][1]).toMatchObject({
      mode: 'range',
      startTime: start,
      endTime: end,
    })
    wrapper.unmount()
  })
})
