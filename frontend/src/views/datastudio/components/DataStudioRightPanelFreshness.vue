<template>
  <div class="meta-section meta-section-fill" v-loading="loading">
    <section class="section-block section-fill">
      <div class="section-header">
        <div class="section-title">数据新鲜度</div>
        <div class="section-actions">
          <el-button size="small" :disabled="!tableId || checking" :loading="checking" @click="checkNow">
            立即检查
          </el-button>
          <el-button size="small" type="primary" :disabled="!tableId" @click="openEdit">
            {{ resp && resp.configured ? '编辑契约' : '配置契约' }}
          </el-button>
        </div>
      </div>

      <el-scrollbar class="meta-scroll">
        <!-- 未配置引导 -->
        <el-empty
          v-if="resp && !resp.effective"
          description="该表未纳入数据新鲜度管理"
        >
          <div class="empty-hint">
            配置时间字段（或分区）与 warn/error 阈值后，可监控该表数据是否按时更新。
          </div>
          <el-button type="primary" @click="openEdit">配置契约</el-button>
        </el-empty>

        <template v-else-if="resp && resp.effective">
          <!-- 最近一次结果 -->
          <div class="section-header small">
            <span>最近检查</span>
            <el-tag v-if="latest" :type="statusType(latest.status)" size="small" effect="dark">
              {{ statusLabel(latest.status) }}
            </el-tag>
          </div>
          <el-descriptions v-if="latest" :column="2" border size="small" class="fresh-desc">
            <el-descriptions-item label="数据最后加载">{{ fmt(latest.maxLoadedAt) }}</el-descriptions-item>
            <el-descriptions-item label="检查时间">{{ fmt(latest.snapshottedAt) }}</el-descriptions-item>
            <el-descriptions-item label="数据年龄">{{ humanizeAge(latest.ageSeconds) }}</el-descriptions-item>
            <el-descriptions-item label="取值模式">{{ modeLabel(latest.mode) }}</el-descriptions-item>
            <el-descriptions-item v-if="latest.reason" label="原因" :span="2">
              {{ reasonLabel(latest.reason) }}
            </el-descriptions-item>
            <el-descriptions-item v-if="latest.errorMessage" label="错误" :span="2">
              {{ latest.errorMessage }}
            </el-descriptions-item>
          </el-descriptions>
          <div v-else class="fresh-none">尚无检查记录，点击「立即检查」运行一次。</div>

          <div class="section-divider"></div>

          <!-- 生效契约 -->
          <div class="section-header small"><span>生效契约</span></div>
          <el-descriptions :column="2" border size="small" class="fresh-desc">
            <el-descriptions-item :label="withSource('取值模式', 'mode')">
              {{ modeLabel(resp.effective.mode) }}
            </el-descriptions-item>
            <el-descriptions-item
              v-if="resp.effective.loadedAtField"
              :label="withSource('加载时间列', 'loadedAtField')"
            >
              {{ resp.effective.loadedAtField }}
            </el-descriptions-item>
            <el-descriptions-item
              v-if="resp.effective.partitionFormat"
              :label="withSource('分区格式', 'partitionFormat')"
            >
              {{ resp.effective.partitionFormat }}
            </el-descriptions-item>
            <el-descriptions-item
              v-if="resp.effective.warnAfter"
              :label="withSource('warn 阈值', 'warnAfter')"
            >
              {{ thresholdLabel(resp.effective.warnAfter) }}
            </el-descriptions-item>
            <el-descriptions-item
              v-if="resp.effective.errorAfter"
              :label="withSource('error 阈值', 'errorAfter')"
            >
              {{ thresholdLabel(resp.effective.errorAfter) }}
            </el-descriptions-item>
            <el-descriptions-item
              v-if="resp.effective.filterExpr"
              :label="withSource('过滤条件', 'filterExpr')"
              :span="2"
            >
              {{ resp.effective.filterExpr }}
            </el-descriptions-item>
            <el-descriptions-item
              v-if="resp.effective.loadedAtQuery"
              :label="withSource('自定义查询', 'loadedAtQuery')"
              :span="2"
            >
              <code class="fresh-code">{{ resp.effective.loadedAtQuery }}</code>
            </el-descriptions-item>
          </el-descriptions>

          <div class="section-divider"></div>

          <!-- 历史 -->
          <div class="section-header small"><span>检查历史</span></div>
          <el-table :data="history" border size="small" class="fresh-table">
            <el-table-column label="时间" min-width="150">
              <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small" effect="plain">
                  {{ statusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="数据年龄" min-width="120">
              <template #default="{ row }">{{ humanizeAge(row.ageSeconds) }}</template>
            </el-table-column>
            <el-table-column label="触发" width="90">
              <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </el-scrollbar>
    </section>

    <!-- 编辑抽屉 -->
    <el-drawer v-model="editing" title="数据新鲜度契约" size="440px" append-to-body>
      <el-form :model="form" label-width="110px" class="fresh-form">
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="form-hint">关闭表示显式退出新鲜度检查</span>
        </el-form-item>
        <el-form-item label="取值模式">
          <el-select v-model="form.mode" style="width: 100%">
            <el-option label="column - 加载时间列" value="column" />
            <el-option label="custom_sql - 自定义查询" value="custom_sql" />
            <el-option label="partition - 分区业务日期" value="partition" />
            <el-option label="metadata - 元数据更新时间" value="metadata" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="form.mode === 'column'" label="加载时间列">
          <el-input v-model="form.loadedAtField" placeholder="如 etl_time，须为该表真实列名" />
        </el-form-item>

        <el-form-item v-if="form.mode === 'custom_sql'" label="自定义查询">
          <el-input
            v-model="form.loadedAtQuery"
            type="textarea"
            :rows="3"
            placeholder="SELECT MAX(order_time) FROM db.tbl"
          />
        </el-form-item>

        <el-form-item v-if="form.mode === 'partition'" label="分区格式">
          <el-input v-model="form.partitionFormat" placeholder="如 yyyyMMdd（匹配分区名中的数字）" />
        </el-form-item>

        <el-alert
          v-if="form.mode === 'metadata'"
          type="warning"
          :closable="false"
          show-icon
          class="mode-note"
          title="metadata 模式只能发现「长期无写入」，发现不了「写入了旧数据」。有时间字段或分区时优先选其他模式。"
        />

        <el-form-item label="filter（可选）">
          <el-input v-model="form.filterExpr" placeholder="附加 WHERE 谓词，如 env = 'prod'" />
        </el-form-item>

        <el-form-item label="warn 阈值">
          <div class="threshold-row">
            <el-input-number v-model="form.warnAfterCount" :min="1" :controls="false" class="th-count" />
            <el-select v-model="form.warnAfterPeriod" class="th-period">
              <el-option label="分钟" value="minute" />
              <el-option label="小时" value="hour" />
              <el-option label="天" value="day" />
            </el-select>
          </div>
        </el-form-item>
        <el-form-item label="error 阈值">
          <div class="threshold-row">
            <el-input-number v-model="form.errorAfterCount" :min="1" :controls="false" class="th-count" />
            <el-select v-model="form.errorAfterPeriod" class="th-period">
              <el-option label="分钟" value="minute" />
              <el-option label="小时" value="hour" />
              <el-option label="天" value="day" />
            </el-select>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="drawer-footer">
          <el-button v-if="resp && resp.configured" type="danger" plain @click="removeConfig">删除契约</el-button>
          <span class="footer-spacer"></span>
          <el-button @click="editing = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, inject, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tableApi } from '@/api/table'

const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelFreshness 需要 dataStudioCtx')
}

const { activeTab, tabStates, formatDateTime } = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const table = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return (tabStates[id] || {}).table || null
})
const tableId = computed(() => table.value?.id || null)

