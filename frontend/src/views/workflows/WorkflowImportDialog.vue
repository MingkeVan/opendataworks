<template>
  <el-dialog
    :model-value="modelValue"
    title="导入工作流"
    width="980px"
    @close="handleClose"
  >
    <div class="import-panel">
      <div class="mode-switch">
        <el-radio-group v-model="importMode" @change="handleModeChange">
          <el-radio-button label="json">JSON 导入</el-radio-button>
          <el-radio-button label="dolphin">从 Dolphin 导入</el-radio-button>
        </el-radio-group>
      </div>

      <div v-if="importMode === 'json'" class="json-import">
        <div class="import-toolbar">
          <input
            ref="fileInputRef"
            type="file"
            accept=".json,application/json"
            class="hidden-file-input"
            @change="handleFileSelected"
          >
          <el-button @click="openFilePicker">选择文件</el-button>
          <span class="file-name">{{ fileName || '未选择文件' }}</span>
        </div>

        <el-input
          v-model="definitionJson"
          type="textarea"
          :rows="10"
          placeholder="可直接粘贴 workflow JSON 文本，或选择 .json 文件"
        />
      </div>

      <div v-else class="dolphin-import">
        <div class="dolphin-toolbar">
          <el-input
            v-model="dolphinQuery.keyword"
            placeholder="搜索工作流名称"
            clearable
            @keyup.enter="handleDolphinSearch"
          />
          <el-input
            v-model="dolphinQuery.projectCode"
            placeholder="项目编码（可选）"
            clearable
            style="width: 180px"
          />
          <el-button type="primary" :loading="dolphinLoading" @click="handleDolphinSearch">查询</el-button>
          <el-button @click="handleDolphinReset">重置</el-button>
        </div>

        <el-table
          v-loading="dolphinLoading"
          :data="dolphinWorkflows"
          row-key="workflowCode"
          highlight-current-row
          max-height="260"
          @current-change="handleDolphinCurrentChange"
        >
          <el-table-column label="工作流" min-width="280">
            <template #default="{ row }">
              <div class="name-line">
                <span>{{ row.workflowName || '-' }}</span>
                <el-tag size="small" :type="row.releaseState === 'ONLINE' ? 'success' : 'info'">
                  {{ row.releaseState || '-' }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="projectCode" label="项目编码" width="160" />
          <el-table-column prop="workflowCode" label="工作流编码" width="180" />
        </el-table>

        <el-pagination
          class="pagination"
          v-model:current-page="dolphinPagination.pageNum"
          v-model:page-size="dolphinPagination.pageSize"
          :total="dolphinPagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="handleDolphinSizeChange"
          @current-change="loadDolphinWorkflows"
        />

        <el-form label-width="120px" class="name-form">
          <el-form-item label="新工作流名称" required>
            <el-input
              v-model="importWorkflowName"
              placeholder="默认使用 Dolphin 工作流名称，可编辑"
              maxlength="100"
              show-word-limit
            />
          </el-form-item>
        </el-form>
      </div>

      <div class="action-bar">
        <el-button
          type="primary"
          :disabled="!canPreview"
          :loading="previewLoading"
          @click="handlePreview"
        >
          预检
        </el-button>
      </div>

      <div v-if="previewResult" class="preview-panel">
        <div class="preview-header">
          <el-tag :type="previewResult.canImport ? 'success' : 'danger'">
            {{ previewResult.canImport ? '可导入' : '不可导入' }}
          </el-tag>
          <span>工作流：{{ previewResult.workflowName || '-' }}</span>
          <span>任务数：{{ previewResult.taskCount || 0 }}</span>
        </div>

        <el-alert
          v-if="previewResult.errors?.length"
          type="error"
          :closable="false"
          show-icon
          title="预检错误"
        >
          <template #default>
            <div
              v-for="(item, idx) in previewResult.errors"
              :key="`error-${idx}`"
              class="issue-line"
            >
              {{ item }}
            </div>
          </template>
        </el-alert>

        <el-alert
          v-if="previewResult.warnings?.length"
          type="warning"
          :closable="false"
          show-icon
          title="预检告警"
        >
          <template #default>
            <div
              v-for="(item, idx) in previewResult.warnings"
              :key="`warning-${idx}`"
              class="issue-line"
            >
              {{ item }}
            </div>
          </template>
        </el-alert>

        <div v-if="previewResult.relationDecisionRequired" class="relation-decision">
          <div class="relation-title">关系差异存在，请选择导入轨道</div>
          <el-radio-group v-model="relationDecision">
            <el-radio label="INFERRED">SQL 推断关系（推荐）</el-radio>
            <el-radio label="DECLARED">文件声明关系</el-radio>
          </el-radio-group>
          <div class="relation-hint">
            不选轨道将无法提交导入。
          </div>
        </div>

        <div
          v-if="previewResult.relationCompareDetail && (
            previewResult.relationCompareDetail.onlyInDeclared?.length
              || previewResult.relationCompareDetail.onlyInInferred?.length
          )"
          class="relation-diff"
        >
          <div class="relation-col">
            <div class="relation-col-title">仅声明关系</div>
            <div
              v-for="(edge, idx) in previewResult.relationCompareDetail.onlyInDeclared"
              :key="`declared-${idx}`"
              class="edge-line"
            >
              {{ formatEdge(edge) }}
            </div>
            <div v-if="!previewResult.relationCompareDetail.onlyInDeclared?.length" class="empty-line">-</div>
          </div>
          <div class="relation-col">
            <div class="relation-col-title">仅 SQL 推断</div>
            <div
              v-for="(edge, idx) in previewResult.relationCompareDetail.onlyInInferred"
              :key="`inferred-${idx}`"
              class="edge-line"
            >
              {{ formatEdge(edge) }}
            </div>
            <div v-if="!previewResult.relationCompareDetail.onlyInInferred?.length" class="empty-line">-</div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button
        type="primary"
        :disabled="!canCommit"
        :loading="commitLoading"
        @click="handleCommit"
      >
        确认导入
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'imported'])

