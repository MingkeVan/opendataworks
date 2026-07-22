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
        <el-table-column label="七维分" min-width="220">
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
        <el-table-column label="失败归因" min-width="180">
          <template #default="{ row }">
            <template v-if="(row.failure_attribution || []).length">
              <el-tag
                v-for="tag in row.failure_attribution"
                :key="tag"
                size="small"
                type="danger"
                effect="plain"
                class="run-detail__attr-tag"
              >
                {{ tag }}
              </el-tag>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="judge_comment" label="评判意见" min-width="220" show-overflow-tooltip />
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

        <div class="case-detail-dialog__section">
          <div class="run-detail__section-title">通过判定</div>
          <div class="case-detail-dialog__checks">
            <el-tag
              v-for="check in passChecks"
              :key="check.label"
              size="default"
              :type="check.ok ? 'success' : 'danger'"
              effect="plain"
            >
              {{ check.ok ? '✓' : '✗' }} {{ check.label }}
            </el-tag>
          </div>
        </div>

        <div v-if="comparisonRows.length" class="case-detail-dialog__section">
          <div class="run-detail__section-title">预期 vs 实际</div>
          <div v-if="caseQuestion" class="case-detail-dialog__qa">
            <div class="case-detail-dialog__qa-label">问题</div>
            <div class="case-detail-dialog__text">{{ caseQuestion }}</div>
          </div>
          <el-table :data="comparisonRows" size="small" stripe>
            <el-table-column prop="group" label="对照项" width="110" />
            <el-table-column label="预期" min-width="260">
              <template #default="{ row }">
                <div class="case-detail-dialog__cell">{{ row.expected || '-' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="实际" min-width="260">
              <template #default="{ row }">
                <div class="case-detail-dialog__cell">{{ row.actual || '-' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.ok === true" size="small" type="success">符合</el-tag>
                <el-tag v-else-if="row.ok === false" size="small" type="danger">不符合</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!expectedCaseDef" class="case-detail-dialog__muted case-detail-dialog__note">
            未能关联到评测集用例定义（运行未关联数据集或用例已删除），预期列仅显示运行内可得信息。
          </div>
        </div>

        <div v-if="judgeInfo.comment" class="case-detail-dialog__section">
          <div class="run-detail__section-title">评判意见</div>
          <div class="case-detail-dialog__text">{{ judgeInfo.comment }}</div>
        </div>

        <div v-if="failureTags.length || vetoTags.length" class="case-detail-dialog__section">
          <div class="run-detail__section-title">失败归因</div>
          <div class="case-detail-dialog__checks">
            <el-tag
              v-for="tag in failureTags"
              :key="`attr-${tag}`"
              size="small"
              type="danger"
              effect="plain"
            >
              {{ tag }}
            </el-tag>
            <el-tag
              v-for="tag in vetoTags"
              :key="`veto-${tag}`"
              size="small"
              type="danger"
            >
              一票否决: {{ tag }}
            </el-tag>
          </div>
        </div>

        <div v-if="caseFinalAnswer" class="case-detail-dialog__section">
          <div class="run-detail__section-title">最终回答（全文）</div>
          <div class="case-detail-dialog__text case-detail-dialog__text--scroll">{{ caseFinalAnswer }}</div>
        </div>

        <div v-if="caseDetail.dimension_scores_json" class="case-detail-dialog__section">
          <div class="run-detail__section-title">维度评分</div>
          <el-table :data="dimensionList(caseDetail.dimension_scores_json, judgeInfo.dimension_rationales)" size="small" stripe>
            <el-table-column prop="name" label="维度" width="120" />
            <el-table-column prop="score" label="得分" width="70" align="center" />
            <el-table-column prop="weight" label="满分" width="70" align="center" />
            <el-table-column prop="rationale" label="理由" min-width="320">
              <template #default="{ row }">
                <span v-if="row.rationale">{{ row.rationale }}</span>
                <span v-else class="case-detail-dialog__muted">-（本次运行的评判版本未输出维度理由）</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="hardGates.length" class="case-detail-dialog__section">
          <div class="run-detail__section-title">硬门槛检查</div>
          <div class="case-detail-dialog__checks">
            <el-tag
              v-for="gate in hardGates"
              :key="gate.key"
              size="small"
              :type="gate.ok ? 'success' : 'danger'"
              effect="plain"
            >
              {{ gate.ok ? '✓' : '✗' }} {{ gate.label }}
            </el-tag>
          </div>
        </div>

        <div v-if="ruleChecks.length" class="case-detail-dialog__section">
          <div class="run-detail__section-title">自动规则检查</div>
          <el-table :data="ruleChecks" size="small" stripe>
            <el-table-column prop="name" label="检查项" width="140" />
            <el-table-column label="结果" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="!row.applicable" size="small" type="info">不适用</el-tag>
                <el-tag v-else size="small" :type="row.passed ? 'success' : 'danger'">
                  {{ row.passed ? '通过' : '未通过' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="240">
              <template #default="{ row }">{{ row.reason || '-' }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="caseErrors.length" class="case-detail-dialog__section">
          <div class="run-detail__section-title">运行错误</div>
          <div class="case-detail-dialog__text case-detail-dialog__text--error">
            <div v-for="(err, idx) in caseErrors" :key="idx">{{ err }}</div>
          </div>
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

const DIMENSION_WEIGHTS = {
  intent: 1,
  ontology_entity: 1,
  relation_scope: 1,
  sql_or_tool_call: 2,
  result_consistency: 2,
  reasoning: 2,
  answer_quality: 1
}

const dimensionList = (dimJson, rationales) => {
  if (!dimJson || typeof dimJson !== 'object') return []
  return Object.entries(dimJson).map(([key, val]) => ({
    name: DIMENSION_NAMES[key] || key,
    score: typeof val === 'object' ? (val.score ?? '-') : val,
    weight: DIMENSION_WEIGHTS[key] ?? (typeof val === 'object' ? (val.weight ?? '-') : '-'),
    rationale: (rationales && typeof rationales === 'object' && rationales[key])
      || (typeof val === 'object' ? (val.rationale || '') : '')
  }))
}

const caseJson = computed(() => {
  const cj = caseDetail.value?.case_json
  if (!cj) return null
  if (typeof cj === 'string') {
    try { return JSON.parse(cj) } catch { return null }
  }
  return typeof cj === 'object' ? cj : null
})

const judgeInfo = computed(() => {
  const j = caseJson.value?.judge
  return j && typeof j === 'object' ? j : {}
})

const ruleCheckInfo = computed(() => {
  const r = caseJson.value?.auto_rule_check
  return r && typeof r === 'object' ? r : {}
})

const caseQuestion = computed(() => String(caseJson.value?.question || ''))
const caseFinalAnswer = computed(() => String(caseJson.value?.final_answer || ''))
const caseErrors = computed(() => (Array.isArray(caseJson.value?.errors) ? caseJson.value.errors.map(String) : []))
const vetoTags = computed(() => (Array.isArray(caseJson.value?.veto_rules_triggered) ? caseJson.value.veto_rules_triggered.map(String) : []))
const failureTags = computed(() => (Array.isArray(judgeInfo.value.failure_attribution) ? judgeInfo.value.failure_attribution.map(String) : []))

const SUCCESS_STATUSES = ['success', 'completed']

const passChecks = computed(() => {
  if (!caseJson.value) return []
  const cj = caseJson.value
  const judge = judgeInfo.value
  const score = Number(judge.score ?? cj.judge?.score ?? 0)
  return [
    { label: '任务执行成功', ok: SUCCESS_STATUSES.includes(String(cj.task_status || '').toLowerCase()) },
    { label: '无运行错误', ok: !(Array.isArray(cj.errors) && cj.errors.length) },
    { label: '自动规则检查通过', ok: ruleCheckInfo.value.passed !== false },
    { label: '评分 ≥ 8', ok: score >= 8 },
    { label: '评判有效', ok: !judge.judge_failed },
    { label: '无幻觉', ok: !judge.hallucination },
    { label: '无一票否决', ok: !vetoTags.value.length }
  ]
})

const HARD_GATE_LABELS = {
  'sql_execution_and_口径': 'SQL 执行与口径',
  required_tool_execution: '必需工具执行',
  no_hallucination: '无幻觉',
  no_veto: '无一票否决',
  reference_data_accuracy: '参考数据准确',
  task_completed: '任务完成'
}

const hardGates = computed(() => {
  const gates = ruleCheckInfo.value.hard_gates
  if (!gates || typeof gates !== 'object') return []
  return Object.entries(gates).map(([key, val]) => ({
    key,
    label: HARD_GATE_LABELS[key] || key,
    ok: Boolean(val)
  }))
})

const ruleChecks = computed(() => {
  if (!caseJson.value) return []
  const rows = []
  const sources = [
    { name: '时间维度', data: ruleCheckInfo.value.time_dimension },
    { name: '结果一致性', data: ruleCheckInfo.value.result_consistency },
    { name: '参考数据准确性', data: caseJson.value.reference_data_accuracy }
  ]
  for (const { name, data } of sources) {
    if (data && typeof data === 'object') {
      rows.push({
        name,
        applicable: Boolean(data.applicable),
        passed: Boolean(data.passed),
        reason: String(data.reason || data.detail || '')
      })
    }
  }
  return rows
})

const expectedCaseDef = ref(null)

const joinList = (v, sep = '、') => (Array.isArray(v) && v.length ? v.map(String).join(sep) : '')
const truncate = (s, n) => {
  const text = String(s || '')
  return text.length > n ? `${text.slice(0, n)}…` : text
}

const comparisonRows = computed(() => {
  const cj = caseJson.value
  if (!cj) return []
  const exp = expectedCaseDef.value || {}
  const gates = ruleCheckInfo.value.hard_gates || {}
  const dims = judgeInfo.value.dimension_scores || {}
  const rows = []

  const sem = exp.expected_semantics || {}
  const semExpected = [
    sem.intent ? `意图: ${sem.intent}` : '',
    joinList(sem.ontology_object_ids) ? `本体对象: ${joinList(sem.ontology_object_ids)}` : '',
    joinList(sem.business_rules, '\n') ? `业务规则:\n${joinList(sem.business_rules, '\n')}` : ''
  ].filter(Boolean).join('\n')
  if (semExpected || dims.intent !== undefined) {
    rows.push({
      group: '语义/意图',
      expected: semExpected,
      actual: `意图 ${dims.intent ?? '-'}/1，实体 ${dims.ontology_entity ?? '-'}/1，关系 ${dims.relation_scope ?? '-'}/1`,
      ok: dims.intent !== undefined
        ? Number(dims.intent) >= 1 && Number(dims.ontology_entity) >= 1
        : null
    })
  }

  const tools = exp.expected_tools || {}
  const toolExpected = [
    joinList(tools.required_steps, ' → ') ? `必需步骤: ${joinList(tools.required_steps, ' → ')}` : '',
    tools.min_calls !== undefined ? `调用次数: ${tools.min_calls ?? 0} - ${tools.max_calls ?? '?'}` : ''
  ].filter(Boolean).join('\n')
  const toolActual = [
    joinList(cj.tool_names) ? `工具: ${joinList(cj.tool_names)}` : '未调用工具',
    `调用 ${cj.tool_call_count ?? 0} 次`
  ].join('\n')
  rows.push({
    group: '工具调用',
    expected: toolExpected,
    actual: toolActual,
    ok: 'required_tool_execution' in gates ? Boolean(gates.required_tool_execution) : null
  })

  const sql = exp.expected_sql || {}
  const sqlExpected = [
    sql.execution_required !== undefined ? `必须执行 SQL: ${sql.execution_required ? '是' : '否'}` : '',
    joinList(sql.tables) ? `表: ${joinList(sql.tables)}` : '',
    joinList(sql.predicates, '；') ? `条件: ${joinList(sql.predicates, '；')}` : '',
    joinList(sql.aggregations) ? `聚合: ${joinList(sql.aggregations)}` : ''
  ].filter(Boolean).join('\n')
  const sqlOutputs = Array.isArray(cj.sql_outputs) ? cj.sql_outputs : []
  const sqlActual = [
    `执行 ${cj.sql_execution_count ?? 0} 条 SQL`,
    sqlOutputs.length ? truncate(sqlOutputs.map((s) => String(s)).join('\n---\n'), 600) : ''
  ].filter(Boolean).join('\n')
  rows.push({
    group: 'SQL 执行',
    expected: sqlExpected,
    actual: sqlActual,
    ok: 'sql_execution_and_口径' in gates ? Boolean(gates['sql_execution_and_口径']) : null
  })

  const result = exp.expected_result || {}
  const refQuery = result.reference_query || {}
  const refActual = cj.reference_data_accuracy || {}
  const resultExpected = [
    joinList(result.required_columns) ? `必需列: ${joinList(result.required_columns)}` : '',
    refQuery.sql ? `参考 SQL: ${truncate(refQuery.sql, 200)}` : '',
    result.allow_empty !== undefined ? `允许空结果: ${result.allow_empty ? '是' : '否'}` : ''
  ].filter(Boolean).join('\n')
  if (resultExpected || refActual.applicable) {
    rows.push({
      group: '数据结果',
      expected: resultExpected,
      actual: refActual.applicable
        ? `参考数据对比: ${refActual.passed ? '一致' : '不一致'}${refActual.reason ? `（${refActual.reason}）` : ''}`
        : '无参考数据对比',
      ok: refActual.applicable ? Boolean(refActual.passed) : null
    })
  }

  const time = exp.expected_time || {}
  const timeCheck = ruleCheckInfo.value.time_dimension || {}
  if (time.required || timeCheck.applicable) {
    rows.push({
      group: '时间口径',
      expected: [
        time.field ? `字段: ${time.field}` : '',
        time.grain ? `粒度: ${time.grain}` : '',
        time.range && Object.keys(time.range).length ? `范围: ${JSON.stringify(time.range)}` : ''
      ].filter(Boolean).join('\n'),
      actual: timeCheck.applicable
        ? `时间维度检查: ${timeCheck.passed ? '通过' : '未通过'}${timeCheck.reason ? `（${timeCheck.reason}）` : ''}`
        : '不适用',
      ok: timeCheck.applicable ? Boolean(timeCheck.passed) : null
    })
  }

  const answer = exp.expected_answer || {}
  const answerExpected = [
    joinList(answer.required_points, '\n') ? `必答要点:\n${joinList(answer.required_points, '\n')}` : '',
    joinList(answer.boundary_notes, '\n') ? `边界说明:\n${joinList(answer.boundary_notes, '\n')}` : '',
    joinList(answer.units) ? `单位: ${joinList(answer.units)}` : ''
  ].filter(Boolean).join('\n')
  rows.push({
    group: '回答要点',
    expected: answerExpected,
    actual: truncate(caseFinalAnswer.value, 600) || '无最终回答',
    ok: dims.answer_quality !== undefined ? Number(dims.answer_quality) >= 1 : null
  })

  return rows.filter((r) => r.expected || r.actual)
})

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
    expectedCaseDef.value = null
    const detailPromise = dataagentApi.getEvalRunCase(runId.value, row.case_id)
    if (run.value?.dataset_id) {
      dataagentApi.getEvalCase(run.value.dataset_id, row.case_id)
        .then((def) => {
          const json = typeof def.case_json === 'string' ? JSON.parse(def.case_json) : def.case_json
          if (json && typeof json === 'object') expectedCaseDef.value = json
        })
        .catch(() => { /* run 未关联数据集或用例已删除时静默降级 */ })
    }
    caseDetail.value = await detailPromise
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

.case-detail-dialog__section {
  margin-top: 4px;
}

.case-detail-dialog__checks {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.case-detail-dialog__text {
  font-size: 13px;
  line-height: 1.7;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f8fafc;
  border: 1px solid #eef2f6;
  border-radius: 6px;
  padding: 10px 12px;
}

.case-detail-dialog__text--scroll {
  max-height: 220px;
  overflow-y: auto;
}

.case-detail-dialog__text--error {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}

.case-detail-dialog__qa {
  margin-bottom: 10px;
}

.case-detail-dialog__qa-label {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 4px;
}

.case-detail-dialog__muted {
  color: #94a3b8;
}

.case-detail-dialog__cell {
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
}

.case-detail-dialog__note {
  margin-top: 8px;
  font-size: 12px;
}

.case-detail-dialog__json {
  margin-top: 8px;
}

.run-detail__attr-tag {
  margin-right: 4px;
  margin-bottom: 2px;
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