const loading = ref(false)
const checking = ref(false)
const saving = ref(false)
const resp = ref(null)
const history = ref([])
const editing = ref(false)

const latest = computed(() => resp.value?.latestResult || null)

const form = reactive({
  mode: 'column',
  loadedAtField: '',
  loadedAtQuery: '',
  partitionFormat: '',
  filterExpr: '',
  warnAfterCount: 2,
  warnAfterPeriod: 'hour',
  errorAfterCount: 4,
  errorAfterPeriod: 'hour',
  enabled: true,
})

const fmt = (value) => (value ? formatDateTime(value) : '-')

const STATUS = {
  pass: { label: '正常', type: 'success' },
  warn: { label: '预警', type: 'warning' },
  error: { label: '超时', type: 'danger' },
  runtime_error: { label: '检查失败', type: 'info' },
}
const statusLabel = (s) => STATUS[s]?.label || s || '-'
const statusType = (s) => STATUS[s]?.type || 'info'

const MODE = {
  column: 'column - 加载时间列',
  custom_sql: 'custom_sql - 自定义查询',
  partition: 'partition - 分区业务日期',
  metadata: 'metadata - 元数据更新时间',
}
const modeLabel = (m) => MODE[m] || m || '-'

const TRIGGER = { manual: '手动', schedule: '定时', inspection: '巡检', workflow: '工作流' }
const triggerLabel = (t) => TRIGGER[t] || t || '-'

