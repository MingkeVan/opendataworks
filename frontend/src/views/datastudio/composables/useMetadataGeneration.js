import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import { nl2sqlApi } from '@/api/nl2sql'
import { tableApi } from '@/api/table'
import { buildFieldPayload } from '../fieldEdit'
import { buildMetadataPrompt, formatFieldComment, parseMetadataResponse } from '../metadataGeneration'

// 智能元数据：复用智能问数的发送消息端点发起后台任务，解析助手消息中的格式化内容，
// 经弹窗复核后采纳，直接走既有表/字段写回接口（不新增后端端点）。
//
// 建议结果为内存态：useTabPersistence 只持久化 tab 骨架，刷新页面后需重新生成。

const TERMINAL_STATUSES = new Set(['finished', 'success', 'completed', 'error', 'failed', 'suspended'])
const SUCCESS_STATUSES = new Set(['finished', 'success', 'completed'])
const POLL_INTERVAL_MS = 2000
const MAX_WAIT_MS = 300000
const MAX_TASK_SQL = 5

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const messageText = (message) => {
  if (!message) return ''
  if (Array.isArray(message.blocks)) {
    const parts = message.blocks
      .filter((block) => block && block.type === 'main_text' && String(block.text || '').trim())
      .map((block) => String(block.text).trim())
    if (parts.length) return parts.join('\n\n')
  }
  return String(message.content || '').trim()
}

