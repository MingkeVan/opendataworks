<template>
  <div class="task-form">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>{{ isEdit ? '编辑任务' : '创建任务' }}</span>
          <el-button @click="handleCancel">返回</el-button>
        </div>
      </template>

      <el-form :model="form" :rules="rules" ref="formRef" label-width="120px" style="max-width: 800px">
        <el-form-item label="任务名称" prop="taskName">
          <el-input v-model="form.taskName" />
        </el-form-item>

        <el-form-item label="任务编码">
          <el-input v-model="form.taskCode" disabled placeholder="保存后自动生成" />
        </el-form-item>

        <el-form-item label="任务类型" prop="taskType">
          <el-radio-group v-model="form.taskType">
            <el-radio label="batch">批任务</el-radio>
            <el-radio label="stream">流任务</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="执行引擎" prop="engine">
          <el-select v-model="form.engine" style="width: 100%">
            <el-option label="DolphinScheduler" value="dolphin" />
            <el-option label="Dinky" value="dinky" :disabled="form.taskType === 'batch'" />
          </el-select>
        </el-form-item>

        <!-- DolphinScheduler 节点类型 -->
        <el-form-item label="节点类型" prop="dolphinNodeType" v-if="form.engine === 'dolphin'">
          <el-select v-model="form.dolphinNodeType" style="width: 100%">
            <el-option label="SQL" value="SQL" />
            <el-option label="SHELL" value="SHELL" />
            <el-option label="PYTHON" value="PYTHON" />
          </el-select>
        </el-form-item>

        <!-- 数据源配置（仅 SQL 节点） -->
        <el-form-item
          label="数据源"
          prop="datasourceName"
          v-if="form.engine === 'dolphin' && form.dolphinNodeType === 'SQL'"
        >
          <el-select
            v-model="form.datasourceName"
            style="width: 100%"
            filterable
            remote
            reserve-keyword
            placeholder="选择数据源"
            :remote-method="handleDatasourceSearch"
            :loading="datasourceLoading"
            @visible-change="handleDatasourceDropdown"
            @change="handleDatasourceChange"
          >
            <el-option
              v-for="option in datasourceOptions"
              :key="option.name"
              :label="formatDatasourceLabel(option)"
              :value="option.name"
            >
              <div class="datasource-option">
                <span>{{ option.name }}</span>
                <el-tag size="small" type="info">{{ option.type || '未知' }}</el-tag>
              </div>
            </el-option>
          </el-select>
          <div v-if="form.datasourceType" class="datasource-hint">
            <el-tag size="small" type="info">{{ form.datasourceType }}</el-tag>
            <span v-if="selectedDatasource && selectedDatasource.dbName" class="datasource-db">
              {{ selectedDatasource.dbName }}
            </span>
          </div>
        </el-form-item>

        <el-form-item label="任务SQL" prop="taskSql">
          <el-input v-model="form.taskSql" type="textarea" :rows="10" placeholder="输入 SQL 语句..." />
        </el-form-item>

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

        <el-form-item label="优先级" prop="priority">
          <el-slider v-model="form.priority" :min="1" :max="10" show-stops />
        </el-form-item>

        <el-form-item label="超时时间(秒)" prop="timeoutSeconds">
          <el-input-number v-model="form.timeoutSeconds" :min="60" :step="60" />
        </el-form-item>

        <el-form-item label="负责人" prop="owner">
          <el-input v-model="form.owner" />
        </el-form-item>

        <el-form-item label="描述" prop="taskDesc">
          <el-input v-model="form.taskDesc" type="textarea" :rows="3" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleSubmit" :loading="submitting">提交</el-button>
          <el-button @click="handleCancel">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, watch, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { taskApi } from '@/api/task'
import { tableApi } from '@/api/table'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)

const formRef = ref(null)
const submitting = ref(false)
const datasourceOptions = ref([])
const datasourceLoading = ref(false)
const datasourceOptionMap = reactive({})
let datasourceSearchTimer = null

const tableOptions = ref([])
const tableOptionCache = reactive({})
const tableLoading = ref(false)
let tableSearchTimer = null
const DEFAULT_DATASOURCE_TYPE = 'DORIS'

