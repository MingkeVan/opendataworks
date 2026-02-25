<template>
  <el-dialog
    :model-value="modelValue"
    title="导入工作流 JSON"
    width="760px"
    @close="handleClose"
  >
    <div class="import-panel">
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
        <el-button
          type="primary"
          :disabled="!definitionJson"
          :loading="previewLoading"
          @click="handlePreview"
        >
          预检
        </el-button>
      </div>

      <el-input
        v-model="definitionJson"
        type="textarea"
        :rows="10"
        placeholder="可直接粘贴 workflow JSON 文本，或选择 .json 文件"
      />

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
import { computed, ref } from 'vue'
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
const fileName = ref('')
const definitionJson = ref('')
const previewLoading = ref(false)
const commitLoading = ref(false)
const previewResult = ref(null)
const relationDecision = ref('INFERRED')

const canCommit = computed(() => {
  if (!previewResult.value?.canImport) return false
  if (!previewResult.value?.relationDecisionRequired) return true
  return Boolean(relationDecision.value)
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

const handlePreview = async () => {
  if (!definitionJson.value.trim()) {
    ElMessage.warning('请先选择文件或粘贴 JSON')
    return
  }
  previewLoading.value = true
  try {
    const result = await workflowApi.previewImportDefinition({
      definitionJson: definitionJson.value,
      operator: 'portal-ui'
    })
    previewResult.value = result
    relationDecision.value = result?.suggestedRelationDecision || 'INFERRED'
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
    const result = await workflowApi.commitImportDefinition({
      definitionJson: definitionJson.value,
      relationDecision: previewResult.value?.relationDecisionRequired ? relationDecision.value : undefined,
      operator: 'portal-ui'
    })
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
  fileName.value = ''
  definitionJson.value = ''
  previewResult.value = null
  relationDecision.value = 'INFERRED'
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

.import-toolbar {
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