const reasonLabel = (r) => (r === 'never_loaded' ? '该表从未产出过数据' : r)

const SOURCE = { table: '表级', rule_default: '规则默认' }
const withSource = (label, field) => {
  const src = resp.value?.effective?.fieldSources?.[field]
  if (src && src !== 'table') {
    return `${label}（${SOURCE[src] || src}）`
  }
  return label
}

const thresholdLabel = (t) => {
  if (!t) return '-'
  const period = { minute: '分钟', hour: '小时', day: '天' }[t.period] || t.period
  return `${t.count} ${period}`
}

const humanizeAge = (seconds) => {
  if (seconds === null || seconds === undefined) return '-'
  let s = Math.max(0, Number(seconds))
  const d = Math.floor(s / 86400)
  s -= d * 86400
  const h = Math.floor(s / 3600)
  s -= h * 3600
  const m = Math.floor(s / 60)
  if (d > 0) return h > 0 ? `${d} 天 ${h} 小时` : `${d} 天`
  if (h > 0) return m > 0 ? `${h} 小时 ${m} 分钟` : `${h} 小时`
  return `${m} 分钟`
}

const loadFreshness = async () => {
  if (!tableId.value) {
    resp.value = null
    history.value = []
    return
  }
  loading.value = true
  try {
    resp.value = await tableApi.getFreshness(tableId.value)
    await loadHistory()
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    loading.value = false
  }
}

const loadHistory = async () => {
  if (!tableId.value) return
  try {
    history.value = (await tableApi.freshnessHistory(tableId.value, 20)) || []
  } catch (e) {
    history.value = []
  }
}

const checkNow = async () => {
  if (!tableId.value) return
  checking.value = true
  try {
    await tableApi.checkFreshness(tableId.value)
    ElMessage.success('检查完成')
    await loadFreshness()
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    checking.value = false
  }
}

const openEdit = () => {
  const cfg = resp.value?.config
  if (cfg) {
    form.mode = cfg.mode || 'column'
    form.loadedAtField = cfg.loadedAtField || ''
    form.loadedAtQuery = cfg.loadedAtQuery || ''
    form.partitionFormat = cfg.partitionFormat || ''
    form.filterExpr = cfg.filterExpr || ''
    form.warnAfterCount = cfg.warnAfterCount ?? 2
    form.warnAfterPeriod = cfg.warnAfterPeriod || 'hour'
    form.errorAfterCount = cfg.errorAfterCount ?? 4
    form.errorAfterPeriod = cfg.errorAfterPeriod || 'hour'
    form.enabled = cfg.enabled !== false
  }
  editing.value = true
}

const save = async () => {
  if (!tableId.value) return
  saving.value = true
  try {
    await tableApi.saveFreshness(tableId.value, { ...form })
    ElMessage.success('保存成功')
    editing.value = false
    await loadFreshness()
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    saving.value = false
  }
}

const removeConfig = async () => {
  try {
    await ElMessageBox.confirm('删除后该表将回落到默认继承（可能不再检查）。确认删除契约？', '提示', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await tableApi.deleteFreshness(tableId.value)
    ElMessage.success('已删除')
    editing.value = false
    await loadFreshness()
  } catch (e) {
    // 拦截器已提示错误
  }
}

watch(tableId, () => loadFreshness(), { immediate: true })
</script>

<style scoped>
.empty-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 12px;
  max-width: 320px;
}
.fresh-desc {
  margin-bottom: 4px;
}
.fresh-none {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  padding: 4px 0 8px;
}
.fresh-code {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.section-divider {
  height: 1px;
  background: var(--el-border-color-lighter);
  margin: 14px 0;
}
.section-header.small {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  margin-bottom: 8px;
}
.mode-note {
  margin-bottom: 14px;
}
.threshold-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.th-count {
  width: 120px;
}
.th-period {
  flex: 1;
}
.form-hint {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.drawer-footer {
  display: flex;
  align-items: center;
  width: 100%;
}
.footer-spacer {
  flex: 1;
}
</style>
