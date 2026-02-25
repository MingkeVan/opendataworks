<template>
  <el-drawer
    v-model="visible"
    title="运行态差异比对"
    size="46%"
    destroy-on-close
  >
    <div class="diff-drawer" v-loading="loading">
      <el-descriptions :column="1" border v-if="workflow">
        <el-descriptions-item label="工作流">
          {{ workflow.workflowName || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="本地ID">
          {{ workflow.id || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="Dolphin编码">
          {{ workflow.workflowCode || '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="section" v-if="result?.errors?.length">
        <div class="section-title">失败项</div>
        <el-alert
          v-for="(issue, index) in result.errors"
          :key="`err-${index}`"
          type="error"
          :closable="false"
          :title="issue.code || 'ERROR'"
          :description="formatIssue(issue)"
          class="issue-alert"
        />
      </div>

      <div class="section" v-if="result?.warnings?.length">
        <div class="section-title">告警项</div>
        <el-alert
          v-for="(issue, index) in result.warnings"
          :key="`warn-${index}`"
          type="warning"
          :closable="false"
          :title="issue.code || 'WARNING'"
          :description="formatIssue(issue)"
          class="issue-alert"
        />
      </div>

      <div class="section" v-if="result?.diffSummary">
        <div class="section-title">摘要</div>
        <div class="summary-grid">
          <span>workflow字段变更: {{ result.diffSummary.workflowFieldChanges?.length || 0 }}</span>
          <span>任务新增: {{ result.diffSummary.taskAdded?.length || 0 }}</span>
          <span>任务删除: {{ result.diffSummary.taskRemoved?.length || 0 }}</span>
          <span>任务修改: {{ result.diffSummary.taskModified?.length || 0 }}</span>
          <span>边新增: {{ result.diffSummary.edgeAdded?.length || 0 }}</span>
          <span>边删除: {{ result.diffSummary.edgeRemoved?.length || 0 }}</span>
          <span>调度变更: {{ result.diffSummary.scheduleChanges?.length || 0 }}</span>
        </div>
      </div>

      <div class="section" v-if="result?.diffSummary?.workflowFieldChanges?.length">
        <div class="section-title">Workflow 字段变更</div>
        <el-table :data="result.diffSummary.workflowFieldChanges" size="small" border>
          <el-table-column prop="field" label="字段" min-width="220" />
          <el-table-column label="变更前" min-width="220">
            <template #default="{ row }">{{ formatFieldValue(row.before) }}</template>
          </el-table-column>
          <el-table-column label="变更后" min-width="220">
            <template #default="{ row }">{{ formatFieldValue(row.after) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="section" v-if="result?.diffSummary?.taskAdded?.length">
        <div class="section-title">任务新增</div>
        <el-tag v-for="(item, index) in result.diffSummary.taskAdded" :key="`task-add-${index}`" type="success" class="item-tag">
          {{ formatTask(item) }}
        </el-tag>
      </div>

      <div class="section" v-if="result?.diffSummary?.taskRemoved?.length">
        <div class="section-title">任务删除</div>
        <el-tag v-for="(item, index) in result.diffSummary.taskRemoved" :key="`task-rm-${index}`" type="danger" class="item-tag">
          {{ formatTask(item) }}
        </el-tag>
      </div>

      <div class="section" v-if="result?.diffSummary?.taskModified?.length">
        <div class="section-title">任务修改</div>
        <div class="task-mod-list">
          <div class="task-mod-card" v-for="(item, index) in result.diffSummary.taskModified" :key="`task-mod-${index}`">
            <div class="task-mod-title">{{ formatTask(item) }}</div>
            <el-tag
              v-for="(change, cIndex) in item.fieldChanges"
              :key="`task-mod-change-${index}-${cIndex}`"
              type="warning"
              class="item-tag"
            >
              {{ change.field }}: {{ formatFieldValue(change.before) }} -> {{ formatFieldValue(change.after) }}
            </el-tag>
          </div>
        </div>
      </div>

      <div class="section" v-if="result?.diffSummary?.edgeAdded?.length || result?.diffSummary?.edgeRemoved?.length">
        <div class="section-title">边变更</div>
        <div class="edge-list">
          <el-tag v-for="(item, index) in result.diffSummary.edgeAdded" :key="`add-${index}`" type="success" class="item-tag">
            + {{ formatRelation(item) }}
          </el-tag>
          <el-tag v-for="(item, index) in result.diffSummary.edgeRemoved" :key="`rm-${index}`" type="danger" class="item-tag">
            - {{ formatRelation(item) }}
          </el-tag>
        </div>
      </div>

      <div class="section" v-if="result?.diffSummary?.scheduleChanges?.length">
        <div class="section-title">调度变更</div>
        <el-table :data="result.diffSummary.scheduleChanges" size="small" border>
          <el-table-column prop="field" label="字段" min-width="220" />
          <el-table-column label="变更前" min-width="220">
            <template #default="{ row }">{{ formatFieldValue(row.before) }}</template>
          </el-table-column>
          <el-table-column label="变更后" min-width="220">
            <template #default="{ row }">{{ formatFieldValue(row.after) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  workflow: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const loading = ref(false)
const result = ref(null)

watch(
  () => visible.value,
  (open) => {
    if (open) {
      loadDiff()
    } else {
      result.value = null
    }
  }
)

watch(
  () => props.workflow?.id,
  () => {
    if (visible.value) {
      loadDiff()
    }
  }
)

const loadDiff = async () => {
  if (!props.workflow?.id) {
    return
  }
  loading.value = true
  try {
    result.value = await workflowApi.runtimeDiff(props.workflow.id)
  } catch (error) {
    console.error('加载运行态差异失败', error)
    ElMessage.error(error.message || '加载运行态差异失败')
  } finally {
    loading.value = false
  }
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
  if (issue.message) {
    parts.push(issue.message)
  }
  return parts.join(' | ')
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
.diff-drawer {
  padding-right: 8px;
}

.section {
  margin-top: 16px;
}

.section-title {
  margin-bottom: 8px;
  font-weight: 600;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(200px, 1fr));
  gap: 8px;
  font-size: 13px;
  color: #606266;
}

.item-tag {
  margin: 0 8px 8px 0;
}

.issue-alert {
  margin-bottom: 8px;
}

.edge-list {
  display: flex;
  flex-wrap: wrap;
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
</style>