const form = reactive({
  taskName: '',
  taskCode: '',
  taskType: 'batch',
  engine: 'dolphin',
  dolphinNodeType: 'SQL',
  datasourceName: '',
  datasourceType: '',
  taskSql: '',
  priority: 5,
  timeoutSeconds: 3600,
  retryTimes: 0,
  retryInterval: 60,
  owner: '',
  taskDesc: '',
  inputTableIds: [],
  outputTableIds: []
})

const redirectTarget = computed(() => {
  const raw = route.query.redirect
  if (!raw) return ''
  const value = Array.isArray(raw) ? raw[0] : raw
  try {
    return decodeURIComponent(value)
  } catch (error) {
    return value
  }
})

const selectedDatasource = computed(() => {
  if (!form.datasourceName) return null
  return datasourceOptionMap[form.datasourceName] || null
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
    if (cached) {
      append(cached)
    }
  })
  return result
})

const rules = {
  taskName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  taskSql: [{ required: true, message: '请输入任务SQL', trigger: 'blur' }],
  dolphinNodeType: [{ required: true, message: '请选择节点类型', trigger: 'change' }],
  datasourceName: [
    {
      required: true,
      message: '请选择数据源',
      trigger: 'change'
    },
    {
      validator: (_rule, value, callback) => {
        if (!value) {
          callback(new Error('请选择数据源'))
        } else if (!form.datasourceType) {
          callback(new Error('数据源类型缺失，请重新选择'))
        } else {
          callback()
        }
      },
      trigger: 'change'
    }
  ]
}

const formatDatasourceLabel = (option) => {
  if (!option) return ''
  return option.dbName ? `${option.name} (${option.dbName})` : option.name
}

const upsertDatasourceOptions = (items = []) => {
  items.forEach((item) => {
    if (item && item.name) {
      datasourceOptionMap[item.name] = item
    }
  })
}

const ensureDatasourceOption = (name, type, dbName) => {
  if (!name) return
  if (!datasourceOptionMap[name]) {
    const option = {
      name,
      type: type || '',
      dbName: dbName || ''
    }
    datasourceOptionMap[name] = option
    datasourceOptions.value = [option, ...datasourceOptions.value.filter((item) => item.name !== name)]
  }
  if (type && !form.datasourceType) {
    form.datasourceType = type
  }
}

const fetchDatasourceOptions = async (keyword = '') => {
  datasourceLoading.value = true
  try {
    const params = { type: DEFAULT_DATASOURCE_TYPE }
    if (keyword) {
      params.keyword = keyword
    }
    const result = await taskApi.fetchDatasources(params)
    const list = Array.isArray(result) ? result : []
    datasourceOptions.value = list
    upsertDatasourceOptions(list)
  } catch (error) {
    console.error('加载数据源失败:', error)
  } finally {
    datasourceLoading.value = false
  }
}

const handleDatasourceSearch = (query) => {
  const keyword = query ? query.trim() : ''
  if (datasourceSearchTimer) {
    clearTimeout(datasourceSearchTimer)
  }
  datasourceSearchTimer = setTimeout(() => {
    fetchDatasourceOptions(keyword)
  }, 300)
}

const handleDatasourceDropdown = (visible) => {
  if (visible && !datasourceOptions.value.length) {
    fetchDatasourceOptions('')
  }
}

const handleDatasourceChange = (value) => {
  if (!value) {
    form.datasourceType = ''
    return
  }
  const option = datasourceOptionMap[value]
  if (option) {
    form.datasourceType = option.type || ''
  }
}

const formatTableOptionLabel = (option) => {
  if (!option) return ''
  const pieces = [option.tableName]
  const meta = []
  if (option.layer) meta.push(option.layer)
  if (option.dbName) meta.push(option.dbName)
  if (meta.length) {
    pieces.push(`(${meta.join(' / ')})`)
  }
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
    const params = { keyword, limit: 20 }
    const result = await tableApi.searchOptions(params)
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
  if (tableSearchTimer) {
    clearTimeout(tableSearchTimer)
  }
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
    const options = tables
      .filter(Boolean)
      .map((table) => ({
        id: table.id,
        tableName: table.tableName,
        tableComment: table.tableComment,
        layer: table.layer,
        dbName: table.dbName
      }))
    upsertTableOptions(options)
  } catch (error) {
    console.error('加载表选项失败:', error)
  }
}

