<template>
  <div class="skill-studio">
    <el-row :gutter="16" class="summary-row">
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="summary-card">
          <div class="summary-label">Skills 根目录</div>
          <div class="summary-value path">{{ settings.skills_root_dir || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :md="4">
        <el-card shadow="never" class="summary-card">
          <div class="summary-label">托管文件</div>
          <div class="summary-value">{{ documents.length }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :md="4">
        <el-card shadow="never" class="summary-card">
          <div class="summary-label">当前编辑</div>
          <div class="summary-value">{{ detail?.file_name || '未选择' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="summary-card sync-card">
          <div>
            <div class="summary-label">手动同步</div>
            <div class="summary-subtext">从 `opendataworks` / `dataagent` 数据库重新生成技能文件。</div>
          </div>
          <el-button type="warning" @click="triggerSync" :loading="syncLoading">
            从 ODW 同步
          </el-button>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="syncResult"
      type="success"
      :closable="true"
      show-icon
      class="sync-alert"
      @close="syncResult = null"
    >
      <template #title>
        同步完成：共处理 {{ syncResult.document_count }} 个文件，变更 {{ syncResult.changed_documents?.length || 0 }} 个，
        新收录 {{ syncResult.imported_documents?.length || 0 }} 个。
      </template>
    </el-alert>

    <div class="studio-grid">
      <el-card shadow="never" class="documents-card">
        <template #header>
          <div class="card-header">
            <div>
              <div class="card-title">技能文件</div>
              <div class="card-subtitle">支持前端编辑、版本比对和回滚。</div>
            </div>
            <div class="actions">
              <el-input
                v-model="searchKeyword"
                clearable
                placeholder="搜索文件名或路径"
                class="search-input"
              />
              <el-button @click="loadDocuments" :loading="listLoading">刷新</el-button>
            </div>
          </div>
        </template>

        <el-table
          v-loading="listLoading"
          :data="filteredDocuments"
          border
          highlight-current-row
          row-key="id"
          height="680"
          @row-click="handleRowClick"
        >
          <el-table-column label="文件" min-width="240">
            <template #default="{ row }">
              <div class="file-cell">
                <div class="file-name">{{ row.file_name }}</div>
                <div class="file-path">{{ row.relative_path }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.category }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="version_count" label="版本" width="84" />
          <el-table-column label="最近更新" min-width="160">
            <template #default="{ row }">
              {{ formatTime(row.updated_at) }}
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="editor-card">
        <template #header>
          <div class="card-header">
            <div>
              <div class="card-title">文件编辑</div>
              <div class="card-subtitle">
                <template v-if="detail">
                  {{ detail.relative_path }} · 当前版本 {{ currentVersionLabel }}
                </template>
                <template v-else>
                  请选择左侧技能文件开始编辑
                </template>
              </div>
            </div>
            <div v-if="detail" class="actions">
              <el-button @click="openCompareDialog()">版本比对</el-button>
              <el-button @click="resetEditor" :disabled="!editorDirty">重置</el-button>
              <el-button type="primary" @click="saveDocument" :loading="saveLoading" :disabled="!editorDirty">
                保存到磁盘和 DataAgent
              </el-button>
            </div>
          </div>
        </template>

        <div v-if="detail" v-loading="detailLoading" class="editor-layout">
          <div class="editor-meta">
            <el-tag size="small" type="info">{{ detail.content_type }}</el-tag>
            <el-tag size="small" :type="editorDirty ? 'warning' : 'success'">
              {{ editorDirty ? '未保存' : '已同步' }}
            </el-tag>
            <span class="meta-text">最近变更：{{ detail.last_change_source }} / {{ detail.last_change_summary || '-' }}</span>
          </div>

          <el-input
            v-model="changeSummary"
            placeholder="本次修改说明（将写入版本记录）"
            maxlength="120"
            show-word-limit
            class="summary-input"
          />

          <div class="editor-shell">
            <TextCodeEditor
              v-model="editorContent"
              :placeholder="detail.content_type === 'json' ? '请输入 JSON 内容' : '请输入文件内容'"
            />
          </div>

          <div class="versions-panel">
            <div class="section-heading">版本历史</div>
            <el-table :data="detail.versions || []" border size="small" height="240">
              <el-table-column prop="version_no" label="版本" width="82">
                <template #default="{ row }">
                  <span>V{{ row.version_no }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.is_current ? 'success' : 'info'">
                    {{ row.is_current ? '当前' : '历史' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="change_source" label="来源" width="100" />
              <el-table-column prop="change_summary" label="说明" min-width="180" show-overflow-tooltip />
              <el-table-column label="时间" min-width="160">
                <template #default="{ row }">
                  {{ formatTime(row.created_at) }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button text type="primary" @click="openCompareDialog(row.id)">对比</el-button>
                  <el-button
                    text
                    type="danger"
                    @click="confirmRollback(row)"
                    :disabled="row.is_current"
                  >
                    回滚
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <el-empty v-else description="请选择左侧技能文件后开始管理" :image-size="110" />
      </el-card>
    </div>

    <el-dialog
      v-model="compareDialogVisible"
      title="版本比对"
      width="86%"
      :close-on-click-modal="false"
    >
      <div v-if="detail" class="compare-toolbar">
        <el-select v-model="leftCompareVersionId" class="compare-select" @change="loadCompare">
          <el-option :value="null" label="当前版本" />
          <el-option
            v-for="item in detail.versions || []"
            :key="item.id"
            :value="item.id"
            :label="`V${item.version_no} · ${item.change_source}`"
          />
        </el-select>
        <span class="compare-arrow">vs</span>
        <el-select v-model="rightCompareVersionId" class="compare-select" @change="loadCompare">
          <el-option :value="null" label="当前版本" />
          <el-option
            v-for="item in detail.versions || []"
            :key="item.id"
            :value="item.id"
            :label="`V${item.version_no} · ${item.change_source}`"
          />
        </el-select>
      </div>

      <div v-loading="compareLoading" class="compare-body">
        <div v-if="compareResult" class="compare-stats">
          <el-tag size="small" type="primary">{{ compareResult.left_label }}</el-tag>
          <el-tag size="small" type="success">{{ compareResult.right_label }}</el-tag>
          <el-tag size="small" type="warning">变更行 {{ compareResult.changed_lines }}</el-tag>
          <el-tag size="small">+{{ compareResult.added_lines }}</el-tag>
          <el-tag size="small">-{{ compareResult.removed_lines }}</el-tag>
        </div>

        <div v-if="compareResult" class="compare-editors">
          <div class="compare-column">
            <div class="compare-title">{{ compareResult.left_label }}</div>
            <div class="compare-editor">
              <TextCodeEditor :model-value="compareResult.left_content" read-only />
            </div>
          </div>
          <div class="compare-column">
            <div class="compare-title">{{ compareResult.right_label }}</div>
            <div class="compare-editor">
              <TextCodeEditor :model-value="compareResult.right_content" read-only />
            </div>
          </div>
        </div>

        <div v-if="compareResult" class="diff-panel">
          <div class="compare-title">Unified Diff</div>
          <div class="diff-editor">
            <TextCodeEditor :model-value="compareResult.diff_text" read-only />
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { dataagentApi } from '@/api/dataagent'
import TextCodeEditor from '@/components/TextCodeEditor.vue'

const listLoading = ref(false)
const detailLoading = ref(false)
const saveLoading = ref(false)
const syncLoading = ref(false)
const compareLoading = ref(false)

const searchKeyword = ref('')
const documents = ref([])
const selectedDocumentId = ref(null)
const detail = ref(null)
const editorContent = ref('')
const changeSummary = ref('')
const syncResult = ref(null)
const settings = ref({
  skills_root_dir: '',
  session_mysql_database: ''
})

const compareDialogVisible = ref(false)
const compareResult = ref(null)
const leftCompareVersionId = ref(null)
const rightCompareVersionId = ref(null)

const filteredDocuments = computed(() => {
  const keyword = String(searchKeyword.value || '').trim().toLowerCase()
  if (!keyword) return documents.value
  return documents.value.filter((item) => {
    return String(item.file_name || '').toLowerCase().includes(keyword) || String(item.relative_path || '').toLowerCase().includes(keyword)
  })
})

const editorDirty = computed(() => {
  return !!detail.value && editorContent.value !== (detail.value.current_content || '')
})

const currentVersionLabel = computed(() => {
  const current = (detail.value?.versions || []).find((item) => item.is_current)
  return current ? `V${current.version_no}` : '-'
})

const formatTime = (value) => {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss')
}

const loadSettings = async () => {
  const payload = await dataagentApi.getSettings()
  settings.value = {
    skills_root_dir: payload?.skills_root_dir || '',
    session_mysql_database: payload?.session_mysql_database || ''
  }
}

const loadDocuments = async () => {
  listLoading.value = true
  try {
    documents.value = await dataagentApi.listSkillDocuments()
    if (!documents.value.length) {
      selectedDocumentId.value = null
      detail.value = null
      editorContent.value = ''
      return
    }
    if (!selectedDocumentId.value && documents.value.length) {
      await loadDocument(documents.value[0].id)
      return
    }
    if (selectedDocumentId.value) {
      const matched = documents.value.find((item) => item.id === selectedDocumentId.value)
      if (!matched && documents.value.length) {
        await loadDocument(documents.value[0].id)
      }
    }
  } finally {
    listLoading.value = false
  }
}

const loadDocument = async (documentId) => {
  selectedDocumentId.value = documentId
  detailLoading.value = true
  try {
    const payload = await dataagentApi.getSkillDocument(documentId)
    detail.value = payload
    editorContent.value = payload?.current_content || ''
    changeSummary.value = ''
  } finally {
    detailLoading.value = false
  }
}

const handleRowClick = (row) => {
  if (!row?.id) return
  loadDocument(row.id)
}

const saveDocument = async () => {
  if (!detail.value) return
  saveLoading.value = true
  try {
    const payload = await dataagentApi.updateSkillDocument(detail.value.id, {
      content: editorContent.value,
      change_summary: changeSummary.value || '前端保存'
    })
    detail.value = payload
    editorContent.value = payload.current_content || ''
    changeSummary.value = ''
    await loadDocuments()
    ElMessage.success('文件已保存到磁盘和 DataAgent')
  } finally {
    saveLoading.value = false
  }
}

const resetEditor = () => {
  if (!detail.value) return
  editorContent.value = detail.value.current_content || ''
  changeSummary.value = ''
}

const loadCompare = async () => {
  if (!detail.value) return
  compareLoading.value = true
  try {
    compareResult.value = await dataagentApi.compareSkillDocument(detail.value.id, {
      left_version_id: leftCompareVersionId.value,
      right_version_id: rightCompareVersionId.value
    })
  } finally {
    compareLoading.value = false
  }
}

const openCompareDialog = async (leftVersionId = null) => {
  if (!detail.value) return
  const fallbackHistory = (detail.value.versions || []).find((item) => !item.is_current)
  leftCompareVersionId.value = leftVersionId ?? fallbackHistory?.id ?? null
  rightCompareVersionId.value = null
  compareDialogVisible.value = true
  await loadCompare()
}

const confirmRollback = async (version) => {
  if (!detail.value || !version?.id || version.is_current) return
  try {
    await ElMessageBox.confirm(
      `确认回滚 ${detail.value.file_name} 到 V${version.version_no} 吗？`,
      '版本回滚',
      {
        type: 'warning',
        confirmButtonText: '确认回滚',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }
  const payload = await dataagentApi.rollbackSkillDocument(detail.value.id, version.id)
  detail.value = payload
  editorContent.value = payload.current_content || ''
  changeSummary.value = ''
  await loadDocuments()
  ElMessage.success(`已回滚到 V${version.version_no}`)
}

const triggerSync = async () => {
  try {
    await ElMessageBox.confirm(
      '确认从 opendataworks / dataagent 数据库重新同步技能文件吗？',
      '手动同步',
      {
        type: 'warning',
        confirmButtonText: '开始同步',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  syncLoading.value = true
  try {
    syncResult.value = await dataagentApi.syncSkills()
    await loadDocuments()
    if (selectedDocumentId.value) {
      await loadDocument(selectedDocumentId.value)
    }
    ElMessage.success('技能文件同步完成')
  } finally {
    syncLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadSettings(), loadDocuments()])
})
</script>

<style scoped>
.skill-studio {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary-row {
  margin: 0 !important;
}

.summary-card {
  height: 100%;
}

.summary-label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}

.summary-value {
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}

.summary-value.path {
  font-size: 13px;
  line-height: 1.5;
  word-break: break-all;
}

.summary-subtext {
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}

.sync-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.sync-alert {
  margin-top: -4px;
}

.studio-grid {
  display: grid;
  grid-template-columns: minmax(320px, 420px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.documents-card,
.editor-card {
  min-height: 760px;
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.card-subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
  word-break: break-all;
}

.actions {
  display: flex;
  gap: 8px;
}

.search-input {
  width: 220px;
}

.file-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.file-name {
  font-weight: 600;
  color: #1f2937;
}

.file-path {
  font-size: 12px;
  color: #64748b;
  word-break: break-all;
}

.editor-layout {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.editor-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-text {
  font-size: 12px;
  color: #64748b;
}

.summary-input {
  max-width: 620px;
}

.editor-shell {
  min-height: 360px;
  height: 420px;
}

.versions-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.section-heading {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
}

.compare-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.compare-select {
  width: 260px;
}

.compare-arrow {
  color: #64748b;
  font-size: 13px;
}

.compare-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.compare-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.compare-editors {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.compare-column,
.diff-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.compare-title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.compare-editor {
  height: 260px;
}

.diff-editor {
  height: 220px;
}

@media (max-width: 1200px) {
  .studio-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .sync-card :deep(.el-card__body),
  .card-header,
  .actions,
  .compare-toolbar,
  .compare-editors {
    flex-direction: column;
  }

  .search-input,
  .compare-select {
    width: 100%;
  }

  .compare-editors {
    grid-template-columns: 1fr;
  }
}
</style>
