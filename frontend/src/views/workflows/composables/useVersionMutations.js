import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workflowApi } from '@/api/workflow'

// WorkflowDetail 版本变更与发布记录（W7，同 composable 套路）。
// 从 WorkflowDetail.vue 逐字抽出，行为保持不变：回滚到指定版本、删除版本、查看某版本发布记录。
// workflow/versionById/publishRecordsByVersionId 注入；可用性判定与加载/返回回调（W3 wrapper、
// loadWorkflowDetail、backToPublishRecords）为前向引用，惰性传入。
export function useVersionMutations({
  workflow,
  versionById,
  publishRecordsByVersionId,
  getRollbackDisabledReason,
  getVersionDeleteDisabledReason,
  loadWorkflowDetail,
  backToPublishRecords,
}) {
  const rollbackLoadingVersionId = ref(null)
  const deleteLoadingVersionId = ref(null)
  const versionPublishRecordDialogVisible = ref(false)
  const activeVersionPublishRecords = ref([])
  const activeVersionForRecords = ref(null)

  const versionPublishRecordDialogTitle = computed(() => {
    const versionNo = activeVersionForRecords.value?.versionNo
    return Number.isFinite(Number(versionNo))
      ? `版本 ${versionNo} 发布记录`
      : '发布记录'
  })

  const rollbackToVersion = async (versionId) => {
    const wf = workflow.value?.workflow
    const normalizedVersionId = Number(versionId)
    if (!wf?.id || !Number.isFinite(normalizedVersionId)) {
      return
    }
    const version = versionById.value[normalizedVersionId]
    const disabledReason = getRollbackDisabledReason(version)
    if (disabledReason) {
      ElMessage.warning(disabledReason)
      return
    }
    const label = version ? `版本 ${version.versionNo}` : `#${versionId}`
    try {
      await ElMessageBox.confirm(
        `确认恢复到${label}吗？恢复后会生成一个新版本。`,
        '确认恢复',
        {
          type: 'warning',
          confirmButtonText: '确认恢复',
          cancelButtonText: '取消'
        }
      )
    } catch {
      return
    }

    rollbackLoadingVersionId.value = normalizedVersionId
    try {
      const response = await workflowApi.rollbackVersion(wf.id, normalizedVersionId, {
        operator: 'portal-ui'
      })
      ElMessage.success(`恢复成功，已生成版本 v${response.newVersionNo}`)
      backToPublishRecords()
      await loadWorkflowDetail()
    } catch (error) {
      console.error('恢复版本失败', error)
      ElMessage.error(error.message || '恢复版本失败')
    } finally {
      rollbackLoadingVersionId.value = null
    }
  }

  const deleteVersion = async (row) => {
    const wf = workflow.value?.workflow
    const versionId = Number(row?.id)
    if (!wf?.id || !Number.isFinite(versionId)) {
      return
    }
    const disabledReason = getVersionDeleteDisabledReason(row)
    if (disabledReason) {
      ElMessage.warning(disabledReason)
      return
    }
    const label = row?.versionNo ? `版本 ${row.versionNo}` : `#${versionId}`
    try {
      await ElMessageBox.confirm(
        `确认删除${label}吗？删除后不可恢复。`,
        '确认删除版本',
        {
          type: 'warning',
          confirmButtonText: '确认删除',
          cancelButtonText: '取消'
        }
      )
    } catch {
      return
    }

    deleteLoadingVersionId.value = versionId
    try {
      await workflowApi.deleteVersion(wf.id, versionId)
      ElMessage.success('版本删除成功')
      await loadWorkflowDetail()
    } catch (error) {
      console.error('删除版本失败', error)
      ElMessage.error(error.message || '删除版本失败')
    } finally {
      deleteLoadingVersionId.value = null
    }
  }

  const openVersionPublishRecords = (row) => {
    activeVersionForRecords.value = row || null
    const versionId = Number(row?.id)
    if (Number.isFinite(versionId)) {
      activeVersionPublishRecords.value = publishRecordsByVersionId.value[versionId] || []
    } else {
      activeVersionPublishRecords.value = []
    }
    versionPublishRecordDialogVisible.value = true
  }

  return {
    rollbackLoadingVersionId,
    deleteLoadingVersionId,
    versionPublishRecordDialogVisible,
    activeVersionPublishRecords,
    activeVersionForRecords,
    versionPublishRecordDialogTitle,
    rollbackToVersion,
    deleteVersion,
    openVersionPublishRecords,
  }
}
