import { ref, computed, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'

// WorkflowDetail 版本对比交互（W6，同 composable 套路）。
// 从 WorkflowDetail.vue 逐字抽出，行为保持不变：历史版本多选（≤2）、加载差异、
// 比较所选、左右步进。版本变更（回滚/删除）与发布记录对话框仍留在组件。
// workflow/versionList/changeMode 注入；selectedHistoryVersionIds 返回供 isVersionSelectable 使用。
export function useVersionCompare({ workflow, versionList, changeMode }) {
  const compareLoading = ref(false)
  const versionCompareResult = ref(null)
  const leftVersionId = ref(null)
  const rightVersionId = ref(null)
  const versionHistoryTableRef = ref(null)
  const selectedHistoryVersions = ref([])

  const selectedHistoryVersionIds = computed(() => {
    return selectedHistoryVersions.value
      .map((item) => Number(item?.id))
      .filter((id) => Number.isFinite(id))
  })
  const canCompareSelected = computed(() => selectedHistoryVersionIds.value.length === 2)

  const clearVersionHistorySelection = () => {
    selectedHistoryVersions.value = []
    nextTick(() => {
      versionHistoryTableRef.value?.clearSelection()
    })
  }

  const handleVersionSelectionChange = (rows) => {
    if (!Array.isArray(rows)) {
      selectedHistoryVersions.value = []
      return
    }
    if (rows.length <= 2) {
      selectedHistoryVersions.value = rows
      return
    }
    ElMessage.warning('最多选择两个版本进行比较')
    const keepRows = rows.slice(0, 2)
    selectedHistoryVersions.value = keepRows
    nextTick(() => {
      versionHistoryTableRef.value?.clearSelection()
      keepRows.forEach((item) => {
        versionHistoryTableRef.value?.toggleRowSelection(item, true)
      })
    })
  }

  const loadVersionCompare = async (leftId, rightId) => {
    const wf = workflow.value?.workflow
    const normalizedRightId = Number(rightId)
    if (!wf?.id || !Number.isFinite(normalizedRightId)) {
      return
    }
    compareLoading.value = true
    try {
      const result = await workflowApi.compareVersions(wf.id, {
        leftVersionId: leftId ?? null,
        rightVersionId: normalizedRightId,
        operator: 'portal-ui'
      })
      versionCompareResult.value = result
      leftVersionId.value = result.leftVersionId ?? null
      rightVersionId.value = result.rightVersionId ?? normalizedRightId
      changeMode.value = 'compare'
    } catch (error) {
      console.error('加载版本差异失败', error)
      ElMessage.error(error.message || '加载版本差异失败')
    } finally {
      compareLoading.value = false
    }
  }

  const compareSelectedVersions = async () => {
    if (!canCompareSelected.value) {
      ElMessage.warning('请选择两个版本进行比较')
      return
    }
    if (selectedHistoryVersions.value.some((item) => !item?.isV3)) {
      ElMessage.warning('仅支持 V3，请先保存生成 V3 基线')
      return
    }
    const sorted = [...selectedHistoryVersions.value].sort((left, right) => {
      const leftNo = Number(left?.versionNo || 0)
      const rightNo = Number(right?.versionNo || 0)
      if (leftNo !== rightNo) {
        return leftNo - rightNo
      }
      return Number(left?.id || 0) - Number(right?.id || 0)
    })
    const leftVersion = sorted[0]
    const rightVersion = sorted[1]
    await loadVersionCompare(leftVersion?.id || null, rightVersion?.id || null)
  }

  const stepVersionCompare = async (direction) => {
    if (!rightVersionId.value || !versionList.value.length) {
      return
    }
    const currentRightId = Number(rightVersionId.value)
    const index = versionList.value.findIndex((item) => Number(item.id) === currentRightId)
    if (index < 0) {
      return
    }

    if (direction === 'left') {
      const nextRightIndex = index - 1
      if (nextRightIndex < 0) {
        return
      }
      const right = versionList.value[nextRightIndex]
      const left = nextRightIndex > 0 ? versionList.value[nextRightIndex - 1]?.id : null
      await loadVersionCompare(left, right.id)
      return
    }

    const nextRightIndex = index + 1
    if (nextRightIndex >= versionList.value.length) {
      return
    }
    const right = versionList.value[nextRightIndex]
    const left = versionList.value[index]?.id || null
    await loadVersionCompare(left, right.id)
  }

  return {
    compareLoading,
    versionCompareResult,
    leftVersionId,
    rightVersionId,
    versionHistoryTableRef,
    selectedHistoryVersions,
    selectedHistoryVersionIds,
    canCompareSelected,
    clearVersionHistorySelection,
    handleVersionSelectionChange,
    loadVersionCompare,
    compareSelectedVersions,
    stepVersionCompare,
  }
}
