<template>
  <el-dialog
    v-model="visible"
    title="从 Dolphin 同步工作流"
    width="1100px"
    :close-on-click-modal="false"
  >
    <div class="sync-dialog" v-loading="loading">
      <div class="toolbar">
        <div class="filters" v-if="!isPresetMode">
          <el-input
            v-model="query.keyword"
            placeholder="搜索工作流名称"
            clearable
            @keyup.enter="handleSearch"
          />
          <el-input
            v-model="query.projectCode"
            placeholder="项目编码（可选）"
            clearable
            style="width: 180px"
          />
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
        <div class="actions">
          <el-button :disabled="!selectedWorkflow" :loading="previewLoading" @click="handlePreview">
            预检
          </el-button>
          <el-button
            type="primary"
            :disabled="!canSubmitSync"
            :loading="syncing"
            @click="handleSync"
          >
            同步
          </el-button>
        </div>
      </div>

      <el-table
        :data="runtimeWorkflows"
        row-key="workflowCode"
        highlight-current-row
        @current-change="handleCurrentChange"
      >
        <el-table-column label="工作流" min-width="240">
          <template #default="{ row }">
            <div class="name-line">
              <span>{{ row.workflowName || '-' }}</span>
              <el-tag size="small" :type="row.releaseState === 'ONLINE' ? 'success' : 'info'">
                {{ row.releaseState || '-' }}
              </el-tag>
            </div>
            <div class="meta-line">
              <span>project: {{ row.projectCode || '-' }}</span>
              <span>code: {{ row.workflowCode || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="同步状态" width="200">
          <template #default="{ row }">
            <el-tag v-if="row.synced" type="success" size="small">已同步</el-tag>
            <el-tag v-else type="info" size="small">未同步</el-tag>
            <span v-if="row.localWorkflowId" class="local-id">#{{ row.localWorkflowId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近同步时间" width="200">
          <template #default="{ row }">
            {{ formatDateTime(row.lastRuntimeSyncAt) }}
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="!isPresetMode"
        class="pagination"
        v-model:current-page="pagination.pageNum"
        v-model:page-size="pagination.pageSize"
        :total="pagination.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @size-change="handlePageSizeChange"
        @current-change="loadRuntimeWorkflows"
      />

      <div class="result" v-if="previewResult">
        <el-alert
          :type="previewResult.canSync ? 'success' : 'error'"
          :closable="false"
          :title="previewResult.canSync ? '预检通过，可执行同步' : '预检未通过，请先处理失败项'"
        />

        <div class="section" v-if="previewResult.renamePlan?.length">
          <div class="section-title">改名计划</div>
          <el-table :data="previewResult.renamePlan" size="small" border>
            <el-table-column prop="taskCode" label="任务编码" width="120" />
            <el-table-column prop="originalName" label="原名称" min-width="160" />
            <el-table-column prop="resolvedName" label="新名称" min-width="180" />
            <el-table-column prop="reason" label="原因" min-width="180" />
          </el-table>
        </div>

        <div class="section" v-if="previewResult.errors?.length">
          <div class="section-title">失败原因</div>
          <el-alert
            v-for="(issue, index) in previewResult.errors"
            :key="`error-${index}`"
            type="error"
            :closable="false"
            class="issue-alert"
            :title="issue.code || 'ERROR'"
            :description="formatIssue(issue)"
          />
        </div>

        <div class="section" v-if="previewResult.warnings?.length">
          <div class="section-title">告警信息</div>
          <div v-for="(issue, index) in previewResult.warnings" :key="`warning-${index}`">
            <el-alert
              type="warning"
              :closable="false"
              class="issue-alert"
              :title="issue.code || 'WARNING'"
              :description="formatIssue(issue)"
            />
            <div class="issue-action" v-if="resolveIssueTarget(issue)">
              <el-button
                link
                size="small"
                type="primary"
                @click="scrollToSection(resolveIssueTarget(issue))"
              >
                查看详情
              </el-button>
            </div>
          </div>
        </div>

        <div
          class="section"
          v-if="relationCompareDetail"
          :ref="(el) => setSectionRef('relationCompare', el)"
          :class="{ 'section-highlight': activeSectionKey === 'relationCompare' }"
        >
          <div class="section-title">关系双轨比对（声明关系 vs SQL 推断关系）</div>
          <div class="diff-grid">
            <span>声明关系: {{ relationCompareDetail.declaredRelations?.length || 0 }}</span>
            <span>推断关系: {{ relationCompareDetail.inferredRelations?.length || 0 }}</span>
            <span>仅声明: {{ relationCompareDetail.onlyInDeclared?.length || 0 }}</span>
            <span>仅推断: {{ relationCompareDetail.onlyInInferred?.length || 0 }}</span>
          </div>

          <div class="edge-diff-grid">
            <div class="edge-card">
              <div class="edge-card-title">仅在声明关系中</div>
              <div class="edge-card-items" v-if="relationCompareDetail.onlyInDeclared?.length">
                <el-tag
                  v-for="(edge, index) in relationCompareDetail.onlyInDeclared"
                  :key="`declared-only-${index}`"
                  type="danger"
                  class="edge-tag"
                >
                  {{ formatRelation(edge) }}
                </el-tag>
              </div>
              <span class="edge-empty" v-else>无</span>
            </div>

            <div class="edge-card">
              <div class="edge-card-title">仅在推断关系中</div>
              <div class="edge-card-items" v-if="relationCompareDetail.onlyInInferred?.length">
                <el-tag
                  v-for="(edge, index) in relationCompareDetail.onlyInInferred"
                  :key="`inferred-only-${index}`"
                  type="success"
                  class="edge-tag"
                >
                  {{ formatRelation(edge) }}
                </el-tag>
              </div>
              <span class="edge-empty" v-else>无</span>
            </div>
          </div>

          <div class="relation-decision" v-if="relationDecisionRequired">
            <el-alert
              type="warning"
              :closable="false"
              title="检测到声明关系与 SQL 推断关系不一致，请先选择本次同步使用的关系轨道"
            />
            <el-radio-group v-model="relationDecision" class="relation-decision-group">
              <el-radio label="INFERRED">按 SQL 推断关系落盘（推荐）</el-radio>
              <el-radio label="DECLARED">按 Dolphin 声明关系落盘</el-radio>
            </el-radio-group>
          </div>
        </div>

        <div
          class="section"
          v-if="previewResult.diffSummary"
          :ref="(el) => setSectionRef('diffSummary', el)"
          :class="{ 'section-highlight': activeSectionKey === 'diffSummary' }"
        >
          <div class="section-title">差异摘要</div>
          <div class="diff-grid">
            <button
              type="button"
              class="summary-link"
              :class="{ 'is-disabled': !hasDiffItems('workflowFieldChanges') }"
              @click="handleDiffSummaryClick('workflowFieldChanges')"
            >
              workflow 字段变更: {{ previewResult.diffSummary.workflowFieldChanges?.length || 0 }}
            </button>
            <button
              type="button"
              class="summary-link"
              :class="{ 'is-disabled': !hasDiffItems('taskAdded') }"
              @click="handleDiffSummaryClick('taskAdded')"
            >
              任务新增: {{ previewResult.diffSummary.taskAdded?.length || 0 }}
            </button>
            <button
              type="button"
              class="summary-link"
              :class="{ 'is-disabled': !hasDiffItems('taskRemoved') }"
              @click="handleDiffSummaryClick('taskRemoved')"
            >
              任务删除: {{ previewResult.diffSummary.taskRemoved?.length || 0 }}
            </button>
            <button
              type="button"
              class="summary-link"
              :class="{ 'is-disabled': !hasDiffItems('taskModified') }"
              @click="handleDiffSummaryClick('taskModified')"
            >
              任务修改: {{ previewResult.diffSummary.taskModified?.length || 0 }}
            </button>
            <button
              type="button"
              class="summary-link"
              :class="{ 'is-disabled': !hasDiffItems('edgeAdded') }"
              @click="handleDiffSummaryClick('edgeAdded')"
            >
              边新增: {{ previewResult.diffSummary.edgeAdded?.length || 0 }}
            </button>
            <button
              type="button"
              class="summary-link"
              :class="{ 'is-disabled': !hasDiffItems('edgeRemoved') }"
              @click="handleDiffSummaryClick('edgeRemoved')"
            >
              边删除: {{ previewResult.diffSummary.edgeRemoved?.length || 0 }}
            </button>
            <button
              type="button"
              class="summary-link"
              :class="{ 'is-disabled': !hasDiffItems('scheduleChanges') }"
              @click="handleDiffSummaryClick('scheduleChanges')"
            >
              调度变更: {{ previewResult.diffSummary.scheduleChanges?.length || 0 }}
            </button>
          </div>
        </div>

        <div
          class="section"
          v-if="previewResult.diffSummary?.workflowFieldChanges?.length"
          :ref="(el) => setSectionRef('workflowFieldChanges', el)"
          :class="{ 'section-highlight': activeSectionKey === 'workflowFieldChanges' }"
        >
          <div class="section-title">Workflow 字段变更详情</div>
          <el-table :data="previewResult.diffSummary.workflowFieldChanges" size="small" border>
            <el-table-column prop="field" label="字段" min-width="220" />
            <el-table-column label="变更前" min-width="260">
              <template #default="{ row }">{{ formatFieldValue(row.before) }}</template>
            </el-table-column>
            <el-table-column label="变更后" min-width="260">
              <template #default="{ row }">{{ formatFieldValue(row.after) }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div
          class="section"
          v-if="previewResult.diffSummary?.taskAdded?.length"
          :ref="(el) => setSectionRef('taskAdded', el)"
          :class="{ 'section-highlight': activeSectionKey === 'taskAdded' }"
        >
          <div class="section-title">任务新增详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="(item, index) in previewResult.diffSummary.taskAdded"
              :key="`task-add-${index}`"
              type="success"
              class="diff-tag"
            >
              {{ formatTask(item) }}
            </el-tag>
          </div>
        </div>

        <div
          class="section"
          v-if="previewResult.diffSummary?.taskRemoved?.length"
          :ref="(el) => setSectionRef('taskRemoved', el)"
          :class="{ 'section-highlight': activeSectionKey === 'taskRemoved' }"
        >
          <div class="section-title">任务删除详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="(item, index) in previewResult.diffSummary.taskRemoved"
              :key="`task-rm-${index}`"
              type="danger"
              class="diff-tag"
            >
              {{ formatTask(item) }}
            </el-tag>
          </div>
        </div>

        <div
          class="section"
          v-if="previewResult.diffSummary?.taskModified?.length"
          :ref="(el) => setSectionRef('taskModified', el)"
          :class="{ 'section-highlight': activeSectionKey === 'taskModified' }"
        >
          <div class="section-title">任务修改详情</div>
          <div class="task-mod-list">
            <div class="task-mod-card" v-for="(item, index) in previewResult.diffSummary.taskModified" :key="`task-mod-${index}`">
              <div class="task-mod-title">{{ formatTask(item) }}</div>
              <div class="tag-wrap" v-if="item.fieldChanges?.length">
                <el-tag
                  v-for="(change, cIndex) in item.fieldChanges"
                  :key="`task-mod-change-${index}-${cIndex}`"
                  type="warning"
                  class="diff-tag"
                >
                  {{ change.field }}: {{ formatFieldValue(change.before) }} -> {{ formatFieldValue(change.after) }}
                </el-tag>
              </div>
            </div>
          </div>
        </div>

        <div
          class="section"
          v-if="previewResult.diffSummary?.edgeAdded?.length || previewResult.diffSummary?.edgeRemoved?.length"
          :ref="(el) => setSectionRef('edgeChanges', el)"
          :class="{ 'section-highlight': activeSectionKey === 'edgeChanges' }"
        >
          <div class="section-title">边变更详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="(item, index) in previewResult.diffSummary.edgeAdded"
              :key="`edge-add-${index}`"
              type="success"
              class="diff-tag"
            >
              + {{ formatRelation(item) }}
            </el-tag>
            <el-tag
              v-for="(item, index) in previewResult.diffSummary.edgeRemoved"
              :key="`edge-rm-${index}`"
              type="danger"
              class="diff-tag"
            >
              - {{ formatRelation(item) }}
            </el-tag>
          </div>
        </div>

        <div
          class="section"
          v-if="previewResult.diffSummary?.scheduleChanges?.length"
          :ref="(el) => setSectionRef('scheduleChanges', el)"
          :class="{ 'section-highlight': activeSectionKey === 'scheduleChanges' }"
        >
          <div class="section-title">调度变更详情</div>
          <el-table :data="previewResult.diffSummary.scheduleChanges" size="small" border>
            <el-table-column prop="field" label="字段" min-width="220" />
            <el-table-column label="变更前" min-width="260">
              <template #default="{ row }">{{ formatFieldValue(row.before) }}</template>
            </el-table-column>
            <el-table-column label="变更后" min-width="260">
              <template #default="{ row }">{{ formatFieldValue(row.after) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'

const RELATION_MISMATCH_CODE = 'RELATION_MISMATCH'
const RELATION_DECISION_REQUIRED_CODE = 'RELATION_DECISION_REQUIRED'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  presetWorkflow: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:modelValue', 'synced'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const loading = ref(false)
const previewLoading = ref(false)
const syncing = ref(false)
const runtimeWorkflows = ref([])
const selectedWorkflow = ref(null)
const previewResult = ref(null)
const relationDecision = ref('')
const activeSectionKey = ref('')
const sectionRefs = reactive({})

const query = reactive({
  keyword: '',
  projectCode: ''
})

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

const isPresetMode = computed(() => {
  return Boolean(props.presetWorkflow?.workflowCode && props.presetWorkflow?.projectCode)
})

const relationDecisionRequired = computed(() => !!previewResult.value?.relationDecisionRequired)
const relationCompareDetail = computed(() => previewResult.value?.relationCompareDetail || null)

const DIFF_TARGET_MAP = {
  workflowFieldChanges: 'workflowFieldChanges',
  taskAdded: 'taskAdded',
  taskRemoved: 'taskRemoved',
  taskModified: 'taskModified',
  edgeAdded: 'edgeChanges',
  edgeRemoved: 'edgeChanges',
  scheduleChanges: 'scheduleChanges'
}

const canSubmitSync = computed(() => {
  if (!selectedWorkflow.value || !previewResult.value || !previewResult.value.canSync) {
    return false
  }
  if (relationDecisionRequired.value && !relationDecision.value) {
    return false
  }
  return true
})

watch(
  () => visible.value,
  (open) => {
    if (open) {
      if (isPresetMode.value) {
        applyPresetWorkflow()
      }
      loadRuntimeWorkflows()
    } else {
      selectedWorkflow.value = null
      previewResult.value = null
      relationDecision.value = ''
    }
  }
)

watch(
  () => props.presetWorkflow,
  () => {
    if (visible.value && isPresetMode.value) {
      applyPresetWorkflow()
    }
  },
  { deep: true }
)

const loadRuntimeWorkflows = async () => {
  if (isPresetMode.value) {
    applyPresetWorkflow()
    return
  }
  loading.value = true
  try {
    const page = await workflowApi.listRuntimeDolphin({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      keyword: query.keyword || undefined,
      projectCode: query.projectCode ? Number(query.projectCode) : undefined
    })
    runtimeWorkflows.value = page.records || []
    pagination.total = page.total || 0
  } catch (error) {
    console.error('加载 Dolphin 工作流失败', error)
    ElMessage.error(error.message || '加载 Dolphin 工作流失败')
  } finally {
    loading.value = false
  }
}

const applyPresetWorkflow = () => {
  const preset = props.presetWorkflow
  if (!preset?.workflowCode || !preset?.projectCode) {
    runtimeWorkflows.value = []
    selectedWorkflow.value = null
    return
  }
  const row = {
    workflowCode: preset.workflowCode,
    projectCode: preset.projectCode,
    workflowName: preset.workflowName,
    releaseState: preset.releaseState || 'UNKNOWN',
    localWorkflowId: preset.localWorkflowId || null,
    synced: preset.synced !== false,
    lastRuntimeSyncAt: preset.lastRuntimeSyncAt || null
  }
  runtimeWorkflows.value = [row]
  selectedWorkflow.value = row
  pagination.total = 1
  previewResult.value = null
  relationDecision.value = ''
}

const handleSearch = () => {
  pagination.pageNum = 1
  loadRuntimeWorkflows()
}

const handleReset = () => {
  query.keyword = ''
  query.projectCode = ''
  pagination.pageNum = 1
  loadRuntimeWorkflows()
}

const handlePageSizeChange = () => {
  pagination.pageNum = 1
  loadRuntimeWorkflows()
}

const handleCurrentChange = (row) => {
  if (isPresetMode.value) {
    return
  }
  selectedWorkflow.value = row || null
  previewResult.value = null
  relationDecision.value = ''
}

const buildSyncPayload = () => {
  if (!selectedWorkflow.value) return null
  return {
    projectCode: selectedWorkflow.value.projectCode,
    workflowCode: selectedWorkflow.value.workflowCode,
    operator: 'portal-ui',
    relationDecision: relationDecisionRequired.value ? relationDecision.value || undefined : undefined
  }
}

const handlePreview = async () => {
  const payload = buildSyncPayload()
  if (!payload) {
    ElMessage.warning('请先选择一个工作流')
    return
  }
  previewLoading.value = true
  relationDecision.value = ''
  try {
    previewResult.value = await workflowApi.previewRuntimeSync(payload)
    if (previewResult.value?.canSync) {
      if (previewResult.value?.relationDecisionRequired) {
        ElMessage.warning('检测到关系差异，请先选择关系轨道后再同步')
      } else {
        ElMessage.success('预检通过')
      }
    } else {
      ElMessage.warning('预检未通过，请查看失败原因')
    }
  } catch (error) {
    console.error('预检失败', error)
    ElMessage.error(error.message || '预检失败')
  } finally {
    previewLoading.value = false
  }
}

const handleSync = async () => {
  const payload = buildSyncPayload()
  if (!payload) {
    ElMessage.warning('请先选择一个工作流')
    return
  }
  if (!previewResult.value || !previewResult.value.canSync) {
    ElMessage.warning('请先执行预检并通过')
    return
  }
  if (relationDecisionRequired.value && !relationDecision.value) {
    ElMessage.warning('请先选择关系轨道')
    scrollToSection('relationCompare')
    return
  }

  syncing.value = true
  try {
    const result = await workflowApi.syncRuntime(payload)
    if (result.success) {
      ElMessage.success('同步成功')
      emit('synced', result)
      visible.value = false
    } else {
      previewResult.value = {
        ...(previewResult.value || {}),
        errors: result.errors || [],
        warnings: result.warnings || [],
        diffSummary: result.diffSummary || previewResult.value?.diffSummary,
        relationDecisionRequired: result.relationDecisionRequired ?? previewResult.value?.relationDecisionRequired,
        relationCompareDetail: result.relationCompareDetail || previewResult.value?.relationCompareDetail
      }
      ElMessage.error(result.errors?.[0]?.message || '同步失败')
    }
  } catch (error) {
    console.error('同步失败', error)
    ElMessage.error(error.message || '同步失败')
  } finally {
    syncing.value = false
  }
}

const setSectionRef = (key, el) => {
  if (!key) return
  if (el) {
    sectionRefs[key] = el
  } else {
    delete sectionRefs[key]
  }
}

const resolveIssueTarget = (issue) => {
  if (!issue?.code) return ''
  if (issue.code === RELATION_MISMATCH_CODE || issue.code === RELATION_DECISION_REQUIRED_CODE) {
    return 'relationCompare'
  }
  if (previewResult.value?.diffSummary) {
    return 'diffSummary'
  }
  return 'relationCompare'
}

const hasDiffItems = (field) => {
  const list = previewResult.value?.diffSummary?.[field]
  return Array.isArray(list) && list.length > 0
}

const handleDiffSummaryClick = (field) => {
  if (!hasDiffItems(field)) {
    return
  }
  const target = DIFF_TARGET_MAP[field]
  if (target) {
    scrollToSection(target)
  }
}

const scrollToSection = async (key) => {
  if (!key) return
  await nextTick()
  const el = sectionRefs[key]
  if (!el || typeof el.scrollIntoView !== 'function') {
    return
  }
  activeSectionKey.value = key
  el.scrollIntoView({
    behavior: 'smooth',
    block: 'start'
  })
  window.setTimeout(() => {
    if (activeSectionKey.value === key) {
      activeSectionKey.value = ''
    }
  }, 1800)
}

const formatIssue = (issue) => {
  if (!issue) return '-'
  const parts = []
  if (issue.taskName) {
    parts.push(`任务: ${issue.taskName}`)
  }
  if (issue.nodeType) {
    parts.push(`节点类型: ${issue.nodeType}`)
  }
  if (issue.rawName) {
    parts.push(`对象: ${issue.rawName}`)
  }
  if (issue.message) {
    parts.push(issue.message)
  }
  return parts.join(' | ')
}

const formatDateTime = (value) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const formatFieldValue = (value) => {
  return value === null || value === undefined || value === '' ? '∅' : String(value)
}

const formatTask = (task) => {
  if (!task) return '-'
  const code = task.taskCode !== null && task.taskCode !== undefined ? `#${task.taskCode}` : '#-'
  const name = task.taskName || '未命名任务'
  return `${name} (${code})`
}

const formatRelation = (edge) => {
  if (!edge) return '-'
  const preCode = edge.preTaskCode !== null && edge.preTaskCode !== undefined ? edge.preTaskCode : '-'
  const postCode = edge.postTaskCode !== null && edge.postTaskCode !== undefined ? edge.postTaskCode : '-'
  const preName = edge.entryEdge ? '入口' : (edge.preTaskName || preCode)
  const postName = edge.postTaskName || postCode
  return `${preName}(${preCode}) -> ${postName}(${postCode})`
}
</script>

<style scoped>
.sync-dialog {
  min-height: 520px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.filters {
  display: flex;
  gap: 10px;
  align-items: center;
}

.actions {
  display: flex;
  gap: 10px;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.meta-line {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
  display: flex;
  gap: 10px;
}

.local-id {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}

.pagination {
  margin-top: 12px;
  justify-content: flex-end;
}

.result {
  margin-top: 16px;
  border-top: 1px solid #ebeef5;
  padding-top: 16px;
}

.section {
  margin-top: 14px;
}

.section-highlight {
  border-left: 3px solid #409eff;
  padding-left: 10px;
  background: #f4f8ff;
  border-radius: 4px;
}

.section-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.issue-alert {
  margin-bottom: 8px;
}

.issue-action {
  margin: -4px 0 8px 6px;
}

.diff-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(180px, 1fr));
  gap: 8px;
  font-size: 13px;
  color: #606266;
}

.tag-wrap {
  display: flex;
  flex-wrap: wrap;
}

.diff-tag {
  margin: 0 8px 8px 0;
  max-width: 100%;
}

.summary-link {
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  text-align: left;
  font-size: 13px;
  color: #409eff;
  cursor: pointer;
}

.summary-link:hover {
  text-decoration: underline;
}

.summary-link.is-disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}

.summary-link.is-disabled:hover {
  text-decoration: none;
}

.edge-diff-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 10px;
}

.edge-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  min-height: 120px;
}

.edge-card-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.edge-card-items {
  display: flex;
  flex-wrap: wrap;
}

.edge-tag {
  margin: 0 8px 8px 0;
}

.edge-empty {
  color: #909399;
  font-size: 12px;
}

.relation-decision {
  margin-top: 10px;
}

.relation-decision-group {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-mod-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task-mod-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
}

.task-mod-title {
  font-weight: 600;
  margin-bottom: 8px;
}

@media (max-width: 900px) {
  .edge-diff-grid {
    grid-template-columns: 1fr;
  }
}
</style>
