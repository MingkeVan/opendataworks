<template>
  <el-drawer
    :model-value="modelValue"
    size="80%"
    title="新建表"
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <el-scrollbar class="drawer-body">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" class="form">
        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-header">基础信息</div>
          </template>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="数据分层" prop="layer">
                <el-select v-model="form.layer" placeholder="选择数据分层">
                  <el-option v-for="layer in layerOptions" :key="layer.value" :label="layer.label" :value="layer.value" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="业务域" prop="businessDomain">
                <el-select v-model="form.businessDomain" placeholder="选择业务域" @change="handleBusinessDomainChange">
                  <el-option
                    v-for="item in businessDomainOptions"
                    :key="item.domainCode"
                    :label="`${item.domainCode} - ${item.domainName}`"
                    :value="item.domainCode"
                  />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="数据域" prop="dataDomain">
                <el-select v-model="form.dataDomain" placeholder="选择数据域" :disabled="!form.businessDomain">
                  <el-option
                    v-for="item in dataDomainOptions"
                    :key="item.domainCode"
                    :label="`${item.domainCode} - ${item.domainName}`"
                    :value="item.domainCode"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="自定义标识" prop="customIdentifier">
                <el-input v-model="form.customIdentifier" placeholder="如: cmp_performance" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="统计周期">
                <el-select v-model="form.statisticsCycle" placeholder="可选">
                  <el-option label="无" value="" />
                  <el-option v-for="option in statisticsOptions" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="更新类型" prop="updateType">
                <el-select v-model="form.updateType">
                  <el-option v-for="option in updateTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="数据库名" prop="dbName">
                <el-input v-model="form.dbName" placeholder="如: doris_dwd" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="负责人" prop="owner">
                <el-input v-model="form.owner" placeholder="负责人" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="表注释">
            <el-input v-model="form.tableComment" type="textarea" :rows="2" placeholder="请输入表业务含义" />
          </el-form-item>

          <el-descriptions :column="1" border v-if="preview.tableName" class="preview-name">
            <el-descriptions-item label="表名">{{ preview.tableName }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-header">
              字段定义
              <el-button type="primary" text size="small" @click="addColumn">
                <el-icon><Plus /></el-icon>
                添加字段
              </el-button>
            </div>
          </template>

          <el-table :data="form.columns" border style="width: 100%">
            <el-table-column prop="columnName" label="字段名" width="160">
              <template #default="{ row }">
                <el-input v-model="row.columnName" placeholder="字段名" @change="schedulePreview" />
              </template>
            </el-table-column>
            <el-table-column prop="dataType" label="数据类型" width="180">
              <template #default="{ row }">
                <el-select v-model="row.dataType" placeholder="类型" filterable @change="schedulePreview">
                  <el-option-group v-for="group in dataTypeOptions" :key="group.label" :label="group.label">
                    <el-option v-for="item in group.options" :key="item" :label="item" :value="item" />
                  </el-option-group>
                </el-select>
              </template>
            </el-table-column>
            <el-table-column prop="typeParams" label="类型参数" width="140">
              <template #default="{ row }">
                <el-input v-model="row.typeParams" placeholder="如: (255)" @change="schedulePreview" />
              </template>
            </el-table-column>
            <el-table-column prop="nullable" label="可为空" width="100">
              <template #default="{ row }">
                <el-switch v-model="row.nullable" @change="schedulePreview" />
              </template>
            </el-table-column>
            <el-table-column prop="defaultValue" label="默认值" width="150">
              <template #default="{ row }">
                <el-input v-model="row.defaultValue" placeholder="默认值" @change="schedulePreview" />
              </template>
            </el-table-column>
            <el-table-column prop="comment" label="字段注释">
              <template #default="{ row }">
                <el-input v-model="row.comment" placeholder="字段说明" @change="schedulePreview" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeColumn($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="!form.columns.length" class="column-empty">
            <el-empty description="请添加字段定义" />
          </div>
        </el-card>

        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-header">Doris 配置与预览</div>
          </template>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="表模型" prop="tableModel">
                <el-select v-model="form.tableModel" placeholder="选择表模型" @change="schedulePreview">
                  <el-option v-for="item in tableModelOptions" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="主键列">
                <el-select
                  v-model="form.keyColumns"
                  multiple
                  collapse-tags
                  placeholder="选择主键列"
                  :disabled="!availableColumns.length"
                  @change="schedulePreview"
                >
                  <el-option v-for="col in availableColumns" :key="col" :label="col" :value="col" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="分区字段">
                <el-select
                  v-model="form.partitionColumn"
                  placeholder="选择分区字段"
                  clearable
                  :disabled="!availableColumns.length"
                  @change="schedulePreview"
                >
                  <el-option v-for="col in availableColumns" :key="col" :label="col" :value="col" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="分桶字段">
                <el-select
                  v-model="form.distributionColumns"
                  multiple
                  collapse-tags
                  placeholder="选择分桶字段"
                  :disabled="!availableColumns.length"
                  @change="schedulePreview"
                >
                  <el-option v-for="col in availableColumns" :key="col" :label="col" :value="col" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="分桶数">
                <el-input-number v-model="form.bucketNum" :min="1" :max="128" @change="schedulePreview" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="副本数">
                <el-input-number v-model="form.replicaNum" :min="1" :max="5" @change="schedulePreview" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="Doris 集群" prop="dorisClusterId">
                <el-select v-model="form.dorisClusterId" placeholder="选择 Doris 集群">
                  <el-option v-for="item in dorisClusterOptions" :key="item.id" :label="item.clusterName" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="同步到 Doris">
                <el-switch v-model="form.syncToDoris" />
              </el-form-item>
            </el-col>
          </el-row>

          <div class="preview-operations">
            <el-button @click="refreshPreview" :loading="preview.loading">刷新预览</el-button>
            <span class="preview-hint">修改字段或配置后可刷新预览生成最新 DDL</span>
          </div>

          <el-skeleton v-if="preview.loading" :rows="4" animated />
          <template v-else>
            <el-alert v-if="previewError" :title="previewError" type="warning" show-icon class="preview-alert" />
            <el-alert
              v-else-if="ddlEdited"
              title="DDL 已手动修改，请确保语法与 Doris 兼容"
              type="info"
              show-icon
              class="preview-alert"
            />
            <el-input
              v-model="preview.dorisDdl"
              type="textarea"
              :rows="12"
              class="ddl-preview"
              placeholder="可在此处校验并修改 Doris 建表语句"
              @input="onDdlInput"
            />
          </template>

          <div class="actions">
            <el-button @click="handleClose">取消</el-button>
            <el-button type="primary" :loading="submitting" @click="handleSubmit">创建表</el-button>
          </div>
        </el-card>
      </el-form>
    </el-scrollbar>
  </el-drawer>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { dorisClusterApi } from '@/api/doris'
import { tableDesignerApi } from '@/api/tableDesigner'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'created'])