const fileInputRef = ref(null)
const importMode = ref('json')
const fileName = ref('')
const definitionJson = ref('')

const dolphinLoading = ref(false)
const dolphinWorkflows = ref([])
const selectedDolphinWorkflow = ref(null)
const importWorkflowName = ref('')
const dolphinQuery = reactive({
  keyword: '',
  projectCode: ''
})
const dolphinPagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

const previewLoading = ref(false)
const commitLoading = ref(false)
const previewResult = ref(null)
const relationDecision = ref('INFERRED')

const canPreview = computed(() => {
  if (importMode.value === 'dolphin') {
    return Boolean(selectedDolphinWorkflow.value?.workflowCode && importWorkflowName.value.trim())
  }
  return Boolean(definitionJson.value.trim())
})

const canCommit = computed(() => {
  if (!previewResult.value?.canImport) return false
  if (importMode.value === 'dolphin' && !importWorkflowName.value.trim()) return false
  if (!previewResult.value?.relationDecisionRequired) return true
  return Boolean(relationDecision.value)
})

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  if (importMode.value === 'dolphin' && !dolphinWorkflows.value.length) {
    loadDolphinWorkflows()
  }
})

const openFilePicker = () => {
  fileInputRef.value?.click()
}

const handleFileSelected = async (event) => {
  const file = event?.target?.files?.[0]
  if (!file) return
  fileName.value = file.name
  try {
    definitionJson.value = await file.text()
    previewResult.value = null
  } catch (error) {
    console.error('读取文件失败', error)
    ElMessage.error('读取文件失败')
  }
}

const handleModeChange = () => {
  previewResult.value = null
  relationDecision.value = 'INFERRED'
  if (importMode.value === 'dolphin' && !dolphinWorkflows.value.length) {
    loadDolphinWorkflows()
  }
}

const handleDolphinSearch = () => {
  dolphinPagination.pageNum = 1
  loadDolphinWorkflows()
}

const handleDolphinReset = () => {
  dolphinQuery.keyword = ''
  dolphinQuery.projectCode = ''
  handleDolphinSearch()
}

const handleDolphinSizeChange = () => {
  dolphinPagination.pageNum = 1
  loadDolphinWorkflows()
}

const loadDolphinWorkflows = async () => {
  const projectCodeText = dolphinQuery.projectCode?.trim()
  const parsedProjectCode = projectCodeText ? Number(projectCodeText) : undefined
  if (projectCodeText && !Number.isFinite(parsedProjectCode)) {
    ElMessage.warning('项目编码必须是数字')
    return
  }
  dolphinLoading.value = true
  try {
    const page = await workflowApi.listImportDolphinWorkflows({
      pageNum: dolphinPagination.pageNum,
      pageSize: dolphinPagination.pageSize,
      keyword: dolphinQuery.keyword || undefined,
      projectCode: parsedProjectCode
    })
    dolphinWorkflows.value = page?.records || []
    dolphinPagination.total = page?.total || 0
  } catch (error) {
    console.error('加载 Dolphin 工作流失败', error)
    ElMessage.error('加载 Dolphin 工作流失败')
  } finally {
    dolphinLoading.value = false
  }
}

