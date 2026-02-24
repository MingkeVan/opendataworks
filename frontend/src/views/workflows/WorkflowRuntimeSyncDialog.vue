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
          <el-alert
            v-for="(issue, index) in previewResult.warnings"
            :key="`warning-${index}`"
            type="warning"
            :closable="false"
            class="issue-alert"
            :title="issue.code || 'WARNING'"
            :description="formatIssue(issue)"
          />
        </div>

        <div class="section" v-if="previewResult.paritySummary && parityChecked">
          <div class="section-title">导出定义一致性详情</div>
          <div class="diff-grid">
            <span>状态: {{ parityText(previewResult.parityStatus) }}</span>
            <span>结果: {{ previewResult.paritySummary?.changed ? '存在差异' : '一致' }}</span>
            <span>主路 Hash: {{ shortHash(previewResult.paritySummary?.primaryHash) }}</span>
            <span>影子 Hash: {{ shortHash(previewResult.paritySummary?.shadowHash) }}</span>
            <span>workflow 字段差异: {{ previewResult.paritySummary?.workflowFieldDiffCount || 0 }}</span>
            <span>任务新增差异: {{ previewResult.paritySummary?.taskAddedDiffCount || 0 }}</span>
            <span>任务删除差异: {{ previewResult.paritySummary?.taskRemovedDiffCount || 0 }}</span>
            <span>任务修改差异: {{ previewResult.paritySummary?.taskModifiedDiffCount || 0 }}</span>
            <span>边新增差异: {{ previewResult.paritySummary?.edgeAddedDiffCount || 0 }}</span>
            <span>边删除差异: {{ previewResult.paritySummary?.edgeRemovedDiffCount || 0 }}</span>
            <span>调度差异: {{ previewResult.paritySummary?.scheduleDiffCount || 0 }}</span>
          </div>
          <div class="parity-samples" v-if="previewResult.paritySummary?.sampleMismatches?.length">
            <div class="detail-tip">不一致样例（前 {{ previewResult.paritySummary.sampleMismatches.length }} 条）</div>
            <div class="tag-wrap">
              <el-tag
                v-for="(sample, index) in previewResult.paritySummary.sampleMismatches"
                :key="`parity-sample-${index}`"
                type="warning"
                class="diff-tag"
              >
                {{ sample }}
              </el-tag>
            </div>
          </div>
        </div>

        <div class="section edge-confirm-section" v-if="edgeMismatchRequired">
          <div class="section-title">边差异确认</div>
          <el-alert
            type="warning"
            :closable="false"
            title="检测到显式边与血缘推断边不一致，请先查看差异详情并确认，或取消同步"
          />
          <div class="edge-diff-grid">
            <div class="edge-card">
              <div class="edge-card-title">仅在 Dolphin 显式边中</div>
              <div class="edge-card-items" v-if="edgeMismatchDetail.onlyInExplicit?.length">
                <el-tag
                  v-for="edge in edgeMismatchDetail.onlyInExplicit"
                  :key="`only-explicit-${edge}`"
                  type="danger"
                  class="edge-tag"
                >
                  {{ edge }}
                </el-tag>
              </div>
              <span class="edge-empty" v-else>无</span>
            </div>
            <div class="edge-card">
              <div class="edge-card-title">仅在血缘推断边中</div>
              <div class="edge-card-items" v-if="edgeMismatchDetail.onlyInInferred?.length">
                <el-tag
                  v-for="edge in edgeMismatchDetail.onlyInInferred"
                  :key="`only-inferred-${edge}`"
                  type="success"
                  class="edge-tag"
                >
                  {{ edge }}
                </el-tag>
              </div>
              <span class="edge-empty" v-else>无</span>
            </div>
          </div>
          <div class="edge-confirm-actions">
            <el-checkbox v-model="edgeMismatchConfirmed">
              我已查看并确认边差异，按血缘推断边继续同步
            </el-checkbox>
            <el-button size="small" @click="cancelEdgeMismatchSync">取消本次同步</el-button>
          </div>
        </div>

        <div class="section" v-if="previewResult.diffSummary">
          <div class="section-title">差异摘要</div>
          <div class="diff-grid">
            <span>workflow 字段变更: {{ previewResult.diffSummary.workflowFieldChanges?.length || 0 }}</span>
            <span>任务新增: {{ previewResult.diffSummary.taskAdded?.length || 0 }}</span>
            <span>任务删除: {{ previewResult.diffSummary.taskRemoved?.length || 0 }}</span>
            <span>任务修改: {{ previewResult.diffSummary.taskModified?.length || 0 }}</span>
            <span>边新增: {{ previewResult.diffSummary.edgeAdded?.length || 0 }}</span>
            <span>边删除: {{ previewResult.diffSummary.edgeRemoved?.length || 0 }}</span>
            <span>调度变更: {{ previewResult.diffSummary.scheduleChanges?.length || 0 }}</span>
          </div>
        </div>

        <div class="section" v-if="previewResult.diffSummary?.workflowFieldChanges?.length">
          <div class="section-title">Workflow 字段变更详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="item in previewResult.diffSummary.workflowFieldChanges"
              :key="`wf-${item}`"
              class="diff-tag"
            >
              {{ item }}
            </el-tag>
          </div>
        </div>

        <div class="section" v-if="previewResult.diffSummary?.taskAdded?.length">
          <div class="section-title">任务新增详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="item in previewResult.diffSummary.taskAdded"
              :key="`task-add-${item}`"
              type="success"
              class="diff-tag"
            >
              {{ item }}
            </el-tag>
          </div>
        </div>

        <div class="section" v-if="previewResult.diffSummary?.taskRemoved?.length">
          <div class="section-title">任务删除详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="item in previewResult.diffSummary.taskRemoved"
              :key="`task-rm-${item}`"
              type="danger"
              class="diff-tag"
            >
              {{ item }}
            </el-tag>
          </div>
        </div>

        <div class="section" v-if="previewResult.diffSummary?.taskModified?.length">
          <div class="section-title">任务修改详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="item in previewResult.diffSummary.taskModified"
              :key="`task-mod-${item}`"
              type="warning"
              class="diff-tag"
            >
              {{ item }}
            </el-tag>
          </div>
        </div>

        <div class="section" v-if="previewResult.diffSummary?.edgeAdded?.length || previewResult.diffSummary?.edgeRemoved?.length">
          <div class="section-title">边变更详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="item in previewResult.diffSummary.edgeAdded"
              :key="`edge-add-${item}`"
              type="success"
              class="diff-tag"
            >
              + {{ item }}
            </el-tag>
            <el-tag
              v-for="item in previewResult.diffSummary.edgeRemoved"
              :key="`edge-rm-${item}`"
              type="danger"
              class="diff-tag"
            >
              - {{ item }}
            </el-tag>
          </div>
        </div>

        <div class="section" v-if="previewResult.diffSummary?.scheduleChanges?.length">
          <div class="section-title">调度变更详情</div>
          <div class="tag-wrap">
            <el-tag
              v-for="item in previewResult.diffSummary.scheduleChanges"
              :key="`sch-${item}`"
              class="diff-tag"
            >
              {{ item }}
            </el-tag>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'

