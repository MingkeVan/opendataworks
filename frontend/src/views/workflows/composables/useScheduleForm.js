import { ref, reactive, computed, watch, nextTick } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { taskApi } from '@/api/task'
import { workflowApi } from '@/api/workflow'

// WorkflowDetail 调度表单/选项/预览/上下线（W5，同 composable 套路）。
// 从 WorkflowDetail.vue 逐字抽出，行为保持不变。调度态由本 composable 拥有并返回；
// workflow/activeTab 注入，buildDolphinConfigParams/loadWorkflowDetail/getErrorMessage 惰性前向引用。
export function useScheduleForm({
  workflow,
  activeTab,
  buildDolphinConfigParams,
  loadWorkflowDetail,
  getErrorMessage,
}) {
  const scheduleFormRef = ref(null)
  const savingSchedule = ref(false)
  const scheduleSwitchLoading = ref(false)
  const scheduleEnabled = ref(false)
  const scheduleSwitchMuted = ref(false)
  const scheduleOptionsLoading = ref(false)
  const scheduleOptionsLoaded = ref(false)
  const schedulePreviewLoading = ref(false)
  const schedulePreviewList = ref([])
  const cronBuilderVisible = ref(false)
  const workerGroupOptions = ref([])
  const tenantOptions = ref([])
  const alertGroupOptions = ref([])
  const environmentOptions = ref([])
  const isScheduleOnline = computed(() => {
    return (workflow.value?.workflow?.scheduleState || '').toUpperCase() === 'ONLINE'
  })
  const timezoneOptions = computed(() => {
    try {
      if (typeof Intl !== 'undefined' && typeof Intl.supportedValuesOf === 'function') {
        return Intl.supportedValuesOf('timeZone')
      }
    } catch {
      // ignore
    }
    return [
      'Asia/Shanghai',
      'UTC',
      'Asia/Hong_Kong',
      'Asia/Singapore',
      'Asia/Tokyo',
      'Europe/London',
      'America/New_York',
      'America/Los_Angeles'
    ]
  })
  const defaultTimezone = (() => {
    try {
      const tz = Intl?.DateTimeFormat?.().resolvedOptions?.().timeZone
      return tz || 'Asia/Shanghai'
    } catch {
      return 'Asia/Shanghai'
    }
  })()
  const defaultStartEndTime = (() => {
    const start = dayjs().startOf('day')
    return [
      start.format('YYYY-MM-DD HH:mm:ss'),
      start.add(100, 'year').format('YYYY-MM-DD HH:mm:ss')
    ]
  })()
  const scheduleForm = reactive({
    scheduleStartEndTime: defaultStartEndTime,
    scheduleCron: '0 0 * * * ? *',
    scheduleTimezone: defaultTimezone,
    scheduleProcessInstancePriority: 'MEDIUM',
    scheduleWorkerGroup: 'default',
    scheduleTenantCode: 'default',
    scheduleEnvironmentCode: -1,
    scheduleFailureStrategy: 'CONTINUE',
    scheduleWarningType: 'NONE',
    scheduleWarningGroupId: null,
    scheduleAutoOnline: false
  })
  const environmentFilteredOptions = computed(() => {
    const selectedWorkerGroup = scheduleForm.scheduleWorkerGroup
    if (!selectedWorkerGroup) {
      return []
    }
    return (environmentOptions.value || []).filter((env) => {
      const groups = env?.workerGroups || []
      return Array.isArray(groups) && groups.includes(selectedWorkerGroup)
    })
  })
  const scheduleRules = {
    scheduleStartEndTime: [
      { required: true, message: '请选择起止时间', trigger: 'change' },
      {
        validator: (_, value, callback) => {
          const start = Array.isArray(value) ? value?.[0] : null
          const end = Array.isArray(value) ? value?.[1] : null
          if (!start || !end) {
            callback(new Error('请选择起止时间'))
            return
          }
          const startTs = dayjs(start).valueOf()
          const endTs = dayjs(end).valueOf()
          if (Number.isFinite(startTs) && Number.isFinite(endTs) && endTs < startTs) {
            callback(new Error('结束时间需晚于开始时间'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    scheduleCron: [
      { required: true, message: '请输入 Cron 表达式', trigger: 'blur' },
      {
        validator: (_, value, callback) => {
          const parts = String(value || '')
            .trim()
            .split(/\s+/)
            .filter(Boolean)
          if (parts.length !== 7) {
            callback(new Error('Cron 需为 Quartz 7 段：秒 分 时 日 月 周 年'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ],
    scheduleTimezone: [{ required: true, message: '请输入时区', trigger: 'blur' }],
    scheduleWarningGroupId: [
      {
        validator: (_, value, callback) => {
          if (scheduleForm.scheduleWarningType === 'NONE') {
            callback()
            return
          }
          if (!value || Number(value) <= 0) {
            callback(new Error('请选择告警组'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ]
  }

  const loadScheduleOptions = async (force = false) => {
    if (scheduleOptionsLoaded.value && !force) {
      return
    }
    scheduleOptionsLoading.value = true
    try {
      const params = buildDolphinConfigParams()
      const [workerGroups, tenants, alertGroups, environments] = await Promise.all([
        taskApi.fetchWorkerGroups(params).catch(() => []),
        taskApi.fetchTenants(params).catch(() => []),
        taskApi.fetchAlertGroups(params).catch(() => []),
        taskApi.fetchEnvironments(params).catch(() => [])
      ])
      workerGroupOptions.value = workerGroups || []
      tenantOptions.value = tenants || []
      alertGroupOptions.value = alertGroups || []
      environmentOptions.value = environments || []
      scheduleOptionsLoaded.value = true
    } finally {
      scheduleOptionsLoading.value = false
    }
  }

  const handleWorkerGroupChange = () => {
    scheduleForm.scheduleEnvironmentCode = -1
  }

  const previewScheduleTimes = async () => {
    if (isScheduleOnline.value) {
      return
    }
    const [startTime, endTime] = Array.isArray(scheduleForm.scheduleStartEndTime)
      ? scheduleForm.scheduleStartEndTime
      : []
    if (!startTime || !endTime) {
      ElMessage.warning('请选择起止时间')
      return
    }
    if (!String(scheduleForm.scheduleCron || '').trim()) {
      ElMessage.warning('请输入 Cron 表达式')
      return
    }
    if (!String(scheduleForm.scheduleTimezone || '').trim()) {
      ElMessage.warning('请输入时区')
      return
    }

    schedulePreviewLoading.value = true
    try {
      const schedule = JSON.stringify({
        startTime,
        endTime,
        crontab: scheduleForm.scheduleCron,
        timezoneId: scheduleForm.scheduleTimezone
      })
      const res = await taskApi.previewSchedule({ schedule }, buildDolphinConfigParams())
      schedulePreviewList.value = Array.isArray(res) ? res : []
    } catch (error) {
      console.error('预览调度时间失败', error)
    } finally {
      schedulePreviewLoading.value = false
    }
  }

  const syncScheduleForm = () => {
    const wf = workflow.value?.workflow
    if (!wf) return

    scheduleForm.scheduleCron = wf.scheduleCron || '0 0 * * * ? *'
    scheduleForm.scheduleTimezone = wf.scheduleTimezone || defaultTimezone
    const startTime = wf.scheduleStartTime
      ? dayjs(wf.scheduleStartTime).format('YYYY-MM-DD HH:mm:ss')
      : null
    const endTime = wf.scheduleEndTime
      ? dayjs(wf.scheduleEndTime).format('YYYY-MM-DD HH:mm:ss')
      : null
    scheduleForm.scheduleStartEndTime = startTime && endTime ? [startTime, endTime] : defaultStartEndTime
    scheduleForm.scheduleFailureStrategy = wf.scheduleFailureStrategy || 'CONTINUE'
    const warningType = (wf.scheduleWarningType || 'NONE').toUpperCase()
    scheduleForm.scheduleWarningType = warningType === 'SUCCESS_FAILURE' ? 'ALL' : warningType
    scheduleForm.scheduleWarningGroupId =
      wf.scheduleWarningGroupId === null || wf.scheduleWarningGroupId === undefined
        ? 0
        : wf.scheduleWarningGroupId
    scheduleForm.scheduleProcessInstancePriority = wf.scheduleProcessInstancePriority || 'MEDIUM'
    scheduleForm.scheduleWorkerGroup = wf.scheduleWorkerGroup || 'default'
    scheduleForm.scheduleTenantCode = wf.scheduleTenantCode || 'default'
    scheduleForm.scheduleEnvironmentCode =
      wf.scheduleEnvironmentCode === null || wf.scheduleEnvironmentCode === undefined
        ? -1
        : wf.scheduleEnvironmentCode
    scheduleForm.scheduleAutoOnline = Boolean(wf.scheduleAutoOnline)
    schedulePreviewList.value = []

    scheduleSwitchMuted.value = true
    scheduleEnabled.value = (wf.scheduleState || '').toUpperCase() === 'ONLINE'
    nextTick(() => {
      scheduleSwitchMuted.value = false
    })
    scheduleFormRef.value?.clearValidate()
  }

  const saveScheduleConfig = async () => {
    const wf = workflow.value?.workflow
    if (!wf?.id) return

    try {
      await scheduleFormRef.value?.validate()
    } catch {
      return
    }

    savingSchedule.value = true
    try {
      const [startTime, endTime] = Array.isArray(scheduleForm.scheduleStartEndTime)
        ? scheduleForm.scheduleStartEndTime
        : []
      await workflowApi.updateSchedule(wf.id, {
        scheduleCron: scheduleForm.scheduleCron,
        scheduleTimezone: scheduleForm.scheduleTimezone,
        scheduleStartTime: startTime,
        scheduleEndTime: endTime,
        scheduleFailureStrategy: scheduleForm.scheduleFailureStrategy,
        scheduleWarningType: scheduleForm.scheduleWarningType,
        scheduleWarningGroupId:
          scheduleForm.scheduleWarningType === 'NONE' ? 0 : scheduleForm.scheduleWarningGroupId,
        scheduleProcessInstancePriority: scheduleForm.scheduleProcessInstancePriority,
        scheduleWorkerGroup: scheduleForm.scheduleWorkerGroup || null,
        scheduleTenantCode: scheduleForm.scheduleTenantCode || null,
        scheduleEnvironmentCode:
          scheduleForm.scheduleEnvironmentCode === null || scheduleForm.scheduleEnvironmentCode === undefined
            ? -1
            : scheduleForm.scheduleEnvironmentCode,
        scheduleAutoOnline: scheduleForm.scheduleAutoOnline
      })
      ElMessage.success('调度配置已保存')
      loadWorkflowDetail()
    } catch (error) {
      console.error('保存调度配置失败', error)
      ElMessage.error(getErrorMessage(error))
    } finally {
      savingSchedule.value = false
    }
  }

  const handleToggleSchedule = async (val) => {
    if (scheduleSwitchMuted.value) {
      return
    }
    const wf = workflow.value?.workflow
    if (!wf?.id) return
    if (!wf.dolphinScheduleId) {
      ElMessage.warning('请先保存调度配置')
      scheduleEnabled.value = false
      return
    }
    if (val === true && wf.status !== 'online') {
      ElMessage.warning('工作流未上线，无法上线调度')
      scheduleEnabled.value = false
      return
    }

    scheduleSwitchLoading.value = true
    try {
      if (val) {
        await workflowApi.onlineSchedule(wf.id)
        ElMessage.success('调度已上线')
      } else {
        await workflowApi.offlineSchedule(wf.id)
        ElMessage.success('调度已下线')
      }
      loadWorkflowDetail()
    } catch (error) {
      console.error('切换调度状态失败', error)
      ElMessage.error(getErrorMessage(error))
      scheduleEnabled.value = !val
    } finally {
      scheduleSwitchLoading.value = false
    }
  }

  watch(activeTab, async (tab) => {
    if (tab === 'schedule') {
      await loadScheduleOptions()
    }
  })

  return {
    scheduleFormRef,
    savingSchedule,
    scheduleSwitchLoading,
    scheduleEnabled,
    scheduleSwitchMuted,
    scheduleOptionsLoading,
    scheduleOptionsLoaded,
    schedulePreviewLoading,
    schedulePreviewList,
    cronBuilderVisible,
    workerGroupOptions,
    tenantOptions,
    alertGroupOptions,
    environmentOptions,
    isScheduleOnline,
    timezoneOptions,
    scheduleForm,
    environmentFilteredOptions,
    scheduleRules,
    loadScheduleOptions,
    handleWorkerGroupChange,
    previewScheduleTimes,
    syncScheduleForm,
    saveScheduleConfig,
    handleToggleSchedule,
  }
}