const formRef = ref(null)
const submitting = ref(false)
const previewError = ref('')
const ddlEdited = ref(false)
let previewTimer = null

const defaultForm = () => ({
  layer: 'DWD',
  businessDomain: '',
  dataDomain: '',
  customIdentifier: '',
  statisticsCycle: '',
  updateType: 'di',
  dbName: '',
  tableComment: '',
  owner: '',
  tableModel: 'DUPLICATE',
  bucketNum: 10,
  replicaNum: 3,
  partitionColumn: '',
  distributionColumns: [],
  keyColumns: [],
  dorisClusterId: null,
  syncToDoris: true,
  columns: []
})

const form = reactive(defaultForm())

const preview = reactive({
  tableName: '',
  dorisDdl: '',
  loading: false
})

const layerOptions = [
  { label: 'ODS - 原始数据层', value: 'ODS' },
  { label: 'DWD - 明细数据层', value: 'DWD' },
  { label: 'DIM - 维度数据层', value: 'DIM' },
  { label: 'DWS - 汇总数据层', value: 'DWS' },
  { label: 'ADS - 应用数据层', value: 'ADS' }
]

const statisticsOptions = [
  { label: '10分钟', value: '10m' },
  { label: '30分钟', value: '30m' },
  { label: '1小时', value: '1h' },
  { label: '1天', value: '1d' },
  { label: '实时', value: 'realtime' }
]