const EDGE_MISMATCH_CODE = 'EDGE_MISMATCH'
const PARITY_MISMATCH_CODE = 'DEFINITION_PARITY_MISMATCH'

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
const edgeMismatchConfirmed = ref(false)

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

const edgeMismatchDetail = computed(() => previewResult.value?.edgeMismatchDetail || null)
const edgeMismatchRequired = computed(() => !!edgeMismatchDetail.value)
const parityChecked = computed(() => {
  const status = String(previewResult.value?.parityStatus || '').toLowerCase()
  return status === 'consistent' || status === 'inconsistent'
})
const canSubmitSync = computed(() => {
  if (!selectedWorkflow.value || !previewResult.value || !previewResult.value.canSync) {
    return false
  }
  if (edgeMismatchRequired.value && !edgeMismatchConfirmed.value) {
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
      edgeMismatchConfirmed.value = false
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
  edgeMismatchConfirmed.value = false
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
  edgeMismatchConfirmed.value = false
}

const buildSyncPayload = () => {
  if (!selectedWorkflow.value) return null
  return {
    projectCode: selectedWorkflow.value.projectCode,
    workflowCode: selectedWorkflow.value.workflowCode,
    operator: 'portal-ui',
    confirmEdgeMismatch: edgeMismatchRequired.value ? !!edgeMismatchConfirmed.value : undefined
  }
}

const handlePreview = async () => {
  const payload = buildSyncPayload()
  if (!payload) {
    ElMessage.warning('请先选择一个工作流')
    return
  }
  previewLoading.value = true
  edgeMismatchConfirmed.value = false
  try {
    previewResult.value = await workflowApi.previewRuntimeSync(payload)
    if (previewResult.value?.canSync) {
      if (hasEdgeMismatchWarning(previewResult.value)) {
        ElMessage.warning('检测到边差异，请人工确认后再同步')
      } else if (hasParityMismatchWarning(previewResult.value)) {
        ElMessage.warning('预检通过，但导出定义与旧路径解析存在不一致，请先核对一致性详情')
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
  if (edgeMismatchRequired.value && !edgeMismatchConfirmed.value) {
    ElMessage.warning('请先确认边差异，或取消本次同步')
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
        edgeMismatchDetail: result.edgeMismatchDetail || previewResult.value?.edgeMismatchDetail
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

const hasEdgeMismatchWarning = (result) => {
  return (result?.warnings || []).some((issue) => issue?.code === EDGE_MISMATCH_CODE)
}

const hasParityMismatchWarning = (result) => {
  return (result?.warnings || []).some((issue) => issue?.code === PARITY_MISMATCH_CODE)
}

const cancelEdgeMismatchSync = () => {
  edgeMismatchConfirmed.value = false
  visible.value = false
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

const shortHash = (value) => {
  if (!value) return '-'
  return String(value).slice(0, 12)
}

const parityText = (status) => {
  const map = {
    consistent: '一致',
    inconsistent: '不一致',
    not_checked: '未校验'
  }
  return map[status] || status || '未校验'
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

.section-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.issue-alert {
  margin-bottom: 8px;
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

.parity-samples {
  margin-top: 8px;
}

.detail-tip {
  margin-bottom: 6px;
  font-size: 12px;
  color: #909399;
}

.edge-confirm-section :deep(.el-alert) {
  margin-bottom: 10px;
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

.edge-confirm-actions {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

@media (max-width: 900px) {
  .edge-diff-grid {
    grid-template-columns: 1fr;
  }
}
</style>
