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
      :model="form"
      :rules="rules"
      ref="formRef"
      label-width="120px"
      style="padding-right: 20px"
    >
      <el-form-item label="任务名称" prop="task.taskName">
        <el-input v-model="form.task.taskName" placeholder="请输入任务名称" />
      </el-form-item>
      
      <el-form-item label="任务描述" prop="task.taskDesc">
        <el-input v-model="form.task.taskDesc" type="textarea" :rows="3" placeholder="请输入任务描述" />
      </el-form-item>

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
        <div class="sql-editor-container">
          <el-input
            v-model="form.task.taskSql"
            type="textarea"
            :rows="12"
            placeholder="请输入 SQL 脚本"
            font-family="monospace"
          />
        </div>
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

    <!-- Test Run Result Dialog -->

  </el-drawer>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { taskApi } from '@/api/task'

import { tableApi } from '@/api/table'

const emit = defineEmits(['saved', 'close'])

const visible = ref(false)
const isEdit = ref(false)
const loading = ref(false)
const formRef = ref(null)



const fetchDatasourceOptions = async () => {
    try {
        const res = await taskApi.fetchDatasources()
        datasourceOptions.value = res || []
    } catch (error) {
        console.error('获取数据源失败:', error)
        ElMessage.error('获取数据源失败')
    }
}

const datasourceOptions = ref([])

// Table search state
const tableOptions = ref([])
const tableLoading = ref(false)
const tableOptionCache = reactive({})
let tableSearchTimer = null

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
    database: ''
  },
  inputTableIds: [],
  outputTableIds: []
})

const rules = {
  'task.taskName': [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  'task.taskName': [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  'task.datasourceName': [{ required: true, message: '请选择数据源', trigger: 'change' }],
  'task.taskSql': [{ required: true, message: 'SQL 脚本不能为空', trigger: 'blur' }]
}

const availableTableOptions = computed(() => {
  const seen = new Set()
  const result = []

  const append = (option) => {
    if (!option || !option.id || seen.has(option.id)) return
    result.push(option)
    seen.add(option.id)
  }

  tableOptions.value.forEach(append)
  
  // Add selected options if they are not in the current search results
  ;[...(form.inputTableIds || []), ...(form.outputTableIds || [])].forEach((id) => {
    const cached = tableOptionCache[id]
    if (cached) {
      append(cached)
    }
  })
  return result
})

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
  const uniqueIds = [...new Set(ids)].filter(
    (id) => id && !tableOptionCache[id]
  )
  if (!uniqueIds.length) return
  try {
    const tables = await Promise.all(
      uniqueIds.map((id) => tableApi.getById(id))
    )
    const options = tables.filter(Boolean).map((table) => ({
      id: table.id,
      tableName: table.tableName,
      tableComment: table.tableComment,
      layer: table.layer,
      dbName: table.dbName,
    }))
    upsertTableOptions(options)
  } catch (error) {
    console.error('加载表选项失败:', error)
  }
}

const open = async (id = null, initialData = {}) => {
  visible.value = true
  loading.value = false
  isEdit.value = !!id
  
  // Reset form
  resetForm()
  
  // Fetch datasources
  await fetchDatasourceOptions()

  if (id) {
    // Edit mode
    try {
      const res = await taskApi.getById(id)
      const taskData = res || {}
      Object.assign(form.task, taskData)
      
      // Load lineage data (input/output tables)
      const lineage = await taskApi.getTaskLineage(id)
      form.inputTableIds = Array.isArray(lineage.inputTableIds) ? lineage.inputTableIds : []
      form.outputTableIds = Array.isArray(lineage.outputTableIds) ? lineage.outputTableIds : []
      
      // Ensure options are loaded for selected tables
      await ensureTableOptionsLoaded([...form.inputTableIds, ...form.outputTableIds])

    } catch (error) {
       console.error(error)
       ElMessage.error('加载任务详情失败')
    }
  } else {
    // Create mode
    if (initialData.taskSql) {
       form.task.taskSql = initialData.taskSql
    }
    
    // Set workflowId if provided
    if (initialData.workflowId) {
        form.task.workflowId = initialData.workflowId
    }
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
        workflowId: null
    }
    form.inputTableIds = []
    form.outputTableIds = []
    tableOptions.value = []
}

defineExpose({
  open
})
</script>

<style scoped>
.sql-editor-container {
  height: 300px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}

</style>