const loadTask = async () => {
  if (!isEdit.value) return
  try {
    const task = await taskApi.getById(route.params.id)
    Object.assign(form, {
      taskName: task.taskName || '',
      taskCode: task.taskCode || '',
      taskType: task.taskType || 'batch',
      engine: task.engine || 'dolphin',
      dolphinNodeType: task.dolphinNodeType || 'SQL',
      datasourceName: task.datasourceName || '',
      datasourceType: task.datasourceType || '',
      taskSql: task.taskSql || '',
      priority: task.priority ?? 5,
      timeoutSeconds: task.timeoutSeconds ?? 3600,
      retryTimes: task.retryTimes ?? 0,
      retryInterval: task.retryInterval ?? 60,
      owner: task.owner || '',
      taskDesc: task.taskDesc || ''
    })
    ensureDatasourceOption(form.datasourceName, form.datasourceType, task.dbName)

    // 加载血缘关系数据
    const lineage = await taskApi.getTaskLineage(route.params.id)
    form.inputTableIds = Array.isArray(lineage.inputTableIds) ? lineage.inputTableIds : []
    form.outputTableIds = Array.isArray(lineage.outputTableIds) ? lineage.outputTableIds : []
    await ensureTableOptionsLoaded([...form.inputTableIds, ...form.outputTableIds])
  } catch (error) {
    console.error('加载任务失败:', error)
  }
}

const applyRelationPreset = async () => {
  if (isEdit.value) return
  const relation = route.query.relation
  const tableIdParam = route.query.tableId
  if (!relation || !tableIdParam) return

  const tableId = Number(tableIdParam)
  if (!Number.isFinite(tableId)) return

  await ensureTableOptionsLoaded([tableId])

  if (relation === 'write' && !form.outputTableIds.includes(tableId)) {
    form.outputTableIds.push(tableId)
  } else if (relation === 'read' && !form.inputTableIds.includes(tableId)) {
    form.inputTableIds.push(tableId)
  }
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate()
  submitting.value = true
  try {
    const taskData = { ...form }
    const inputTableIds = taskData.inputTableIds || []
    const outputTableIds = taskData.outputTableIds || []
    delete taskData.inputTableIds
    delete taskData.outputTableIds

    const data = {
      task: taskData,
      inputTableIds,
      outputTableIds
    }

    if (isEdit.value) {
      await taskApi.update(route.params.id, data)
      ElMessage.success('更新成功')
    } else {
      await taskApi.create(data)
      ElMessage.success('创建成功')
    }

    if (redirectTarget.value) {
      router.push(redirectTarget.value)
    } else {
      router.push('/tasks')
    }
  } catch (error) {
    console.error('提交失败:', error)
    ElMessage.error('提交失败: ' + (error.message || '未知错误'))
  } finally {
    submitting.value = false
  }
}

const handleCancel = () => {
  if (redirectTarget.value) {
    router.push(redirectTarget.value)
  } else {
    router.push('/tasks')
  }
}

watch(
  () => form.datasourceName,
  (value) => {
    if (!value) {
      form.datasourceType = ''
      return
    }
    const option = datasourceOptionMap[value]
    if (option) {
      form.datasourceType = option.type || ''
    }
  }
)

watch(
  () => [form.engine, form.dolphinNodeType],
  ([engine, nodeType]) => {
    if (engine !== 'dolphin' || nodeType !== 'SQL') {
      form.datasourceName = ''
      form.datasourceType = ''
    }
  }
)

onMounted(async () => {
  await fetchDatasourceOptions('')
  await loadTask()
  await applyRelationPreset()
})

onUnmounted(() => {
  if (datasourceSearchTimer) {
    clearTimeout(datasourceSearchTimer)
  }
  if (tableSearchTimer) {
    clearTimeout(tableSearchTimer)
  }
})
</script>

<style scoped>
.task-form {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.datasource-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 200px;
}

.datasource-hint {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #64748b;
}

.datasource-db {
  color: #1f2937;
}
</style>
