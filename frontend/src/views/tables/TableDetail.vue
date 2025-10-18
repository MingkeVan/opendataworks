<template>
  <div class="table-detail" v-loading="loading">
    <el-page-header content="表详情" @back="goBack" class="page-header">
      <template #extra>
        <el-button type="primary" link @click="goLineage" :disabled="!table">
          查看血缘关系
        </el-button>
      </template>
    </el-page-header>

    <el-result v-if="!loading && !table" icon="warning" title="未找到表信息" sub-title="请返回列表重新选择" />

    <template v-else>
      <el-row :gutter="20">
        <el-col :span="12">
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card-header">
                基本信息
              </div>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="表名">{{ table?.tableName }}</el-descriptions-item>
              <el-descriptions-item label="表注释">{{ table?.tableComment || '-' }}</el-descriptions-item>
              <el-descriptions-item label="数据分层">{{ table?.layer || '-' }}</el-descriptions-item>
              <el-descriptions-item label="业务域">{{ table?.businessDomain || '-' }}</el-descriptions-item>
              <el-descriptions-item label="数据域">{{ table?.dataDomain || '-' }}</el-descriptions-item>
              <el-descriptions-item label="自定义标识">{{ table?.customIdentifier || '-' }}</el-descriptions-item>
              <el-descriptions-item label="统计周期">{{ table?.statisticsCycle || '无' }}</el-descriptions-item>
              <el-descriptions-item label="更新类型">{{ table?.updateType || '-' }}</el-descriptions-item>
              <el-descriptions-item label="数据库">{{ table?.dbName || '-' }}</el-descriptions-item>
              <el-descriptions-item label="负责人">{{ table?.owner || '-' }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag :type="tableStatusType">{{ table?.status || '-' }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="同步状态">
                <el-tag :type="table?.isSynced ? 'success' : 'info'">
                  {{ table?.isSynced ? '已同步' : '未同步' }}
                </el-tag>
                <span v-if="table?.syncTime" class="text-muted"> ({{ table?.syncTime }})</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>

        <el-col :span="12">
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card-header">
                Doris 配置
              </div>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="表模型">{{ table?.tableModel || '-' }}</el-descriptions-item>
              <el-descriptions-item label="主键列">{{ table?.keyColumns || '-' }}</el-descriptions-item>
              <el-descriptions-item label="分区字段">{{ table?.partitionColumn || '-' }}</el-descriptions-item>
              <el-descriptions-item label="分桶字段">{{ table?.distributionColumn || '-' }}</el-descriptions-item>
              <el-descriptions-item label="分桶数">{{ table?.bucketNum || '-' }}</el-descriptions-item>
              <el-descriptions-item label="副本数">{{ table?.replicaNum || '-' }}</el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never" class="card" v-if="table">
        <template #header>
          <div class="card-header">
            表统计信息
            <el-button
              text
              size="small"
              @click="refreshStatistics"
              :loading="statisticsLoading"
              :disabled="!table?.isSynced"
            >
              刷新
            </el-button>
          </div>
        </template>
        <div v-if="!table?.isSynced" class="statistics-placeholder">
          <el-alert
            title="表尚未同步到 Doris"
            type="info"
            :closable="false"
            show-icon
          >
            <template #default>
              请先将表同步到 Doris 后才能查看统计信息
            </template>
          </el-alert>
        </div>
        <div v-else-if="statisticsError" class="statistics-placeholder">
          <el-alert
            :title="statisticsError"
            type="error"
            :closable="false"
            show-icon
          />
        </div>
        <div v-else-if="!statistics" class="statistics-placeholder">
          <el-empty description="点击刷新按钮获取统计信息" />
        </div>
        <template v-else>
          <el-row :gutter="20">
            <el-col :span="8">
              <div class="stat-card">
                <div class="stat-label">数据行数</div>
                <div class="stat-value">{{ formatNumber(statistics.rowCount) }}</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="stat-card">
                <div class="stat-label">数据大小</div>
                <div class="stat-value">{{ statistics.dataSizeReadable || '-' }}</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="stat-card">
                <div class="stat-label">分区数量</div>
                <div class="stat-value">{{ statistics.partitionCount || '-' }}</div>
              </div>
            </el-col>
          </el-row>
          <el-descriptions :column="2" border class="mt-16">
            <el-descriptions-item label="数据库">{{ statistics.databaseName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="表类型">{{ statistics.tableType || '-' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(statistics.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="最后更新时间">{{ formatDateTime(statistics.lastUpdateTime) }}</el-descriptions-item>
            <el-descriptions-item label="副本数">{{ statistics.replicationNum || '-' }}</el-descriptions-item>
            <el-descriptions-item label="分桶数">{{ statistics.bucketNum || '-' }}</el-descriptions-item>
            <el-descriptions-item label="表引擎">{{ statistics.engine || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statistics.available ? 'success' : 'danger'">
                {{ statistics.available ? '可用' : '不可用' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <div class="stat-footer">
            <span class="text-muted">统计时间：{{ formatDateTime(statistics.lastCheckTime) }}</span>
          </div>
        </template>
      </el-card>

      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-header">
            表名配置
            <el-tag size="small" type="info" v-if="autoGenerate">自动生成</el-tag>
          </div>
        </template>

        <el-form
          ref="tableNameFormRef"
          :model="tableNameForm"
          :rules="tableNameRules"
          label-width="120px"
          class="table-name-form"
        >
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="数据分层" prop="layer">
                <el-select v-model="tableNameForm.layer" placeholder="选择数据分层">
                  <el-option
                    v-for="item in layerOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="业务域" prop="businessDomain">
                <el-select
                  v-model="tableNameForm.businessDomain"
                  placeholder="选择业务域"
                  @change="handleBusinessDomainChange"
                >
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
                <el-select
                  v-model="tableNameForm.dataDomain"
                  placeholder="选择数据域"
                  :disabled="!tableNameForm.businessDomain"
                >
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
                <el-input
                  v-model="tableNameForm.customIdentifier"
                  placeholder="如: cmp_performance"
                  @blur="handleCustomIdentifierBlur"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="统计周期">
                <el-select
                  v-model="tableNameForm.statisticsCycle"
                  placeholder="可选"
                  clearable
                >
                  <el-option label="无" value="" />
                  <el-option
                    v-for="item in statisticsOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="更新类型" prop="updateType">
                <el-select v-model="tableNameForm.updateType" placeholder="选择更新类型">
                  <el-option
                    v-for="item in updateTypeOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="自动生成">
                <div class="auto-toggle">
                  <el-switch v-model="autoGenerate" active-text="开启" inactive-text="关闭" />
                  <el-button
                    type="primary"
                    link
                    @click="regenerateTableName"
                    :disabled="!canGenerateName()"
                  >
                    重新生成
                  </el-button>
                </div>
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="表名" prop="tableName">
            <el-input
              v-model="tableNameForm.tableName"
              placeholder="自动生成或手动输入"
              @input="handleTableNameInput"
            >
              <template #append>
                <el-button @click="copyGeneratedName" :disabled="!tableNameForm.tableName">
                  复制
                </el-button>
              </template>
            </el-input>
          </el-form-item>

          <div class="form-actions">
            <el-button @click="resetTableNameForm" :disabled="!isTableNameDirty">重置</el-button>
            <el-button
              type="primary"
              :loading="saving"
              :disabled="!isTableNameDirty"
              @click="handleSaveTableName"
            >
              保存
            </el-button>
          </div>
        </el-form>
      </el-card>

      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-header">
            字段列表
          </div>
        </template>

        <el-table :data="fields" border>
          <el-table-column prop="fieldName" label="字段名" width="180" />
          <el-table-column prop="fieldType" label="类型" width="150" />
          <el-table-column prop="isNullable" label="可为空" width="100">
            <template #default="{ row }">
              <el-tag :type="row.isNullable ? 'success' : 'danger'">
                {{ row.isNullable ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="isPartition" label="分区字段" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.isPartition" type="warning">是</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="isPrimary" label="主键" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.isPrimary" type="info">是</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="defaultValue" label="默认值" width="160" />
          <el-table-column prop="fieldComment" label="注释" />
        </el-table>
        <div v-if="!fields.length" class="empty-block">
          <el-empty description="暂无字段信息" />
        </div>
      </el-card>

      <el-row :gutter="20">
        <el-col :span="12">
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card-header">关联任务</div>
            </template>

            <el-empty v-if="!hasRelatedTasks" description="尚未关联任何任务" />

            <template v-else>
              <div class="task-section" v-if="relatedTasks.writeTasks.length">
                <h4>写入任务</h4>
                <div class="task-list">
                  <div v-for="task in relatedTasks.writeTasks" :key="task.id" class="task-item">
                    <div class="task-title">
                      <el-link type="primary" @click="goTaskDetail(task.id)">
                        {{ task.taskName }}
                      </el-link>
                      <el-tag size="small" :type="taskStatusTag(task.status)">{{ task.status || '-' }}</el-tag>
                    </div>
                    <div class="task-meta">
                      <span>编码: {{ task.taskCode }}</span>
                      <span>引擎: {{ task.engine }}</span>
                      <span v-if="task.scheduleCron">调度: {{ task.scheduleCron }}</span>
                    </div>
                    <div class="task-meta">
                      <span>最近执行: {{ task.lastExecuted || '未执行' }}</span>
                      <span v-if="task.lastExecutionStatus">状态: {{ task.lastExecutionStatus }}</span>
                    </div>
                  </div>
                </div>
              </div>
              <div class="task-section" v-if="relatedTasks.readTasks.length">
                <h4>读取任务</h4>
                <div class="task-list">
                  <div v-for="task in relatedTasks.readTasks" :key="task.id" class="task-item">
                    <div class="task-title">
                      <el-link type="primary" @click="goTaskDetail(task.id)">
                        {{ task.taskName }}
                      </el-link>
                      <el-tag size="small" :type="taskStatusTag(task.status)">{{ task.status || '-' }}</el-tag>
                    </div>
                    <div class="task-meta">
                      <span>编码: {{ task.taskCode }}</span>
                      <span>引擎: {{ task.engine }}</span>
                      <span v-if="task.scheduleCron">调度: {{ task.scheduleCron }}</span>
                    </div>
                    <div class="task-meta">
                      <span>最近执行: {{ task.lastExecuted || '未执行' }}</span>
                      <span v-if="task.lastExecutionStatus">状态: {{ task.lastExecutionStatus }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </template>
          </el-card>
        </el-col>

        <el-col :span="12">
          <el-card shadow="never" class="card">
            <template #header>
              <div class="card-header">上下游表</div>
            </template>

            <el-row>
              <el-col :span="12">
                <h4>上游</h4>
                <div v-if="lineage.upstreamTables.length" class="lineage-list">
                  <div v-for="item in lineage.upstreamTables" :key="item.id" class="lineage-item">
                    <el-icon class="arrow"><Top /></el-icon>
                    <router-link :to="`/tables/${item.id}`">{{ item.tableName }}</router-link>
                    <span class="text-muted">{{ item.tableComment }}</span>
                  </div>
                </div>
                <el-empty v-else description="暂无上游" />
              </el-col>
              <el-col :span="12">
                <h4>下游</h4>
                <div v-if="lineage.downstreamTables.length" class="lineage-list">
                  <div v-for="item in lineage.downstreamTables" :key="item.id" class="lineage-item">
                    <el-icon class="arrow down"><Bottom /></el-icon>
                    <router-link :to="`/tables/${item.id}`">{{ item.tableName }}</router-link>
                    <span class="text-muted">{{ item.tableComment }}</span>
                  </div>
                </div>
                <el-empty v-else description="暂无下游" />
              </el-col>
            </el-row>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never" class="card">
        <template #header>
          <div class="card-header">
            Doris 建表语句
            <el-button text size="small" @click="copyDdl" :disabled="!table?.dorisDdl">
              复制
            </el-button>
          </div>
        </template>
        <el-input
          :value="table?.dorisDdl || ''"
          type="textarea"
          :rows="12"
          readonly
          class="ddl-block"
          placeholder="暂无 Doris 建表语句"
        />
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Top, Bottom } from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { tableDesignerApi } from '@/api/tableDesigner'

const router = useRouter()
const route = useRoute()

const table = ref(null)
const fields = ref([])
const relatedTasks = ref({ writeTasks: [], readTasks: [] })
const lineage = ref({ upstreamTables: [], downstreamTables: [] })
const statistics = ref(null)
const loading = ref(false)
const saving = ref(false)
const statisticsLoading = ref(false)
const statisticsError = ref('')

const tableNameFormRef = ref()
const tableNameForm = reactive({
  tableName: '',
  layer: '',
  businessDomain: '',
  dataDomain: '',
  customIdentifier: '',
  statisticsCycle: '',
  updateType: ''
})

const businessDomainOptions = ref([])
const dataDomainOptions = ref([])
const autoGenerate = ref(true)
const initializingForm = ref(false)
const initializedForm = ref(false)

const TABLE_NAME_PATTERN = /^[a-z0-9_]+$/

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

const tableNameRules = {
  layer: [{ required: true, message: '请选择数据分层', trigger: 'change' }],
  businessDomain: [{ required: true, message: '请选择业务域', trigger: 'change' }],
  dataDomain: [{ required: true, message: '请选择数据域', trigger: 'change' }],
  customIdentifier: [
    { required: true, message: '请输入自定义标识', trigger: 'blur' },
    { pattern: TABLE_NAME_PATTERN, message: '自定义标识仅支持小写字母、数字和下划线', trigger: 'blur' }
  ],
  updateType: [{ required: true, message: '请选择更新类型', trigger: 'change' }],
  tableName: [
    { required: true, message: '表名不能为空', trigger: 'blur' },
    { pattern: TABLE_NAME_PATTERN, message: '表名仅支持小写字母、数字和下划线', trigger: 'blur' }
  ]
}

const tableStatusType = computed(() => {
  if (!table.value) return ''
  switch (table.value.status) {
    case 'active':
      return 'success'
    case 'inactive':
      return 'warning'
    case 'deprecated':
      return 'info'
    default:
      return ''
  }
})

const hasRelatedTasks = computed(
  () =>
    relatedTasks.value.writeTasks.length > 0 ||
    relatedTasks.value.readTasks.length > 0
)

const normalizeCompareValue = (value) => (value ?? '').toString()

const isTableNameDirty = computed(() => {
  if (!table.value) return false
  const source = table.value
  const target = tableNameForm
  const fields = [
    'tableName',
    'layer',
    'businessDomain',
    'dataDomain',
    'customIdentifier',
    'statisticsCycle',
    'updateType'
  ]
  return fields.some(
    (field) => normalizeCompareValue(target[field]) !== normalizeCompareValue(source[field])
  )
})

const canGenerateName = () =>
  !!(
    tableNameForm.layer &&
    tableNameForm.businessDomain &&
    tableNameForm.dataDomain &&
    tableNameForm.customIdentifier &&
    tableNameForm.updateType
  )

const normalizeSegment = (value) =>
  value ? value.trim().toLowerCase().replace(/\s+/g, '_').replace(/-+/g, '_') : ''

const loadBusinessDomains = async () => {
  try {
    businessDomainOptions.value = await businessDomainApi.list()
  } catch (error) {
    console.error('加载业务域失败', error)
    businessDomainOptions.value = []
  }
}

const loadDataDomains = async (businessDomain) => {
  if (!businessDomain) {
    dataDomainOptions.value = []
    return
  }
  try {
    dataDomainOptions.value = await dataDomainApi.list({ businessDomain })
  } catch (error) {
    console.error('加载数据域失败', error)
    dataDomainOptions.value = []
  }
}

const applyTableNameForm = async (data) => {
  if (!data) return
  initializingForm.value = true
  initializedForm.value = false
  tableNameForm.layer = data.layer || ''
  tableNameForm.businessDomain = normalizeSegment(data.businessDomain || '')
  await loadDataDomains(tableNameForm.businessDomain)
  if (
    tableNameForm.businessDomain &&
    !businessDomainOptions.value.some((item) => item.domainCode === tableNameForm.businessDomain)
  ) {
    businessDomainOptions.value.push({
      domainCode: tableNameForm.businessDomain,
      domainName: tableNameForm.businessDomain
    })
  }
  tableNameForm.dataDomain = normalizeSegment(data.dataDomain || '')
  if (
    tableNameForm.dataDomain &&
    !dataDomainOptions.value.some((item) => item.domainCode === tableNameForm.dataDomain)
  ) {
    dataDomainOptions.value.push({
      domainCode: tableNameForm.dataDomain,
      domainName: tableNameForm.dataDomain,
      businessDomain: tableNameForm.businessDomain
    })
  }
  tableNameForm.customIdentifier = normalizeSegment(data.customIdentifier || '')
  tableNameForm.statisticsCycle = data.statisticsCycle || ''
  tableNameForm.updateType = data.updateType || ''
  tableNameForm.tableName = data.tableName || ''
  autoGenerate.value = true
  await nextTick()
  initializedForm.value = true
  initializingForm.value = false
}

const generateTableName = async (force = false) => {
  if (!initializedForm.value || initializingForm.value) return
  if (!canGenerateName()) return
  if (!autoGenerate.value && !force) return
  try {
    const payload = {
      layer: tableNameForm.layer,
      businessDomain: tableNameForm.businessDomain,
      dataDomain: tableNameForm.dataDomain,
      customIdentifier: tableNameForm.customIdentifier,
      statisticsCycle: tableNameForm.statisticsCycle || null,
      updateType: tableNameForm.updateType
    }
    const name = await tableDesignerApi.generateTableName(payload)
    tableNameForm.tableName = name
  } catch (error) {
    console.error('表名生成失败', error)
    if (force || autoGenerate.value) {
      ElMessage.error(error?.message || '表名生成失败，请检查配置')
    }
  }
}

const regenerateTableName = async () => {
  autoGenerate.value = true
  await generateTableName(true)
}

const handleCustomIdentifierBlur = () => {
  tableNameForm.customIdentifier = normalizeSegment(tableNameForm.customIdentifier)
}

const handleTableNameInput = () => {
  if (autoGenerate.value) {
    autoGenerate.value = false
  }
}

const copyGeneratedName = async () => {
  if (!tableNameForm.tableName) return
  try {
    await navigator.clipboard.writeText(tableNameForm.tableName)
    ElMessage.success('表名已复制')
  } catch (error) {
    console.error('复制表名失败', error)
    ElMessage.error('复制失败，请手动复制')
  }
}

const resetTableNameForm = async () => {
  if (!table.value) return
  await applyTableNameForm(table.value)
}

const handleSaveTableName = async () => {
  if (!table.value) return
  try {
    await tableNameFormRef.value.validate()
  } catch (error) {
    return
  }
  saving.value = true
  try {
    const payload = {
      ...table.value,
      tableName: tableNameForm.tableName,
      layer: tableNameForm.layer,
      businessDomain: tableNameForm.businessDomain,
      dataDomain: tableNameForm.dataDomain,
      customIdentifier: tableNameForm.customIdentifier,
      statisticsCycle: tableNameForm.statisticsCycle || null,
      updateType: tableNameForm.updateType
    }
    const updated = await tableApi.update(table.value.id, payload)
    table.value = updated
    await applyTableNameForm(updated)
    ElMessage.success('表名配置已保存')
  } catch (error) {
    console.error('保存表名失败', error)
    ElMessage.error(error?.message || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

const handleBusinessDomainChange = async () => {
  tableNameForm.businessDomain = normalizeSegment(tableNameForm.businessDomain)
  await loadDataDomains(tableNameForm.businessDomain)
  if (
    tableNameForm.dataDomain &&
    !dataDomainOptions.value.some((item) => item.domainCode === tableNameForm.dataDomain)
  ) {
    tableNameForm.dataDomain = ''
  }
  await generateTableName()
}

const loadDetail = async (id) => {
  loading.value = true
  try {
    const [tableInfo, fieldList, tasks, lineageInfo] = await Promise.all([
      tableApi.getById(id),
      tableApi.getFields(id),
      tableApi.getTasks(id),
      tableApi.getLineage(id)
    ])
    table.value = tableInfo
    fields.value = fieldList
    relatedTasks.value = tasks
    lineage.value = lineageInfo
    await applyTableNameForm(tableInfo)
  } catch (error) {
    console.error('加载表详情失败', error)
    ElMessage.error('加载表详情失败')
    table.value = null
  } finally {
    loading.value = false
  }
}

const goBack = () => {
  router.push('/tables')
}

const goLineage = () => {
  if (table.value) {
    router.push({
      path: '/lineage',
      query: { focus: table.value.tableName }
    })
  }
}

const copyDdl = async () => {
  if (!table.value?.dorisDdl) return
  try {
    await navigator.clipboard.writeText(table.value.dorisDdl)
    ElMessage.success('已复制到剪贴板')
  } catch (error) {
    console.error('复制失败', error)
    ElMessage.error('复制失败，请手动复制')
  }
}

const taskStatusTag = (status) => {
  if (!status) return ''
  switch (status) {
    case 'published':
    case 'running':
      return 'success'
    case 'paused':
      return 'warning'
    case 'failed':
      return 'danger'
    default:
      return ''
  }
}

const goTaskDetail = (taskId) => {
  router.push(`/tasks/${taskId}/edit`)
}

const refreshStatistics = async () => {
  if (!table.value || !table.value.isSynced) return

  statisticsLoading.value = true
  statisticsError.value = ''
  try {
    const data = await tableApi.getStatistics(table.value.id)
    statistics.value = data
  } catch (error) {
    console.error('加载统计信息失败', error)
    statisticsError.value = error?.message || '加载统计信息失败'
    statistics.value = null
  } finally {
    statisticsLoading.value = false
  }
}

const formatNumber = (num) => {
  if (num === null || num === undefined) return '-'
  return num.toLocaleString('zh-CN')
}

const formatDateTime = (dateTime) => {
  if (!dateTime) return '-'
  try {
    const date = new Date(dateTime)
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    })
  } catch (error) {
    return dateTime
  }
}

const init = () => {
  const id = Number(route.params.id)
  if (!id) {
    table.value = null
    return
  }
  loadDetail(id)
}

watch(
  () => [
    tableNameForm.layer,
    tableNameForm.businessDomain,
    tableNameForm.dataDomain,
    tableNameForm.customIdentifier,
    tableNameForm.statisticsCycle,
    tableNameForm.updateType
  ],
  () => {
    if (!initializedForm.value || initializingForm.value) return
    if (!autoGenerate.value) return
    generateTableName()
  }
)

watch(autoGenerate, (value) => {
  if (initializingForm.value) return
  if (value) {
    generateTableName(true)
  }
})

watch(
  () => route.params.id,
  () => {
    init()
  }
)

onMounted(() => {
  loadBusinessDomains()
  init()
})
</script>

<style scoped>
.table-detail {
  padding-bottom: 40px;
}

.page-header {
  margin-bottom: 16px;
}

.card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.text-muted {
  color: #909399;
  margin-left: 4px;
}

.empty-block {
  margin-top: 16px;
}

.table-name-form {
  padding-top: 8px;
}

.auto-toggle {
  display: flex;
  align-items: center;
  gap: 12px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 12px;
}

.task-section + .task-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #ebeef5;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task-item {
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background-color: #fafafa;
}

.task-title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 600;
  margin-bottom: 8px;
}

.task-meta {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #606266;
}

.lineage-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
}

.lineage-item {
  display: flex;
  flex-direction: column;
}

.lineage-item .text-muted {
  font-size: 12px;
}

.lineage-item a {
  color: #409eff;
  font-weight: 600;
}

.arrow {
  color: #67c23a;
  margin-right: 4px;
}

.arrow.down {
  color: #409eff;
}

.ddl-block {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.statistics-placeholder {
  padding: 20px;
}

.stat-card {
  padding: 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 8px;
  color: white;
  text-align: center;
}

.stat-label {
  font-size: 14px;
  opacity: 0.9;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
}

.mt-16 {
  margin-top: 16px;
}

.stat-footer {
  margin-top: 12px;
  text-align: right;
  font-size: 12px;
}
</style>
