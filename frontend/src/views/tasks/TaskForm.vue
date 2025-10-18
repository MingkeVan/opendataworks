<template>
  <div class="task-form">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>{{ isEdit ? '编辑任务' : '创建任务' }}</span>
          <el-button @click="$router.back()">返回</el-button>
        </div>
      </template>

      <el-form :model="form" :rules="rules" ref="formRef" label-width="120px" style="max-width: 800px">
        <el-form-item label="任务名称" prop="taskName">
          <el-input v-model="form.taskName" />
        </el-form-item>

        <el-form-item label="任务编码" prop="taskCode">
          <el-input v-model="form.taskCode" :disabled="isEdit" />
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
        <el-form-item label="数据源名称" prop="datasourceName" v-if="form.engine === 'dolphin' && form.dolphinNodeType === 'SQL'">
          <el-input v-model="form.datasourceName" placeholder="例如: doris_test" />
        </el-form-item>

        <el-form-item label="数据源类型" prop="datasourceType" v-if="form.engine === 'dolphin' && form.dolphinNodeType === 'SQL'">
          <el-select v-model="form.datasourceType" style="width: 100%">
            <el-option label="DORIS" value="DORIS" />
            <el-option label="MYSQL" value="MYSQL" />
            <el-option label="POSTGRESQL" value="POSTGRESQL" />
            <el-option label="CLICKHOUSE" value="CLICKHOUSE" />
            <el-option label="HIVE" value="HIVE" />
          </el-select>
        </el-form-item>

        <el-form-item label="任务SQL" prop="taskSql">
          <el-input v-model="form.taskSql" type="textarea" :rows="10" placeholder="输入 SQL 语句..." />
        </el-form-item>

        <el-form-item label="输入表" prop="inputTableIds">
          <el-select v-model="form.inputTableIds" multiple placeholder="选择输入表" style="width: 100%">
            <el-option
              v-for="table in tables"
              :key="table.id"
              :label="`${table.tableName} (${table.layer})`"
              :value="table.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="输出表" prop="outputTableIds">
          <el-select v-model="form.outputTableIds" multiple placeholder="选择输出表" style="width: 100%">
            <el-option
              v-for="table in tables"
              :key="table.id"
              :label="`${table.tableName} (${table.layer})`"
              :value="table.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="调度配置" prop="scheduleCron">
          <el-input v-model="form.scheduleCron" placeholder="例如: 0 3 * * * (每天凌晨3点)" />
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
          <el-button @click="$router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { taskApi } from '@/api/task'
import { tableApi } from '@/api/table'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)

const formRef = ref(null)
const submitting = ref(false)
const tables = ref([])

const form = reactive({
  taskName: '',
  taskCode: '',
  taskType: 'batch',
  engine: 'dolphin',
  dolphinNodeType: 'SQL',
  datasourceName: 'doris_test',
  datasourceType: 'DORIS',
  taskSql: '',
  scheduleCron: '',
  priority: 5,
  timeoutSeconds: 3600,
  retryTimes: 0,
  retryInterval: 60,
  owner: '',
  taskDesc: '',
  inputTableIds: [],
  outputTableIds: []
})

const rules = {
  taskName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  taskCode: [{ required: true, message: '请输入任务编码', trigger: 'blur' }],
  taskSql: [{ required: true, message: '请输入任务SQL', trigger: 'blur' }],
  dolphinNodeType: [{ required: true, message: '请选择节点类型', trigger: 'change' }],
  datasourceName: [
    { required: true, message: '请输入数据源名称', trigger: 'blur' }
  ],
  datasourceType: [
    { required: true, message: '请选择数据源类型', trigger: 'change' }
  ]
}

const loadTables = async () => {
  try {
    tables.value = await tableApi.listAll()
  } catch (error) {
    console.error('加载表列表失败:', error)
  }
}

const loadTask = async () => {
  if (!isEdit.value) return
  try {
    const task = await taskApi.getById(route.params.id)
    Object.assign(form, task)

    // 加载血缘关系数据
    const lineage = await taskApi.getTaskLineage(route.params.id)
    form.inputTableIds = lineage.inputTableIds || []
    form.outputTableIds = lineage.outputTableIds || []
  } catch (error) {
    console.error('加载任务失败:', error)
  }
}

const handleSubmit = async () => {
  await formRef.value.validate()
  submitting.value = true
  try {
    // 准备提交数据，分离表信息
    const { inputTableIds, outputTableIds, ...taskData } = form
    const data = {
      task: taskData,
      inputTableIds: inputTableIds || [],
      outputTableIds: outputTableIds || []
    }

    if (isEdit.value) {
      await taskApi.update(route.params.id, data)
      ElMessage.success('更新成功')
    } else {
      await taskApi.create(data)
      ElMessage.success('创建成功')
    }
    router.push('/tasks')
  } catch (error) {
    console.error('提交失败:', error)
    ElMessage.error('提交失败: ' + (error.message || '未知错误'))
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadTables()
  loadTask()
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
</style>
