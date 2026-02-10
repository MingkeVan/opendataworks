<template>
  <el-drawer
    v-model="visible"
    :title="isEdit ? '编辑任务' : '创建任务'"
    size="50%"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <el-form
      v-if="visible"
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
      style="padding-right: 20px"
    >
      <el-form-item label="任务名称" prop="task.taskName">
        <el-input
          v-model="form.task.taskName"
          placeholder="请输入任务名称"
          :disabled="isWriteTask"
          @input="handleTaskNameInput"
          @blur="handleTaskNameBlur"
        >
          <template #suffix>
            <el-icon v-if="taskNameChecking" class="is-loading">
              <Loading />
            </el-icon>
            <el-icon v-else-if="taskNameError" class="error-icon">
              <CircleClose />
            </el-icon>
            <el-icon v-else-if="form.task.taskName && !taskNameError" class="success-icon">
              <CircleCheck />
            </el-icon>
          </template>
        </el-input>
        <div v-if="isWriteTask" class="hint-text">
          写入任务名称自动使用目标表名
        </div>
        <div v-if="taskNameError" class="error-text">
          {{ taskNameError }}
        </div>
      </el-form-item>

      <el-form-item label="任务描述" prop="task.taskDesc">
        <el-input v-model="form.task.taskDesc" type="textarea" :rows="3" placeholder="请输入任务描述" />
      </el-form-item>

      <el-form-item label="所属工作流">
        <el-select
          v-model="form.task.workflowId"
          placeholder="可选择工作流（可选）"
          filterable
          :disabled="!!lockedWorkflowId"
          style="width: 100%"
          clearable
        >
          <el-option
            v-for="item in workflowOptions"
            :key="item.id"
            :label="item.workflowName"
            :value="item.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="任务类型" prop="task.dolphinNodeType">
        <el-select
          v-model="form.task.dolphinNodeType"
          placeholder="请选择任务类型"
          @change="handleNodeTypeChange"
          style="width: 100%"
        >
          <el-option label="SQL" value="SQL" />
          <el-option label="DataX" value="DATAX" />
          <el-option label="Shell" value="SHELL" />
          <el-option label="Python" value="PYTHON" />
        </el-select>
      </el-form-item>

      <template v-if="form.task.dolphinNodeType === 'SQL'">
        <el-form-item label="数据源" prop="task.datasourceName">
          <el-select
            v-model="form.task.datasourceName"
            placeholder="请选择数据源"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in datasourceOptions"
              :key="item.name"
              :label="item.name"
              :value="item.name"
            >
              <span>{{ item.name }}</span>
              <span style="float: right; color: #8492a6; font-size: 13px; margin-left: 10px">{{ item.dbName }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="SQL 脚本" prop="task.taskSql" class="sql-editor-item">
          <div class="sql-editor-wrapper">
            <SqlEditor
              ref="sqlEditorRef"
              v-model="form.task.taskSql"
              class="sql-codemirror"
              placeholder="请输入 SQL 脚本"
              :table-names="[]"
              :highlights="sqlHighlights"
            />
          </div>
        </el-form-item>

        <el-form-item label="SQL 解析" class="sql-analysis-item">
          <div class="sql-analysis-panel">
            <div class="sql-analysis-toolbar">
              <div class="sql-analysis-tags">
                <el-tag type="success" size="small">已命中 {{ analysisSummary.matched }}</el-tag>
                <el-tag type="warning" size="small">歧义 {{ analysisSummary.ambiguous }}</el-tag>
                <el-tag type="danger" size="small">未识别 {{ analysisSummary.unmatched }}</el-tag>
                <el-tag v-if="analysisLoading" type="info" size="small">解析中...</el-tag>
              </div>
              <div class="sql-analysis-actions">
                <el-button
                  type="primary"
                  link
                  :disabled="!hasMatchedSuggestions"
                  @click="applyMatchedSuggestions(false)"
                >
                  应用建议
                </el-button>
                <el-button
                  v-if="analysisSummary.unmatched > 0"
                  type="warning"
                  link
                  @click="goToMetadataSync"
                >
                  去元数据同步
                </el-button>
              </div>
            </div>

            <div v-if="analysisError" class="analysis-error">{{ analysisError }}</div>
            <el-alert
              v-if="analysisSummary.unmatched > 0 || analysisSummary.ambiguous > 0"
              :closable="false"
              show-icon
              type="warning"
              title="请人工核对解析结果；自动解析不会覆盖你已手工选择的输入/输出表。"
              class="analysis-alert"
            />

            <div class="analysis-group">
              <div class="analysis-title">输入表建议</div>
              <el-empty v-if="!sqlAnalysis.inputRefs.length" description="暂无输入表建议" :image-size="60" />
              <div
                v-for="(item, idx) in sqlAnalysis.inputRefs"
                :key="`in-${idx}-${item.rawName}`"
                class="analysis-item"
                @click="focusAnalyzeRef(item)"
              >
                <el-tag size="small" :type="analysisStatusType(item.matchStatus)">{{ analysisStatusText(item.matchStatus) }}</el-tag>
                <span class="analysis-raw">{{ item.rawName }}</span>
                <span class="analysis-target">{{ formatAnalyzeTarget(item) }}</span>
              </div>
            </div>

            <div class="analysis-group">
              <div class="analysis-title">输出表建议</div>
              <el-empty v-if="!sqlAnalysis.outputRefs.length" description="暂无输出表建议" :image-size="60" />
              <div
                v-for="(item, idx) in sqlAnalysis.outputRefs"
                :key="`out-${idx}-${item.rawName}`"
                class="analysis-item"
                @click="focusAnalyzeRef(item)"
              >
                <el-tag size="small" :type="analysisStatusType(item.matchStatus)">{{ analysisStatusText(item.matchStatus) }}</el-tag>
                <span class="analysis-raw">{{ item.rawName }}</span>
                <span class="analysis-target">{{ formatAnalyzeTarget(item) }}</span>
              </div>
            </div>
          </div>
        </el-form-item>
      </template>

      <template v-if="form.task.dolphinNodeType === 'DATAX'">
        <el-form-item label="源数据源" prop="task.datasourceName">
          <el-select
            v-model="form.task.datasourceName"
            placeholder="请选择源数据源"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in datasourceOptions"
              :key="item.name"
              :label="item.name"
              :value="item.name"
            >
              <span>{{ item.name }}</span>
              <span style="float: right; color: #8492a6; font-size: 13px; margin-left: 10px">{{ item.dbName }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="源表名" prop="task.sourceTable">
          <el-input v-model="form.task.sourceTable" placeholder="例如: user_info" />
        </el-form-item>

        <el-form-item label="目标数据源" prop="task.targetDatasourceName">
          <el-select
            v-model="form.task.targetDatasourceName"
            placeholder="请选择目标数据源"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in datasourceOptions"
              :key="item.name"
              :label="item.name"
              :value="item.name"
            >
              <span>{{ item.name }}</span>
              <span style="float: right; color: #8492a6; font-size: 13px; margin-left: 10px">{{ item.dbName }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="目标表名" prop="task.targetTable">
          <el-input v-model="form.task.targetTable" placeholder="例如: user_info_copy" />
        </el-form-item>

        <el-form-item label="列映射（可选）" prop="task.columnMapping">
          <el-input
            v-model="form.task.columnMapping"
            type="textarea"
            :rows="3"
            placeholder="留空表示全部列同步，或输入JSON格式的列映射配置"
          />
        </el-form-item>
      </template>

      <template v-if="['SHELL', 'PYTHON'].includes(form.task.dolphinNodeType)">
        <el-form-item label="脚本内容" prop="task.taskSql" class="sql-editor-item">
          <el-input
            v-model="form.task.taskSql"
            type="textarea"
            :rows="15"
            placeholder="请输入脚本内容"
            class="sql-input"
            resize="none"
            spellcheck="false"
          />
        </el-form-item>
      </template>

      <el-form-item label="输入表" prop="inputTableIds">
        <el-select
          v-model="form.inputTableIds"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="搜索输入表"
          style="width: 100%"
          :remote-method="handleTableSearch"
          :loading="tableLoading"
        >
          <el-option
            v-for="option in availableTableOptions"
            :key="option.id"
            :label="formatTableOptionLabel(option)"
            :value="option.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="输出表" prop="outputTableIds">
        <el-select
          v-model="form.outputTableIds"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="搜索输出表"
          style="width: 100%"
          :remote-method="handleTableSearch"
          :loading="tableLoading"
        >
          <el-option
            v-for="option in availableTableOptions"
            :key="option.id"
            :label="formatTableOptionLabel(option)"
            :value="option.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="优先级" prop="task.priority">
        <el-slider v-model="form.task.priority" :min="1" :max="10" show-stops />
      </el-form-item>

      <el-form-item label="负责任" prop="task.owner">
        <el-input v-model="form.task.owner" placeholder="请输入负责人" />
      </el-form-item>
    </el-form>

    <template #footer>
      <div style="flex: auto">
        <el-button @click="handleClose">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="loading">保存</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, reactive, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import SqlEditor from '@/components/SqlEditor.vue'
import { taskApi } from '@/api/task'
import { workflowApi } from '@/api/workflow'
import { tableApi } from '@/api/table'

const emit = defineEmits(['saved', 'success', 'close'])

const route = useRoute()
const router = useRouter()

const visible = ref(false)
const isEdit = ref(false)
const loading = ref(false)
const formRef = ref(null)
const sqlEditorRef = ref(null)
const lockedWorkflowId = ref(null)
const taskNameError = ref('')
const taskNameChecking = ref(false)
const originalTaskName = ref('')
const workflowOptions = ref([])
const isWriteTask = ref(false)

const datasourceOptions = ref([])
const tableOptions = ref([])
const tableLoading = ref(false)
const tableOptionCache = reactive({})

const analysisLoading = ref(false)
const analysisError = ref('')
const sqlAnalysis = reactive({
  inputRefs: [],
  outputRefs: [],
  unmatched: [],
  ambiguous: []
})

const inputSelectionTouched = ref(false)
const outputSelectionTouched = ref(false)
const isSyncingSelection = ref(false)

let tableSearchTimer = null
let taskNameCheckTimer = null
let sqlAnalyzeTimer = null
let analyzeSeq = 0

const form = reactive({
  task: {
    id: null,
    taskName: '',
    taskDesc: '',
    taskCode: '',
    taskType: 'batch',
    engine: 'dolphin',
    dolphinNodeType: 'SQL',
    datasourceName: '',
    datasourceType: 'DORIS',
    taskSql: '',
    scheduleCron: '',
    priority: 5,
    owner: '',
    clusterId: null,
    database: '',
    targetDatasourceName: '',
    sourceTable: '',
    targetTable: '',
    columnMapping: ''
  },
  inputTableIds: [],
  outputTableIds: []
})

const fetchWorkflowOptions = async () => {
  try {
    const res = await workflowApi.list({ pageNum: 1, pageSize: 200 })
    workflowOptions.value = res.records || []
  } catch (error) {
    console.error('获取工作流列表失败:', error)
  }
}

const fetchDatasourceOptions = async () => {
  try {
    const res = await taskApi.fetchDatasources()
    datasourceOptions.value = res || []
  } catch (error) {
    console.error('获取数据源失败:', error)
    ElMessage.error('获取数据源失败')
  }
}

const inputTableRule = [{ type: 'array', required: true, min: 1, message: '请选择至少一个输入表', trigger: 'change' }]
const outputTableRule = [{ type: 'array', required: true, min: 1, message: '请选择至少一个输出表', trigger: 'change' }]

const rules = computed(() => {
  const baseRules = {
    'task.taskName': [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
    outputTableIds: outputTableRule
  }

  if (form.task.dolphinNodeType === 'SQL') {
    return {
      ...baseRules,
      inputTableIds: inputTableRule,
      'task.datasourceName': [{ required: true, message: '请选择数据源', trigger: 'change' }],
      'task.taskSql': [{ required: true, message: 'SQL 脚本不能为空', trigger: 'blur' }]
    }
  }

  if (form.task.dolphinNodeType === 'DATAX') {
    return {
      ...baseRules,
      'task.datasourceName': [{ required: true, message: '请选择源数据源', trigger: 'change' }],
      'task.targetDatasourceName': [{ required: true, message: '请选择目标数据源', trigger: 'change' }],
      'task.sourceTable': [{ required: true, message: '请输入源表名', trigger: 'blur' }],
      'task.targetTable': [{ required: true, message: '请输入目标表名', trigger: 'blur' }]
    }
  }

  if (['SHELL', 'PYTHON'].includes(form.task.dolphinNodeType)) {
    return {
      ...baseRules,
      'task.taskSql': [{ required: true, message: '脚本内容不能为空', trigger: 'blur' }]
    }
  }

  return baseRules
})

const availableTableOptions = computed(() => {
  const seen = new Set()
  const result = []

  const append = (option) => {
    if (!option || !option.id || seen.has(option.id)) return
    result.push(option)
    seen.add(option.id)
  }

  tableOptions.value.forEach(append)
  ;[...(form.inputTableIds || []), ...(form.outputTableIds || [])].forEach((id) => {
    const cached = tableOptionCache[id]
    if (cached) append(cached)
  })

  return result
})

const analysisSummary = computed(() => {
  const refs = [...(sqlAnalysis.inputRefs || []), ...(sqlAnalysis.outputRefs || [])]
  let matched = 0
  let ambiguous = 0
  let unmatched = 0

  refs.forEach((item) => {
    const status = String(item?.matchStatus || '')
    if (status === 'matched') matched += 1
    else if (status === 'ambiguous') ambiguous += 1
    else if (status === 'unmatched') unmatched += 1
  })

  return { matched, ambiguous, unmatched }
})

const hasMatchedSuggestions = computed(() => {
  const inCount = (sqlAnalysis.inputRefs || []).filter((item) => item?.matchStatus === 'matched' && item?.chosenTable?.tableId).length
  const outCount = (sqlAnalysis.outputRefs || []).filter((item) => item?.matchStatus === 'matched' && item?.chosenTable?.tableId).length
  return inCount > 0 || outCount > 0
})

const sqlHighlights = computed(() => {
  const highlights = []
  const collect = (items = []) => {
    items.forEach((item) => {
      ;(item?.spans || []).forEach((span) => {
        const from = Number(span?.from)
        const to = Number(span?.to)
        if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) return
        highlights.push({ from, to, status: item?.matchStatus || 'matched' })
      })
    })
  }

  collect(sqlAnalysis.inputRefs)
  collect(sqlAnalysis.outputRefs)
  return highlights
})

const formatTableOptionLabel = (option) => {
  if (!option) return ''
  const pieces = [option.tableName || '']
  const meta = []

  const sourceMeta = [option.clusterName, option.sourceType].filter(Boolean).join('/')
  if (sourceMeta) meta.push(sourceMeta)
  if (option.layer) meta.push(option.layer)
  if (option.dbName) meta.push(option.dbName)

  if (meta.length) pieces.push(`(${meta.join(' / ')})`)
  return pieces.join(' ')
}

const upsertTableOptions = (items = []) => {
  items.forEach((item) => {
    if (item && item.id) {
      tableOptionCache[item.id] = item
    }
  })
}

const fetchTableOptions = async (keyword) => {
  if (!keyword) {
    tableOptions.value = []
    return
  }

  tableLoading.value = true
  try {
    const result = await tableApi.searchOptions({ keyword, limit: 20 })
    const list = Array.isArray(result) ? result : []
    tableOptions.value = list
    upsertTableOptions(list)
  } catch (error) {
    console.error('远程搜索表失败:', error)
  } finally {
    tableLoading.value = false
  }
}

const handleTableSearch = (query) => {
  const keyword = query ? query.trim() : ''
  if (tableSearchTimer) clearTimeout(tableSearchTimer)
  if (!keyword) {
    tableOptions.value = []
    return
  }
  tableSearchTimer = setTimeout(() => {
    fetchTableOptions(keyword)
  }, 300)
}

const ensureTableOptionsLoaded = async (ids = []) => {
  const uniqueIds = [...new Set(ids)].filter((id) => id && !tableOptionCache[id])
  if (!uniqueIds.length) return

  try {
    const tables = await Promise.all(uniqueIds.map((id) => tableApi.getById(id)))
    const options = tables.filter(Boolean).map((table) => ({
      id: table.id,
      tableName: table.tableName,
      tableComment: table.tableComment,
      layer: table.layer,
      dbName: table.dbName,
      clusterId: table.clusterId,
      qualifiedName: table.dbName && table.tableName ? `${table.dbName}.${table.tableName}` : table.tableName
    }))
    upsertTableOptions(options)
  } catch (error) {
    console.error('加载表选项失败:', error)
  }
}

const setTableSelections = (inputIds = [], outputIds = []) => {
  isSyncingSelection.value = true
  form.inputTableIds = [...new Set((inputIds || []).filter(Boolean))]
  form.outputTableIds = [...new Set((outputIds || []).filter(Boolean))]
  nextTick(() => {
    isSyncingSelection.value = false
  })
}

const clearSqlAnalysis = () => {
  analysisLoading.value = false
  analysisError.value = ''
  sqlAnalysis.inputRefs = []
  sqlAnalysis.outputRefs = []
  sqlAnalysis.unmatched = []
  sqlAnalysis.ambiguous = []
}

const runSqlAnalyze = async (sqlText) => {
  const seq = ++analyzeSeq
  analysisLoading.value = true
  analysisError.value = ''

  try {
    const result = await taskApi.analyzeSqlTables({
      sql: sqlText,
      nodeType: form.task.dolphinNodeType
    })

    if (seq !== analyzeSeq) return

    sqlAnalysis.inputRefs = Array.isArray(result?.inputRefs) ? result.inputRefs : []
    sqlAnalysis.outputRefs = Array.isArray(result?.outputRefs) ? result.outputRefs : []
    sqlAnalysis.unmatched = Array.isArray(result?.unmatched) ? result.unmatched : []
    sqlAnalysis.ambiguous = Array.isArray(result?.ambiguous) ? result.ambiguous : []

    if (
      !inputSelectionTouched.value &&
      !outputSelectionTouched.value &&
      (!form.inputTableIds?.length && !form.outputTableIds?.length)
    ) {
      await applyMatchedSuggestions(true)
    }
  } catch (error) {
    if (seq !== analyzeSeq) return
    console.error('SQL 解析失败:', error)
    analysisError.value = error?.message || 'SQL 解析失败'
    sqlAnalysis.inputRefs = []
    sqlAnalysis.outputRefs = []
    sqlAnalysis.unmatched = []
    sqlAnalysis.ambiguous = []
  } finally {
    if (seq === analyzeSeq) {
      analysisLoading.value = false
    }
  }
}

const scheduleSqlAnalyze = () => {
  if (sqlAnalyzeTimer) {
    clearTimeout(sqlAnalyzeTimer)
    sqlAnalyzeTimer = null
  }

  if (!visible.value || form.task.dolphinNodeType !== 'SQL') {
    clearSqlAnalysis()
    return
  }

  const sqlText = String(form.task.taskSql || '').trim()
  if (!sqlText) {
    clearSqlAnalysis()
    return
  }

  sqlAnalyzeTimer = setTimeout(() => {
    runSqlAnalyze(sqlText)
  }, 600)
}

const applyMatchedSuggestions = async (silent = false) => {
  const inputIds = (sqlAnalysis.inputRefs || [])
    .filter((item) => item?.matchStatus === 'matched' && item?.chosenTable?.tableId)
    .map((item) => item.chosenTable.tableId)

  const outputIds = (sqlAnalysis.outputRefs || [])
    .filter((item) => item?.matchStatus === 'matched' && item?.chosenTable?.tableId)
    .map((item) => item.chosenTable.tableId)

  const mergedInputs = [...new Set([...(form.inputTableIds || []), ...inputIds])]
  const mergedOutputs = [...new Set([...(form.outputTableIds || []), ...outputIds])]

  const changed =
    mergedInputs.length !== (form.inputTableIds || []).length ||
    mergedOutputs.length !== (form.outputTableIds || []).length

  if (!changed) {
    if (!silent) ElMessage.info('没有可应用的新建议')
    return
  }

  setTableSelections(mergedInputs, mergedOutputs)
  await ensureTableOptionsLoaded([...mergedInputs, ...mergedOutputs])

  if (!silent) {
    ElMessage.success('已应用 SQL 解析建议')
  }
}

const analysisStatusType = (status) => {
  if (status === 'matched') return 'success'
  if (status === 'ambiguous') return 'warning'
  if (status === 'unmatched') return 'danger'
  return 'info'
}

const analysisStatusText = (status) => {
  if (status === 'matched') return '命中'
  if (status === 'ambiguous') return '歧义'
  if (status === 'unmatched') return '未识别'
  return '未知'
}

const formatAnalyzeTarget = (item) => {
  if (!item) return '-'
  if (item.matchStatus === 'matched' && item.chosenTable) {
    const c = item.chosenTable
    const source = [c.clusterName, c.sourceType].filter(Boolean).join('/')
    const dbTable = [c.dbName, c.tableName].filter(Boolean).join('.')
    return [source, dbTable].filter(Boolean).join(' / ')
  }
  if (item.matchStatus === 'ambiguous') {
    return `候选 ${Array.isArray(item.candidates) ? item.candidates.length : 0} 个`
  }
  return '未匹配到平台元数据'
}

const focusAnalyzeRef = (item) => {
  const first = item?.spans?.[0]
  if (!first) return
  sqlEditorRef.value?.scrollToRange?.(first.from, first.to)
}

const goToMetadataSync = () => {
  router.push({
    path: '/integration',
    query: {
      redirect: route.fullPath
    }
  })
}

const open = async (id = null, initialData = {}) => {
  visible.value = true
  loading.value = false
  isEdit.value = !!id

  resetForm()
  await Promise.all([fetchDatasourceOptions(), fetchWorkflowOptions()])
  lockedWorkflowId.value = null

  if (id) {
    try {
      const taskData = (await taskApi.getById(id)) || {}
      Object.assign(form.task, taskData)
      originalTaskName.value = form.task.taskName

      const lineage = await taskApi.getTaskLineage(id)
      const inputIds = Array.isArray(lineage.inputTableIds) ? lineage.inputTableIds : []
      const outputIds = Array.isArray(lineage.outputTableIds) ? lineage.outputTableIds : []
      setTableSelections(inputIds, outputIds)

      await ensureTableOptionsLoaded([...inputIds, ...outputIds])
    } catch (error) {
      console.error(error)
      ElMessage.error('加载任务详情失败')
    }
  } else {
    if (initialData.taskSql) form.task.taskSql = initialData.taskSql
    if (initialData.taskName) form.task.taskName = initialData.taskName
    if (initialData.taskDesc) form.task.taskDesc = initialData.taskDesc

    if (initialData.workflowId) {
      form.task.workflowId = initialData.workflowId
      lockedWorkflowId.value = initialData.workflowId
    }

    if (initialData.relation && initialData.tableId) {
      const tableId = Number(initialData.tableId)
      if (Number.isFinite(tableId)) {
        await ensureTableOptionsLoaded([tableId])
        if (initialData.relation === 'write') {
          setTableSelections([], [tableId])
          isWriteTask.value = true
          const tableInfo = tableOptionCache[tableId]
          if (tableInfo?.tableName) {
            form.task.taskName = tableInfo.tableName
          }
        } else if (initialData.relation === 'read') {
          setTableSelections([tableId], [])
        }
      }
    }
  }

  inputSelectionTouched.value = false
  outputSelectionTouched.value = false
  scheduleSqlAnalyze()
}

const handleClose = () => {
  visible.value = false
  emit('close')
}

const checkTaskName = async (taskName) => {
  if (!taskName) {
    taskNameError.value = ''
    return
  }

  try {
    taskNameChecking.value = true
    const excludeId = isEdit.value ? form.task.id : null
    const exists = await taskApi.checkTaskName(taskName, excludeId)
    taskNameError.value = exists ? '任务名称已存在' : ''
  } catch (error) {
    console.error('检查任务名称失败:', error)
    taskNameError.value = '检查失败，请稍后重试'
  } finally {
    taskNameChecking.value = false
  }
}

const handleTaskNameInput = () => {
  taskNameError.value = ''
  if (isEdit.value && form.task.taskName === originalTaskName.value) {
    return
  }

  if (taskNameCheckTimer) clearTimeout(taskNameCheckTimer)
  taskNameCheckTimer = setTimeout(async () => {
    await checkTaskName(form.task.taskName)
  }, 500)
}

const handleTaskNameBlur = () => {
  if (taskNameCheckTimer) {
    clearTimeout(taskNameCheckTimer)
    taskNameCheckTimer = null
  }
  checkTaskName(form.task.taskName)
}

const handleSave = async () => {
  if (!formRef.value) return

  if (taskNameError.value) {
    ElMessage.error('请解决任务名称重复问题')
    return
  }

  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    const { taskGroupName, ...taskPayload } = form.task
    const payload = {
      task: taskPayload,
      inputTableIds: form.inputTableIds,
      outputTableIds: form.outputTableIds
    }

    if (isEdit.value) {
      await taskApi.update(form.task.id, payload)
      ElMessage.success('更新成功')
    } else {
      await taskApi.create(payload)
      ElMessage.success('创建成功')
    }

    visible.value = false
    emit('saved')
    emit('success')
  } catch (error) {
    console.error(error)
    ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.task = {
    id: null,
    taskName: '',
    taskDesc: '',
    taskCode: '',
    taskType: 'batch',
    engine: 'dolphin',
    dolphinNodeType: 'SQL',
    datasourceName: '',
    datasourceType: 'DORIS',
    taskSql: '',
    scheduleCron: '',
    priority: 5,
    owner: '',
    workflowId: null,
    targetDatasourceName: '',
    sourceTable: '',
    targetTable: '',
    columnMapping: ''
  }

  setTableSelections([], [])
  tableOptions.value = []
  taskNameError.value = ''
  originalTaskName.value = ''
  isWriteTask.value = false
  inputSelectionTouched.value = false
  outputSelectionTouched.value = false
  clearSqlAnalysis()
}

const handleNodeTypeChange = (newType) => {
  if (newType !== 'SQL') {
    form.task.datasourceName = ''
    form.task.datasourceType = 'DORIS'
    clearSqlAnalysis()
  }
  if (newType !== 'DATAX') {
    form.task.targetDatasourceName = ''
    form.task.sourceTable = ''
    form.task.targetTable = ''
    form.task.columnMapping = ''
  }
  if (!['SQL', 'SHELL', 'PYTHON'].includes(newType)) {
    form.task.taskSql = ''
  }

  scheduleSqlAnalyze()
}

watch(
  () => [...(form.inputTableIds || [])],
  () => {
    if (!isSyncingSelection.value) {
      inputSelectionTouched.value = true
    }
  }
)

watch(
  () => [...(form.outputTableIds || [])],
  () => {
    if (!isSyncingSelection.value) {
      outputSelectionTouched.value = true
    }
  }
)

watch(
  () => form.task.taskSql,
  () => {
    scheduleSqlAnalyze()
  }
)

watch(
  () => form.task.dolphinNodeType,
  () => {
    scheduleSqlAnalyze()
  }
)

onBeforeUnmount(() => {
  if (tableSearchTimer) clearTimeout(tableSearchTimer)
  if (taskNameCheckTimer) clearTimeout(taskNameCheckTimer)
  if (sqlAnalyzeTimer) clearTimeout(sqlAnalyzeTimer)
})

defineExpose({
  open
})
</script>

<style scoped>
.sql-input :deep(.el-textarea__inner) {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  background-color: #fafafa;
  color: #333;
  padding: 12px;
}

.sql-input :deep(.el-textarea__inner):focus {
  background-color: #fff;
  border-color: #409eff;
}

.sql-editor-wrapper {
  width: 100%;
}

.sql-codemirror {
  width: 100%;
  height: 320px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  overflow: hidden;
}

.sql-analysis-panel {
  width: 100%;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px;
  background: #fafafa;
}

.sql-analysis-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.sql-analysis-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.sql-analysis-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.analysis-alert {
  margin-top: 8px;
}

.analysis-error {
  margin-top: 8px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.analysis-group {
  margin-top: 10px;
}

.analysis-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--el-text-color-primary);
}

.analysis-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
}

.analysis-item:hover {
  background: #f0f7ff;
}

.analysis-raw {
  color: var(--el-text-color-primary);
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
}

.analysis-target {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.error-text {
  color: #f56c6c;
  font-size: 12px;
  margin-top: 4px;
}

.hint-text {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}

.error-icon {
  color: #f56c6c;
}

.success-icon {
  color: #67c23a;
}

.is-loading {
  animation: rotating 2s linear infinite;
}

@keyframes rotating {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
</style>
