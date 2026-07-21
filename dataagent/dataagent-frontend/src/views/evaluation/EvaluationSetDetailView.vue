<template>
  <div class="eval-detail">
    <div class="eval-detail__topbar">
      <button type="button" class="eval-detail__back" @click="goBack">&larr; 评测集列表</button>
      <span class="eval-detail__slash">/</span>
      <span class="eval-detail__name">{{ dataset?.name || datasetId }}</span>
    </div>

    <div v-if="dataset" class="eval-detail__meta">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="名称">{{ dataset.name }}</el-descriptions-item>
        <el-descriptions-item label="类别">{{ dataset.category || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="dataset.status === 'active' ? 'success' : 'info'">
            {{ dataset.status === 'active' ? '活跃' : '归档' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="用例数">{{ dataset.case_count }}</el-descriptions-item>
        <el-descriptions-item label="Hash">
          <span class="eval-detail__hash">{{ dataset.dataset_hash ? dataset.dataset_hash.slice(0, 16) + '…' : '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ formatTime(dataset.updated_at) }}</el-descriptions-item>
        <el-descriptions-item v-if="dataset.description" label="描述" :span="3">{{ dataset.description }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="eval-detail__case-toolbar">
      <div class="eval-detail__case-title">用例列表 ({{ cases.length }})</div>
      <div class="eval-detail__case-actions">
        <el-input
          v-model="searchKeyword"
          clearable
          placeholder="搜索 case_id 或 question"
          class="eval-detail__search"
        />
        <el-button type="primary" @click="openCaseEditor(null)">新建用例</el-button>
      </div>
    </div>

    <el-table
      v-loading="casesLoading"
      :data="filteredCases"
      stripe
      class="eval-detail__table"
    >
      <el-table-column prop="case_id" label="Case ID" width="200" show-overflow-tooltip />
      <el-table-column prop="case_type" label="类型" width="100" />
      <el-table-column prop="category" label="类别" width="100" show-overflow-tooltip />
      <el-table-column prop="question" label="问题" min-width="280" show-overflow-tooltip />
      <el-table-column label="标签" width="160">
        <template #default="{ row }">
          <el-tag
            v-for="tag in parseTags(row.suite_tags)"
            :key="tag"
            size="small"
            effect="plain"
            class="eval-detail__tag"
          >
            {{ tag }}
          </el-tag>
          <span v-if="!parseTags(row.suite_tags).length">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="openCaseEditor(row)">编辑</el-button>
          <el-button text type="danger" @click="confirmDeleteCase(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty
      v-if="!casesLoading && !filteredCases.length"
      :description="searchKeyword ? '没有匹配的用例' : '暂无用例'"
      :image-size="100"
    />

    <el-dialog
      v-model="editorVisible"
      :title="editingCase ? `编辑用例 ${editingCase.case_id}` : '新建用例'"
      width="76%"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="case-editor">
        <div class="case-editor__hint">
          编辑完整的 V2 评测用例 JSON，需包含 <code>case_id</code>、<code>schema_version</code> 等必要字段。
        </div>
        <div class="case-editor__area">
          <TextCodeEditor
            v-model="editorContent"
            placeholder='{"schema_version": 2, "case_id": "...", "question": "...", "case_type": "single_turn", ...}'
          />
        </div>
        <div v-if="editorError" class="case-editor__error">{{ editorError }}</div>
      </div>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="editorSaving" @click="saveCase">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { dataagentApi } from '@/api/dataagent'
import { withAgentContext } from '@/router/agentContext'
import TextCodeEditor from '@/components/TextCodeEditor.vue'

const route = useRoute()
const router = useRouter()

const datasetId = computed(() => String(route.params.datasetId || ''))

const dataset = ref(null)
const cases = ref([])
const casesLoading = ref(false)
const searchKeyword = ref('')

const editorVisible = ref(false)
const editingCase = ref(null)
const editorContent = ref('')
const editorError = ref('')
const editorSaving = ref(false)

const parseTags = (tags) => {
  if (Array.isArray(tags)) return tags
  if (typeof tags === 'string') {
    try { return JSON.parse(tags) } catch { return [] }
  }
  return []
}

const filteredCases = computed(() => {
  const kw = String(searchKeyword.value || '').trim().toLowerCase()
  if (!kw) return cases.value
  return cases.value.filter((c) =>
    String(c.case_id || '').toLowerCase().includes(kw) ||
    String(c.question || '').toLowerCase().includes(kw)
  )
})

const formatTime = (val) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-'

const notifyError = (error, fallback) => {
  if (!error?.__odwNotified) ElMessage.error(error?.message || fallback)
}

const goBack = () => {
  router.push(withAgentContext({ name: 'EvaluationSets' }, route.query))
}

const loadDataset = async () => {
  try {
    dataset.value = await dataagentApi.getEvalDataset(datasetId.value)
  } catch (e) {
    dataset.value = null
    notifyError(e, '加载评测集失败')
  }
}

const loadCases = async () => {
  casesLoading.value = true
  try {
    cases.value = await dataagentApi.listEvalCases(datasetId.value)
  } catch (e) {
    cases.value = []
    notifyError(e, '加载用例失败')
  } finally {
    casesLoading.value = false
  }
}

const openCaseEditor = async (caseRow) => {
  editorError.value = ''
  editingCase.value = caseRow
  if (caseRow) {
    try {
      const detail = await dataagentApi.getEvalCase(datasetId.value, caseRow.case_id)
      const json = typeof detail.case_json === 'string' ? JSON.parse(detail.case_json) : detail.case_json
      editorContent.value = JSON.stringify(json, null, 2)
    } catch (e) {
      editorContent.value = ''
      notifyError(e, '加载用例详情失败')
    }
  } else {
    editorContent.value = JSON.stringify({
      schema_version: 2,
      case_id: '',
      case_type: 'single_turn',
      question: '',
      category: '',
      suite_tags: [],
      expected_semantics: { intent: '', entities: [], conditions: [] },
      expected_time: { range: '', granularity: '' },
      expected_tools: { min_calls: 1, max_calls: 3 },
      expected_sql: { keywords: [], tables: [] },
      expected_result: { row_count: null, columns: [] },
      expected_answer: { must_contain: [], must_not_contain: [] },
      limits: { max_turns: 5, timeout_seconds: 120 },
      scoring: {
        intent: 1,
        ontology_entity: 1,
        relation_scope: 1,
        sql_or_tool_call: 2,
        result_consistency: 2,
        reasoning: 2,
        answer_quality: 1,
        total_score: 10
      },
      veto_rules: { hallucination_fails: true }
    }, null, 2)
  }
  editorVisible.value = true
}

const saveCase = async () => {
  editorError.value = ''
  let parsed
  try {
    parsed = JSON.parse(editorContent.value)
  } catch {
    editorError.value = 'JSON 格式错误，请检查'
    return
  }
  const caseId = String(parsed.case_id || '').trim()
  if (!caseId) {
    editorError.value = '缺少 case_id 字段'
    return
  }
  editorSaving.value = true
  try {
    await dataagentApi.upsertEvalCase(datasetId.value, caseId, parsed)
    ElMessage.success('用例已保存')
    editorVisible.value = false
    await loadDataset()
    await loadCases()
  } catch (e) {
    notifyError(e, '保存用例失败')
  } finally {
    editorSaving.value = false
  }
}

const confirmDeleteCase = async (caseRow) => {
  try {
    await ElMessageBox.confirm(
      `确认删除用例「${caseRow.case_id}」？`,
      '删除用例',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await dataagentApi.deleteEvalCase(datasetId.value, caseRow.case_id)
    ElMessage.success('用例已删除')
    await loadDataset()
    await loadCases()
  } catch (e) {
    notifyError(e, '删除用例失败')
  }
}

watch(datasetId, () => {
  if (datasetId.value) {
    loadDataset()
    loadCases()
  }
})

onMounted(() => {
  if (datasetId.value) {
    loadDataset()
    loadCases()
  }
})
</script>

<style scoped>
.eval-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.eval-detail__topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
}

.eval-detail__back {
  border: 0;
  padding: 0;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}

.eval-detail__back:hover {
  color: #1d4ed8;
}

.eval-detail__slash {
  color: #94a3b8;
}

.eval-detail__name {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  min-width: 0;
  word-break: break-all;
}

.eval-detail__meta {
  background: #fff;
  border: 1px solid #dbe2ea;
  border-radius: 8px;
  padding: 16px;
}

.eval-detail__hash {
  font-family: monospace;
  font-size: 12px;
  color: #64748b;
}

.eval-detail__case-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.eval-detail__case-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.eval-detail__case-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.eval-detail__search {
  width: 240px;
}

.eval-detail__table {
  min-width: 0;
}

.eval-detail__tag {
  margin-right: 4px;
  margin-bottom: 2px;
}

.case-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.case-editor__hint {
  font-size: 13px;
  color: #64748b;
}

.case-editor__hint code {
  padding: 1px 4px;
  background: #f1f5f9;
  border-radius: 3px;
  font-size: 12px;
}

.case-editor__area {
  height: 420px;
}

.case-editor__error {
  color: #ef4444;
  font-size: 13px;
}

@media (max-width: 768px) {
  .eval-detail__case-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .eval-detail__case-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .eval-detail__search {
    width: 100%;
  }
}
</style>
