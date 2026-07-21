<template>
  <div class="run-detail">
    <div class="run-detail__topbar">
      <button type="button" class="run-detail__back" @click="goBack">&larr; 评测结果</button>
      <span class="run-detail__slash">/</span>
      <span class="run-detail__name">{{ run?.run_label || runId }}</span>
    </div>

    <div v-if="run" class="run-detail__summary">
      <div class="run-detail__cards">
        <div class="metric-card">
          <div class="metric-card__label">平均分</div>
          <div class="metric-card__value">{{ formatScore(run.average_score) }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-card__label">通过</div>
          <div class="metric-card__value" :class="run.passed ? 'metric-card__value--pass' : 'metric-card__value--fail'">
            {{ run.passed ? '通过' : '未通过' }}
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-card__label">用例通过</div>
          <div class="metric-card__value">{{ run.passed_cases }} / {{ run.total_cases }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-card__label">幻觉用例</div>
          <div class="metric-card__value">{{ run.veto_count || 0 }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-card__label">评判失败</div>
          <div class="metric-card__value">{{ run.judge_failed_count || 0 }}</div>
        </div>
      </div>

      <el-descriptions :column="3" border size="small" class="run-detail__info">
        <el-descriptions-item label="引擎">{{ run.evaluation_engine }}</el-descriptions-item>
        <el-descriptions-item label="引擎版本">{{ run.engine_version || '-' }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ run.model || '-' }}</el-descriptions-item>
        <el-descriptions-item label="评判模型">{{ run.judge_model || '-' }}</el-descriptions-item>
        <el-descriptions-item label="评判 Prompt">{{ run.judge_prompt_version || '-' }}</el-descriptions-item>
        <el-descriptions-item label="指标语义版本">{{ run.metric_semantics_version || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ run.run_status || '-' }}</el-descriptions-item>
        <el-descriptions-item label="建议">{{ run.recommendation || '-' }}</el-descriptions-item>
        <el-descriptions-item label="并发">{{ run.concurrency || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数据集 ID">{{ run.dataset_id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数据集 Hash">
          <span class="run-detail__hash">{{ run.dataset_hash ? run.dataset_hash.slice(0, 16) + '…' : '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="运行时间">{{ formatTime(run.started_at) }}</el-descriptions-item>
        <el-descriptions-item label="入库时间">{{ formatTime(run.ingested_at) }}</el-descriptions-item>
        <el-descriptions-item label="Run ID" :span="2">
          <span class="run-detail__hash">{{ run.run_id }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="metricsEntries.length" class="run-detail__metrics-section">
        <div class="run-detail__section-title">指标详情</div>
        <el-table :data="metricsEntries" size="small" stripe>
          <el-table-column prop="key" label="指标" min-width="200" />
          <el-table-column label="值" min-width="200">
            <template #default="{ row }">
              <template v-if="row.isRatio">
                {{ formatScore(row.value) }} ({{ row.numerator }}/{{ row.denominator }})
              </template>
              <template v-else>{{ row.display }}</template>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="run-detail__case-section">
      <div class="run-detail__case-header">
        <div class="run-detail__section-title">用例结果 ({{ runCases.length }})</div>
        <el-input
          v-model="caseSearch"
          clearable
          placeholder="搜索 case_id"
          class="run-detail__search"
        />
      </div>

      <el-table
        v-loading="casesLoading"
        :data="filteredCases"
        stripe
        class="run-detail__table"
      >
        <el-table-column prop="case_id" label="Case ID" min-width="180" show-overflow-tooltip />
        <el-table-column prop="category" label="类别" width="100" />
        <el-table-column label="得分" width="80" align="center">
          <template #default="{ row }">{{ formatScore(row.score) }}</template>
        </el-table-column>
        <el-table-column label="通过" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.case_passed ? 'success' : 'danger'">
              {{ row.case_passed ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="task_status" label="状态" width="100" />
        <el-table-column label="幻觉" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.hallucination" size="small" type="danger">是</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="七维分" min-width="260">
          <template #default="{ row }">
            <template v-if="row.dimension_scores_json">
              <span
                v-for="(dim, idx) in dimensionList(row.dimension_scores_json)"
                :key="idx"
                class="run-detail__dim"
              >
                {{ dim.name }}:{{ dim.score }}
              </span>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openCaseDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="caseDialogVisible"
      :title="`用例详情 - ${caseDetail?.case_id || ''}`"
      width="76%"
      :close-on-click-modal="true"
      destroy-on-close
    >
      <div v-if="caseDetail" class="case-detail-dialog">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="Case ID">{{ caseDetail.case_id }}</el-descriptions-item>
          <el-descriptions-item label="得分">{{ formatScore(caseDetail.score) }}</el-descriptions-item>
          <el-descriptions-item label="通过">
            <el-tag size="small" :type="caseDetail.case_passed ? 'success' : 'danger'">
              {{ caseDetail.case_passed ? '是' : '否' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ caseDetail.task_status || '-' }}</el-descriptions-item>
          <el-descriptions-item label="幻觉">{{ caseDetail.hallucination ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ caseDetail.category || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="caseDetail.dimension_scores_json" class="case-detail-dialog__dims">
          <div class="run-detail__section-title">维度评分</div>
          <el-table :data="dimensionList(caseDetail.dimension_scores_json)" size="small" stripe>
            <el-table-column prop="name" label="维度" />
            <el-table-column prop="score" label="得分" width="80" align="center" />
            <el-table-column prop="weight" label="权重" width="80" align="center" />
            <el-table-column prop="rationale" label="理由" min-width="300" show-overflow-tooltip />
          </el-table>
        </div>

        <div v-if="caseDetail.case_json" class="case-detail-dialog__json">
          <div class="run-detail__section-title">完整用例数据</div>
          <div class="case-detail-dialog__editor">
            <TextCodeEditor
              :model-value="formatJson(caseDetail.case_json)"
              read-only
            />
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { dataagentApi } from '@/api/dataagent'
import { withAgentContext } from '@/router/agentContext'
import TextCodeEditor from '@/components/TextCodeEditor.vue'

const route = useRoute()
const router = useRouter()

const runId = computed(() => String(route.params.runId || ''))

const run = ref(null)
const runCases = ref([])
const casesLoading = ref(false)
const caseSearch = ref('')
const caseDialogVisible = ref(false)
const caseDetail = ref(null)

const formatTime = (val) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-'
const formatScore = (val) => typeof val === 'number' ? val.toFixed(2) : '-'

const formatJson = (val) => {
  if (typeof val === 'string') {
    try { return JSON.stringify(JSON.parse(val), null, 2) } catch { return val }
  }
  if (val && typeof val === 'object') return JSON.stringify(val, null, 2)
  return String(val || '')
}

const notifyError = (error, fallback) => {
  if (!error?.__odwNotified) ElMessage.error(error?.message || fallback)
}

const goBack = () => {
  router.push(withAgentContext({ name: 'EvaluationResults' }, route.query))
}

const metricsEntries = computed(() => {
  if (!run.value?.metrics_json || typeof run.value.metrics_json !== 'object') return []
  const entries = []
  const walk = (obj, prefix) => {
    for (const [k, v] of Object.entries(obj)) {
      const key = prefix ? `${prefix}.${k}` : k
      if (v && typeof v === 'object' && !Array.isArray(v)) {
        if ('value' in v && 'numerator' in v && 'denominator' in v) {
          entries.push({ key, value: v.value, numerator: v.numerator, denominator: v.denominator, isRatio: true, display: '' })
        } else {
          walk(v, key)
        }
      } else {
        entries.push({ key, value: v, isRatio: false, display: Array.isArray(v) ? JSON.stringify(v) : String(v ?? '-') })
      }
    }
  }
  walk(run.value.metrics_json, '')
  return entries
})

const DIMENSION_NAMES = {
  intent: '意图理解',
  ontology_entity: '实体识别',
  relation_scope: '关系范围',
  sql_or_tool_call: 'SQL/工具',
  result_consistency: '结果一致',
  reasoning: '推理过程',
  answer_quality: '回答质量'
}

const dimensionList = (dimJson) => {
  if (!dimJson || typeof dimJson !== 'object') return []
  return Object.entries(dimJson).map(([key, val]) => ({
    name: DIMENSION_NAMES[key] || key,
    score: typeof val === 'object' ? (val.score ?? '-') : val,
    weight: typeof val === 'object' ? (val.weight ?? '-') : '-',
    rationale: typeof val === 'object' ? (val.rationale || '') : ''
  }))
}

const filteredCases = computed(() => {
  const kw = String(caseSearch.value || '').trim().toLowerCase()
  if (!kw) return runCases.value
  return runCases.value.filter((c) => String(c.case_id || '').toLowerCase().includes(kw))
})

const loadRun = async () => {
  try {
    run.value = await dataagentApi.getEvalRun(runId.value)
  } catch (e) {
    run.value = null
    notifyError(e, '加载运行详情失败')
  }
}

const loadRunCases = async () => {
  casesLoading.value = true
  try {
    runCases.value = await dataagentApi.listEvalRunCases(runId.value)
  } catch (e) {
    runCases.value = []
    notifyError(e, '加载用例结果失败')
  } finally {
    casesLoading.value = false
  }
}

const openCaseDetail = async (row) => {
  try {
    caseDetail.value = await dataagentApi.getEvalRunCase(runId.value, row.case_id)
    caseDialogVisible.value = true
  } catch (e) {
    notifyError(e, '加载用例详情失败')
  }
}

watch(runId, () => {
  if (runId.value) {
    loadRun()
    loadRunCases()
  }
})

onMounted(() => {
  if (runId.value) {
    loadRun()
    loadRunCases()
  }
})
</script>

<style scoped>
.run-detail {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}

.run-detail__topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
}

.run-detail__back {
  border: 0;
  padding: 0;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}

.run-detail__back:hover {
  color: #1d4ed8;
}

.run-detail__slash {
  color: #94a3b8;
}

.run-detail__name {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  min-width: 0;
  word-break: break-all;
}

.run-detail__summary {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.run-detail__cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 12px;
}

.metric-card {
  background: #fff;
  border: 1px solid #dbe2ea;
  border-radius: 8px;
  padding: 16px;
  text-align: center;
}

.metric-card__label {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 8px;
}

.metric-card__value {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}

.metric-card__value--pass {
  color: #22c55e;
}

.metric-card__value--fail {
  color: #ef4444;
}

.run-detail__info {
  background: #fff;
  border-radius: 8px;
}

.run-detail__hash {
  font-family: monospace;
  font-size: 12px;
  color: #64748b;
  word-break: break-all;
}

.run-detail__section-title {
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 8px;
}

.run-detail__metrics-section {
  background: #fff;
  border: 1px solid #dbe2ea;
  border-radius: 8px;
  padding: 16px;
}

.run-detail__case-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.run-detail__case-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.run-detail__search {
  width: 220px;
}

.run-detail__table {
  min-width: 0;
}

.run-detail__dim {
  display: inline-block;
  margin-right: 8px;
  font-size: 12px;
  color: #334155;
}

.case-detail-dialog {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.case-detail-dialog__dims {
  margin-top: 8px;
}

.case-detail-dialog__json {
  margin-top: 8px;
}

.case-detail-dialog__editor {
  height: 360px;
}

@media (max-width: 768px) {
  .run-detail__cards {
    grid-template-columns: repeat(2, 1fr);
  }

  .run-detail__case-header {
    flex-direction: column;
    align-items: stretch;
  }

  .run-detail__search {
    width: 100%;
  }
}
</style>