const updateTypeOptions = [
  { label: 'di - 日增量', value: 'di' },
  { label: 'df - 日全量', value: 'df' },
  { label: 'hi - 小时增量', value: 'hi' },
  { label: 'hf - 小时全量', value: 'hf' },
  { label: 'ri - 实时增量', value: 'ri' }
]

const tableModelOptions = [
  { label: 'Duplicate Key', value: 'DUPLICATE' },
  { label: 'Unique Key', value: 'UNIQUE' },
  { label: 'Aggregate Key', value: 'AGGREGATE' }
]

const dataTypeOptions = [
  { label: '数值', options: ['TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT', 'FLOAT', 'DOUBLE', 'DECIMAL'] },
  { label: '字符串', options: ['CHAR', 'VARCHAR', 'STRING', 'TEXT'] },
  { label: '日期时间', options: ['DATE', 'DATETIME'] },
  { label: '其他', options: ['BOOLEAN', 'JSON'] }
]

const businessDomainOptions = ref([])
const dataDomainOptions = ref([])
const dorisClusterOptions = ref([])

const availableColumns = computed(() => form.columns.map(col => col.columnName).filter(Boolean))

const rules = {
  layer: [{ required: true, message: '请选择数据分层', trigger: 'change' }],
  businessDomain: [{ required: true, message: '请选择业务域', trigger: 'change' }],
  dataDomain: [{ required: true, message: '请选择数据域', trigger: 'change' }],
  customIdentifier: [{ required: true, message: '请输入自定义标识', trigger: 'blur' }],
  updateType: [{ required: true, message: '请选择更新类型', trigger: 'change' }],
  dbName: [{ required: true, message: '请输入数据库名', trigger: 'blur' }],
  owner: [{ required: true, message: '请输入负责人', trigger: 'blur' }],
  dorisClusterId: [{ required: true, message: '请选择 Doris 集群', trigger: 'change' }]
}

const schedulePreview = () => {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(refreshPreview, 600)
}

const handleClose = () => {
  emit('update:modelValue', false)
}

const resetForm = () => {
  Object.assign(form, defaultForm())
  form.columns = []
  preview.tableName = ''
  preview.dorisDdl = ''
  preview.loading = false
  previewError.value = ''
  ddlEdited.value = false
}

const addColumn = () => {
  form.columns.push({
    columnName: '',
    dataType: '',
    typeParams: '',
    nullable: true,
    defaultValue: '',
    comment: ''
  })
}

const removeColumn = (index) => {
  form.columns.splice(index, 1)
  sanitizeColumnSelections()
  schedulePreview()
}

const sanitizeColumnSelections = () => {
  const names = new Set(availableColumns.value)
  form.keyColumns = form.keyColumns.filter(col => names.has(col))
  form.distributionColumns = form.distributionColumns.filter(col => names.has(col))
  if (form.partitionColumn && !names.has(form.partitionColumn)) {
    form.partitionColumn = ''
  }
}

const handleBusinessDomainChange = async () => {
  form.dataDomain = ''
  await loadDataDomains()
  schedulePreview()
}

const loadBusinessDomains = async () => {
  businessDomainOptions.value = await businessDomainApi.list()
  if (!form.businessDomain && businessDomainOptions.value.length) {
    form.businessDomain = businessDomainOptions.value[0].domainCode
  }
}

const loadDataDomains = async () => {
  if (!form.businessDomain) {
    dataDomainOptions.value = []
    return
  }
  dataDomainOptions.value = await dataDomainApi.list({ businessDomain: form.businessDomain })
  if (!form.dataDomain && dataDomainOptions.value.length) {
    form.dataDomain = dataDomainOptions.value[0].domainCode
  }
}

const loadDorisClusters = async () => {
  const clusters = await dorisClusterApi.list()
  dorisClusterOptions.value = clusters
  if (!form.dorisClusterId && clusters.length) {
    const defaultCluster = clusters.find(item => item.isDefault === 1) || clusters[0]
    form.dorisClusterId = defaultCluster.id
  }
}

const refreshTableName = async () => {
  if (!canGenerateName()) {
    preview.tableName = ''
    return
  }
  try {
    const payload = {
      layer: form.layer,
      businessDomain: form.businessDomain,
      dataDomain: form.dataDomain,
      customIdentifier: form.customIdentifier,
      statisticsCycle: form.statisticsCycle || null,
      updateType: form.updateType
    }
    preview.tableName = await tableDesignerApi.generateTableName(payload)
  } catch (error) {
    console.error('生成表名失败', error)
  }
}

