<template>
  <div class="meta-section meta-section-fill" v-loading="loading">
    <section class="section-block section-fill">
      <div class="section-header">
        <div class="fresh-title-row">
          <div class="section-title">数据新鲜度</div>
          <!-- 右侧：最近一次检查状态 -->
          <el-tag v-if="latest" :type="statusType(latest.status)" size="small" effect="dark">
            {{ statusLabel(latest.status) }}
          </el-tag>
          <span v-else class="fresh-muted">未检查</span>
        </div>
        <div class="section-actions">
          <el-button size="small" :disabled="!tableId || checking" :loading="checking" @click="checkNow">
            立即检查
          </el-button>
          <el-button size="small" :disabled="!tableId" @click="openHistory">历史</el-button>
        </div>
      </div>

      <el-scrollbar class="meta-scroll">
        <!-- 最近一次结果：一行紧凑显示 -->
        <div v-if="latest" class="fresh-latest">
          <span>最后加载 {{ fmt(latest.maxLoadedAt) }}</span>
          <span class="dot">·</span>
          <span>{{ latest.reason === 'never_loaded' ? '从未产出' : ('数据年龄 ' + humanizeAge(latest.ageSeconds)) }}</span>
          <span class="dot">·</span>
          <span>检查于 {{ fmt(latest.createdAt || latest.snapshottedAt) }}</span>
        </div>
        <div v-else class="fresh-muted fresh-latest">尚无检查记录，配置契约后点「立即检查」。</div>

        <!-- 行内契约编辑：选取值方式 → 补一点 → 阈值 → 保存 -->
        <el-form class="fresh-form" :inline="true" size="small" @submit.prevent>
          <el-form-item label="取值">
            <el-select v-model="form.mode" style="width: 132px">
              <el-option label="字段" value="column" />
              <el-option label="自定义" value="custom_sql" />
              <el-option label="分区" value="partition" />
              <el-option label="元数据" value="metadata" />
            </el-select>
          </el-form-item>

          <el-form-item v-if="form.mode === 'column'" label="列">
            <el-input v-model="form.loadedAtField" placeholder="如 etl_time" style="width: 160px" />
          </el-form-item>
          <el-form-item v-else-if="form.mode === 'custom_sql'" label="查询">
            <el-input v-model="form.loadedAtQuery" placeholder="SELECT MAX(order_time) FROM db.tbl" style="width: 260px" />
          </el-form-item>
          <el-form-item v-else-if="form.mode === 'partition'" label="格式">
            <el-input v-model="form.partitionFormat" placeholder="如 yyyyMMdd" style="width: 132px" />
          </el-form-item>
          <el-form-item v-else label=" ">
            <span class="fresh-muted">元数据：只能发现长期无写入，发现不了写入了旧数据</span>
          </el-form-item>

          <el-form-item label="warn">
            <el-input-number v-model="form.warnAfterCount" :min="1" :controls="false" class="th-count" />
            <el-select v-model="form.warnAfterPeriod" class="th-period">
              <el-option label="分" value="minute" />
              <el-option label="时" value="hour" />
              <el-option label="天" value="day" />
            </el-select>
          </el-form-item>
          <el-form-item label="error">
            <el-input-number v-model="form.errorAfterCount" :min="1" :controls="false" class="th-count" />
            <el-select v-model="form.errorAfterPeriod" class="th-period">
              <el-option label="分" value="minute" />
              <el-option label="时" value="hour" />
              <el-option label="天" value="day" />
            </el-select>
          </el-form-item>

          <el-form-item label="过滤">
            <el-input v-model="form.filterExpr" placeholder="可选 WHERE 谓词" style="width: 160px" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>

          <el-form-item>
            <el-button type="primary" size="small" :loading="saving" :disabled="!tableId" @click="save">保存</el-button>
            <el-button
              v-if="resp && resp.configured"
              size="small"
              text
              type="danger"
              @click="removeConfig"
            >删除</el-button>
          </el-form-item>
        </el-form>
      </el-scrollbar>
    </section>

    <!-- 历史：按钮点开，不常驻 -->
    <el-dialog v-model="historyVisible" title="新鲜度检查历史" width="640px" append-to-body>
      <el-table :data="history" border size="small" max-height="420" v-loading="historyLoading">
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small" effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据年龄" min-width="110">
          <template #default="{ row }">{{ humanizeAge(row.ageSeconds) }}</template>
        </el-table-column>
        <el-table-column label="取值" width="100">
          <template #default="{ row }">{{ modeLabel(row.mode) }}</template>
        </el-table-column>
        <el-table-column label="触发" width="80">
          <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!historyLoading && history.length === 0" :image-size="60" description="暂无历史" />
    </el-dialog>
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
const historyVisible = ref(false)
const historyLoading = ref(false)

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

const MODE = { column: '字段', custom_sql: '自定义', partition: '分区', metadata: '元数据' }
const modeLabel = (m) => MODE[m] || m || '-'

const TRIGGER = { manual: '手动', schedule: '定时', inspection: '巡检', workflow: '工作流' }
const triggerLabel = (t) => TRIGGER[t] || t || '-'

const humanizeAge = (seconds) => {
  if (seconds === null || seconds === undefined) return '-'
  let s = Math.max(0, Number(seconds))
  const d = Math.floor(s / 86400); s -= d * 86400
  const h = Math.floor(s / 3600); s -= h * 3600
  const m = Math.floor(s / 60)
  if (d > 0) return h > 0 ? `${d} 天 ${h} 小时` : `${d} 天`
  if (h > 0) return m > 0 ? `${h} 小时 ${m} 分钟` : `${h} 小时`
  return `${m} 分钟`
}

const applyConfigToForm = () => {
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
}

const loadFreshness = async () => {
  if (!tableId.value) {
    resp.value = null
    return
  }
  loading.value = true
  try {
    resp.value = await tableApi.getFreshness(tableId.value)
    applyConfigToForm()
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    loading.value = false
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

const save = async () => {
  if (!tableId.value) return
  saving.value = true
  try {
    await tableApi.saveFreshness(tableId.value, { ...form })
    ElMessage.success('保存成功')
    await loadFreshness()
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    saving.value = false
  }
}

const removeConfig = async () => {
  try {
    await ElMessageBox.confirm('删除后该表将不再检查新鲜度。确认删除契约？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await tableApi.deleteFreshness(tableId.value)
    ElMessage.success('已删除')
    await loadFreshness()
  } catch (e) {
    // 拦截器已提示错误
  }
}

const openHistory = async () => {
  historyVisible.value = true
  if (!tableId.value) return
  historyLoading.value = true
  try {
    history.value = (await tableApi.freshnessHistory(tableId.value, 50)) || []
  } catch (e) {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

watch(tableId, () => loadFreshness(), { immediate: true })
</script>

<style scoped>
.fresh-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fresh-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.fresh-latest {
  font-size: 12px;
  color: var(--el-text-color-regular);
  margin: 8px 0 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.fresh-latest .dot {
  color: var(--el-text-color-disabled);
}
.fresh-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}
.fresh-form :deep(.el-form-item) {
  margin-right: 12px;
  margin-bottom: 10px;
}
.th-count {
  width: 68px;
}
.th-period {
  width: 62px;
  margin-left: 6px;
}
</style>