export function useMetadataGeneration({
  clusterId,
  tabStates,
  taskApi,
  loadDdl,
  warnPlatformMetadataMissing
}) {
  const metadataGenerating = ref(false)
  const metadataAdopting = ref(false)
  const metadataDialogVisible = ref(false)
  const metadataResult = ref(null)

  const collectRelatedTasks = async (state) => {
    const picked = [
      ...(state.tasks?.writeTasks || []),
      ...(state.tasks?.readTasks || [])
    ].slice(0, MAX_TASK_SQL)

    const collected = []
    for (const item of picked) {
      try {
        const detail = await taskApi.getById(item.id)
        const taskSql = detail?.taskSql || detail?.task_sql || ''
        if (String(taskSql).trim()) {
          collected.push({
            taskName: item.taskName || detail?.taskName || '',
            engine: item.engine || detail?.engine || '',
            relationType: item.relationType || '',
            taskSql
          })
        }
      } catch (error) {
        console.warn('获取关联任务代码失败', item?.id, error)
      }
    }
    return collected
  }

  const waitForResult = async (taskId) => {
    const deadline = Date.now() + MAX_WAIT_MS
    while (Date.now() < deadline) {
      await sleep(POLL_INTERVAL_MS)
      let task
      try {
        task = await nl2sqlApi.getTask(taskId)
      } catch {
        // 轮询期间的瞬时错误不终止等待，交给超时兜底
        continue
      }
      const status = String(task?.task_status || '').toLowerCase()
      if (TERMINAL_STATUSES.has(status)) {
        if (!SUCCESS_STATUSES.has(status)) {
          throw new Error(`生成任务未成功（状态：${status || '未知'}）`)
        }
        return
      }
    }
    throw new Error('生成超时，请稍后重试')
  }

  const buildResult = (tabId, state, parsed) => {
    const currentByName = new Map((state.fields || []).map((field) => [field.fieldName, field]))
    const fields = parsed.fields
      .filter((item) => currentByName.has(item.fieldName))
      .map((item) => {
        const current = currentByName.get(item.fieldName)
        const suggestedComment = formatFieldComment(item.comment, item.enumValues)
        const currentComment = current?.fieldComment || ''
        return {
          fieldId: current?.id,
          fieldName: item.fieldName,
          fieldType: current?.fieldType || '',
          currentComment,
          suggestedComment,
          hasRecommendation: !!suggestedComment && suggestedComment !== currentComment
        }
      })

    // 供主字段表的「智能描述」内联列消费
    state.metaSuggestion = {
      fields: fields.map((field) => ({
        fieldName: field.fieldName,
        suggestedComment: field.suggestedComment
      }))
    }

    const tableComment = state.table.tableComment || ''
    return {
      tabId,
      tableName: state.table.tableName || '',
      table: {
        currentComment: tableComment,
        suggestedComment: parsed.tableComment,
        hasRecommendation: !!parsed.tableComment && parsed.tableComment !== tableComment
      },
      fields
    }
  }

  // force=true 时忽略已有结果重新生成；否则已有结果直接打开弹窗
  const generateMetadata = async (tabId, { force = false } = {}) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('智能元数据')
      return
    }
    const state = tabStates[tabId]
    if (!state?.table?.id) return
    if (warnPlatformMetadataMissing(state.table)) return
    if (!force && metadataResult.value?.tabId === tabId) {
      metadataDialogVisible.value = true
      return
    }
    if (metadataGenerating.value) return

    metadataGenerating.value = true
    try {
      if (!state.ddl && typeof loadDdl === 'function') {
        try {
          await loadDdl(tabId)
        } catch {
          // DDL 缺失不阻断生成，其余上下文仍可用
        }
      }

      const relatedTasks = await collectRelatedTasks(state)
      const prompt = buildMetadataPrompt({
        dbName: state.table.dbName,
        tableName: state.table.tableName,
        tableType: state.table.tableType,
        layer: state.table.layer,
        tableComment: state.table.tableComment,
        ddl: state.ddl,
        fields: (state.fields || []).map((field) => ({
          fieldName: field.fieldName,
          fieldType: field.fieldType,
          fieldComment: field.fieldComment
        })),
        upstreamTables: state.lineage?.upstreamTables || [],
        downstreamTables: state.lineage?.downstreamTables || [],
        relatedTasks
      })

      const topic = await nl2sqlApi.createTopic({
        title: `元数据生成: ${state.table.dbName || ''}.${state.table.tableName || ''}`
      })
      const topicId = topic?.topic_id || topic?.id
      if (!topicId) throw new Error('创建会话失败')

      const submitted = await nl2sqlApi.deliverMessage({
        topic_id: topicId,
        content: prompt,
        execution_mode: 'background'
      })
      const taskId = submitted?.task_id || submitted?.taskId
      if (!taskId) throw new Error('发起生成任务失败')

      await waitForResult(taskId)
      const parsed = parseMetadataResponse(messageText(await nl2sqlApi.getTaskMessage(taskId)))

      metadataResult.value = buildResult(tabId, state, parsed)
      metadataDialogVisible.value = true
      if (
        !metadataResult.value.table.hasRecommendation &&
        !metadataResult.value.fields.some((field) => field.hasRecommendation)
      ) {
        ElMessage.warning('AI 未生成可采纳的元数据建议')
      }
    } catch (error) {
      ElMessage.error(error?.message || '智能生成元数据失败')
    } finally {
      metadataGenerating.value = false
    }
  }

  // payload: { table: { text } | null, fields: [{ fieldName, text }] }
  const adoptMetadata = async (tabId, payload = {}) => {
    const state = tabStates[tabId]
    if (!state?.table?.id) return
    const { table = null, fields = [] } = payload

    metadataAdopting.value = true
    try {
      if (table && String(table.text || '').trim()) {
        await tableApi.updateComment(state.table.id, table.text, clusterId.value || null)
        state.table.tableComment = table.text
        if (state.metaForm) state.metaForm.tableComment = table.text
        if (state.metaOriginal) state.metaOriginal.tableComment = table.text
      }

      for (const item of fields) {
        const original = (state.fields || []).find((field) => field.fieldName === item.fieldName)
        if (!original?.id) continue
        await tableApi.updateField(
          state.table.id,
          original.id,
          buildFieldPayload({ ...original, fieldComment: item.text }),
          clusterId.value || null
        )
      }

      // 刷新字段：内联列的「已采纳」态与元数据完善度随之更新
      const list = await tableApi.getFields(state.table.id)
      if (Array.isArray(list)) state.fields = list

      ElMessage.success('已采纳并保存')
      metadataDialogVisible.value = false
    } catch (error) {
      ElMessage.error(error?.message || '采纳失败')
    } finally {
      metadataAdopting.value = false
    }
  }

  return {
    metadataGenerating,
    metadataAdopting,
    metadataDialogVisible,
    metadataResult,
    generateMetadata,
    adoptMetadata
  }
}