const refreshPreview = async () => {
  previewError.value = ''
  if (!canPreview()) {
    preview.dorisDdl = ''
    ddlEdited.value = false
    return
  }
  preview.loading = true
  try {
    const payload = buildRequestPayload()
    const result = await tableDesignerApi.preview(payload)
    preview.tableName = result.tableName
    ddlEdited.value = false
    preview.dorisDdl = result.dorisDdl
  } catch (error) {
    console.error('生成预览失败', error)
    previewError.value = error?.message || '生成 DDL 失败，请检查输入项'
  } finally {
    preview.loading = false
  }
}

const buildRequestPayload = () => ({
  layer: form.layer,
  businessDomain: form.businessDomain,
  dataDomain: form.dataDomain,
  customIdentifier: form.customIdentifier,
  statisticsCycle: form.statisticsCycle || null,
  updateType: form.updateType,
  dbName: form.dbName,
  tableComment: form.tableComment,
  owner: form.owner,
  tableModel: form.tableModel,
  bucketNum: form.bucketNum,
  replicaNum: form.replicaNum,
  partitionColumn: form.partitionColumn,
  distributionColumns: form.distributionColumns,
  keyColumns: form.keyColumns,
  dorisClusterId: form.dorisClusterId,
  syncToDoris: form.syncToDoris,
  dorisDdl: preview.dorisDdl,
  columns: form.columns.map(column => ({
    columnName: column.columnName,
    dataType: column.dataType,
    typeParams: column.typeParams,
    nullable: column.nullable,
    defaultValue: column.defaultValue,
    comment: column.comment,
    primaryKey: form.keyColumns.includes(column.columnName),
    partitionColumn: form.partitionColumn === column.columnName
  }))
})

const canGenerateName = () =>
  form.layer &&
  form.businessDomain &&
  form.dataDomain &&
  form.customIdentifier &&
  form.updateType

const canPreview = () =>
  canGenerateName() &&
  form.dbName &&
  form.dorisClusterId &&
  form.columns.length > 0 &&
  form.columns.every(col => col.columnName && col.dataType)

const handleSubmit = async () => {
  await formRef.value.validate()
  if (!canPreview()) {
    ElMessage.warning('请完善字段定义和 Doris 配置后再创建')
    return
  }
  if (!preview.dorisDdl || !preview.dorisDdl.trim()) {
    ElMessage.warning('请先生成并确认 Doris 建表语句')
    return
  }
  submitting.value = true
  try {
    const payload = buildRequestPayload()
    const result = await tableDesignerApi.create(payload)
    ElMessage.success('创建成功，已同步到 Doris')
    emit('created', result)
  } catch (error) {
    console.error('创建失败', error)
    ElMessage.error(error?.message || '创建表失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

const onDdlInput = () => {
  ddlEdited.value = true
}

const initDrawer = async () => {
  resetForm()
  addColumn()
  await loadBusinessDomains()
  await loadDataDomains()
  await loadDorisClusters()
  refreshTableName()
}

watch(
  () => [form.layer, form.businessDomain, form.dataDomain, form.customIdentifier, form.statisticsCycle, form.updateType],
  () => {
    refreshTableName()
    schedulePreview()
  }
)

watch(
  () => props.modelValue,
  (val) => {
    if (val) {
      initDrawer()
    } else {
      clearTimeout(previewTimer)
    }
  }
)

onBeforeUnmount(() => {
  clearTimeout(previewTimer)
})
</script>

<style scoped>
.drawer-body {
  padding: 0 4px 20px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card {
  border-radius: 10px;
}

.card-header {
  font-weight: 600;
  color: #1f2f3d;
}

.preview-name {
  margin-top: 8px;
}

.column-empty {
  padding: 12px 0;
}

.preview-operations {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}

.preview-hint {
  font-size: 12px;
  color: #909399;
}

.preview-alert {
  margin-bottom: 8px;
}

.ddl-preview :deep(.el-textarea__inner) {
  font-family: 'Fira Code', monospace;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
</style>