const handleDolphinCurrentChange = (row) => {
  selectedDolphinWorkflow.value = row || null
  previewResult.value = null
  relationDecision.value = 'INFERRED'
  if (!row) {
    importWorkflowName.value = ''
    return
  }
  importWorkflowName.value = buildDefaultImportedWorkflowName(row)
}

const buildDefaultImportedWorkflowName = (workflow) => {
  return String(workflow?.workflowName || `workflow_${workflow?.workflowCode || 'new'}`).trim()
}

const buildImportPayload = () => {
  const payload = {
    sourceType: importMode.value,
    operator: 'portal-ui'
  }
  if (importMode.value === 'dolphin') {
    payload.projectCode = selectedDolphinWorkflow.value?.projectCode
    payload.workflowCode = selectedDolphinWorkflow.value?.workflowCode
    payload.workflowName = importWorkflowName.value.trim()
  } else {
    payload.definitionJson = definitionJson.value
  }
  return payload
}

const handlePreview = async () => {
  if (!canPreview.value) {
    ElMessage.warning(importMode.value === 'dolphin'
      ? '请先选择 Dolphin 工作流并填写新工作流名称'
      : '请先选择文件或粘贴 JSON')
    return
  }
  previewLoading.value = true
  try {
    const result = await workflowApi.previewImportDefinition(buildImportPayload())
    previewResult.value = result
    relationDecision.value = result?.suggestedRelationDecision || 'INFERRED'
    if (importMode.value === 'dolphin' && result?.workflowName && !importWorkflowName.value.trim()) {
      importWorkflowName.value = result.workflowName
    }
  } catch (error) {
    console.error('导入预检失败', error)
  } finally {
    previewLoading.value = false
  }
}

const handleCommit = async () => {
  if (!canCommit.value) {
    ElMessage.warning('当前预检未通过，无法导入')
    return
  }
  commitLoading.value = true
  try {
    const payload = buildImportPayload()
    if (previewResult.value?.relationDecisionRequired) {
      payload.relationDecision = relationDecision.value
    }
    const result = await workflowApi.commitImportDefinition(payload)
    ElMessage.success(`导入成功：${result?.workflowName || ''}`)
    emit('imported', result)
    handleClose()
  } catch (error) {
    console.error('导入提交失败', error)
  } finally {
    commitLoading.value = false
  }
}

const formatEdge = (edge) => {
  if (!edge) return '-'
  const pre = edge.preTaskCode === 0 ? '入口' : (edge.preTaskName || edge.preTaskCode || '-')
  const post = edge.postTaskName || edge.postTaskCode || '-'
  return `${pre} -> ${post}`
}

const resetState = () => {
  importMode.value = 'json'
  fileName.value = ''
  definitionJson.value = ''
  previewResult.value = null
  relationDecision.value = 'INFERRED'
  selectedDolphinWorkflow.value = null
  importWorkflowName.value = ''
  dolphinQuery.keyword = ''
  dolphinQuery.projectCode = ''
  dolphinPagination.pageNum = 1
  dolphinPagination.pageSize = 10
  dolphinPagination.total = 0
  dolphinWorkflows.value = []
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

const handleClose = () => {
  resetState()
  emit('update:modelValue', false)
}
</script>

<style scoped>
.import-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mode-switch {
  display: flex;
  justify-content: flex-start;
}

.import-toolbar,
.dolphin-toolbar,
.action-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hidden-file-input {
  display: none;
}

.file-name {
  color: #606266;
  font-size: 13px;
  flex: 1;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pagination {
  display: flex;
  justify-content: flex-end;
}

.name-form {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px 12px 0;
  background: #fcfcfc;
}

.preview-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  background: #fcfcfc;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;
  color: #303133;
}

.issue-line {
  line-height: 1.6;
  font-size: 13px;
}

.relation-decision {
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  padding: 10px;
}

.relation-title {
  margin-bottom: 8px;
  font-weight: 600;
}

.relation-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.relation-diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.relation-col {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px;
  min-height: 90px;
  background: #fff;
}

.relation-col-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.edge-line {
  font-size: 12px;
  line-height: 1.5;
  color: #606266;
}

.empty-line {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
