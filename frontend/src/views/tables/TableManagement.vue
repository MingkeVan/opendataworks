<template>
  <div class="table-management">
    <div class="management-container">
      <!-- 左侧：数据库和表列表 -->
      <div class="left-panel">
        <el-card shadow="never" class="database-card">
          <template #header>
            <div class="card-header">
              <span class="title">数据库和表</span>
              <el-button type="primary" size="small" @click="goCreate">
                <el-icon><Plus /></el-icon>
                新建表
              </el-button>
            </div>
          </template>

          <!-- 搜索框 -->
          <el-input
            v-model="searchKeyword"
            placeholder="搜索表名..."
            clearable
            class="search-input"
            @input="handleSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>

          <!-- 数据库列表 -->
          <div class="database-list" v-loading="loading">
            <el-collapse v-model="activeDatabase" accordion>
              <el-collapse-item
                v-for="db in databases"
                :key="db"
                :name="db"
                @click="handleDatabaseClick(db)"
              >
                <template #title>
                  <div class="database-title">
                    <el-icon><Folder /></el-icon>
                    <span class="db-name">{{ db }}</span>
                    <el-badge
                      :value="getTableCount(db)"
                      class="table-count-badge"
                      type="info"
                    />
                  </div>
                </template>

                <!-- 排序选项 -->
                <div class="sort-options">
                  <el-select
                    v-model="sortField"
                    size="small"
                    placeholder="排序字段"
                    style="width: 140px; margin-right: 10px"
                    @change="loadTablesForDatabase(db)"
                  >
                    <el-option label="创建时间" value="createdAt" />
                    <el-option label="更新时间" value="lastUpdated" />
                    <el-option label="表名" value="tableName" />
                    <el-option label="数据量" value="rowCount" />
                    <el-option label="存储大小" value="storageSize" />
                  </el-select>
                  <el-select
                    v-model="sortOrder"
                    size="small"
                    style="width: 100px"
                    @change="loadTablesForDatabase(db)"
                  >
                    <el-option label="降序" value="desc" />
                    <el-option label="升序" value="asc" />
                  </el-select>
                </div>

                <!-- 表列表 -->
                <div class="table-list">
                  <template v-if="getTablesForDatabase(db).length">
                    <div
                      v-for="item in getTablesForDatabase(db)"
                      :key="item.id"
                      class="table-item"
                      :class="{ active: selectedTable?.id === item.id }"
                      @click.stop="handleTableClick(item)"
                    >
                      <div class="table-info">
                        <div class="table-name-row">
                          <el-icon class="table-icon"><Document /></el-icon>
                          <span class="table-name" :title="item.tableName">
                            {{ item.tableName }}
                          </span>
                          <el-tag
                            v-if="item.layer"
                            size="small"
                            :type="getLayerType(item.layer)"
                            class="layer-tag"
                          >
                            {{ item.layer }}
                          </el-tag>
                        </div>
                        <div class="table-meta">
                          <span
                            v-if="item.tableComment"
                            class="table-comment"
                            :title="item.tableComment"
                          >
                            {{ item.tableComment }}
                          </span>
                          <span v-if="item.rowCount" class="stat-item">
                            <el-icon><List /></el-icon>
                            {{ formatNumber(item.rowCount) }} 行
                          </span>
                          <span class="stat-item">
                            <el-icon><Link /></el-icon>
                            上游 {{ getUpstreamCount(item.id) }}
                          </span>
                          <span class="stat-item">
                            <el-icon><Connection /></el-icon>
                            下游 {{ getDownstreamCount(item.id) }}
                          </span>
                        </div>
                      </div>
                    </div>
                  </template>
                  <el-empty
                    v-else
                    description="暂无表"
                    :image-size="60"
                  />
                </div>
              </el-collapse-item>
            </el-collapse>
            <el-empty
              v-if="databases.length === 0"
              description="暂无数据库"
              :image-size="80"
            />
          </div>
        </el-card>
      </div>

      <!-- 右侧：表详情 -->
      <div class="right-panel">
        <el-card shadow="never" class="detail-card" v-loading="detailLoading">
          <div v-if="!selectedTable" class="empty-state">
            <el-empty description="请从左侧选择一个表查看详情">
              <template #image>
                <el-icon :size="100" color="#909399"><FolderOpened /></el-icon>
              </template>
            </el-empty>
          </div>

          <template v-else>
            <!-- 表头操作区 -->
            <div class="detail-header">
              <div class="table-title-section">
                <h2 class="table-title">{{ selectedTable.tableName }}</h2>
                <el-tag
                  :type="selectedTable.status === 'active' ? 'success' : 'info'"
                  size="large"
                >
                  {{ selectedTable.status === 'active' ? '活跃' : '已废弃' }}
                </el-tag>
              </div>
              <div class="action-buttons">
                <el-button type="primary" link @click="goLineage">
                  查看血缘关系
                </el-button>
                <template v-if="isEditing">
                  <el-button @click="handleEditCancel" :disabled="editSubmitting">
                    取消
                  </el-button>
                  <el-button
                    type="primary"
                    :loading="editSubmitting"
                    @click="handleEditSubmit"
                  >
                    保存
                  </el-button>
                </template>
                <template v-else>
                  <el-button type="primary" @click="startEditing">编辑</el-button>
                  <el-popconfirm
                    title="确定删除吗?"
                    @confirm="handleDelete(selectedTable.id)"
                  >
                    <template #reference>
                      <el-button type="danger">删除</el-button>
                    </template>
                  </el-popconfirm>
                </template>
              </div>
            </div>

            <!-- 标签页 -->
            <el-tabs v-model="activeTab" class="detail-tabs">
              <!-- 基本信息 -->
              <el-tab-pane label="基本信息" name="basic">
                <template v-if="isEditing">
                  <div class="inline-edit-wrapper">
                    <el-form
                      ref="editFormRef"
                      :model="editForm"
                      :rules="editFormRules"
                      label-width="110px"
                      class="edit-form"
                    >
                      <el-row :gutter="16">
                        <el-col :span="24">
                          <el-form-item label="表名" prop="tableName">
                            <div class="table-name-input-group">
                              <el-input v-model="editForm.tableName" placeholder="请输入表名" />
                              <el-button
                                type="primary"
                                link
                                size="small"
                                :disabled="!canGenerateEditName"
                                @click="handleGenerateTableName"
                              >
                                生成
                              </el-button>
                            </div>
                          </el-form-item>
                        </el-col>
                      </el-row>

                      <el-row :gutter="16">
                        <el-col :span="12">
                          <el-form-item label="数据分层" prop="layer">
                            <el-select v-model="editForm.layer" placeholder="选择数据分层">
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
                              v-model="editForm.businessDomain"
                              placeholder="选择业务域"
                              filterable
                              @change="handleEditBusinessDomainChange"
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

                      <el-row :gutter="16">
                        <el-col :span="12">
                          <el-form-item label="数据域" prop="dataDomain">
                            <el-select
                              v-model="editForm.dataDomain"
                              placeholder="选择数据域"
                              :disabled="!editForm.businessDomain"
                              filterable
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
                              v-model="editForm.customIdentifier"
                              placeholder="如: cmp_performance"
                              @blur="() => handleNormalizeSegment('customIdentifier')"
                            />
                          </el-form-item>
                        </el-col>
                      </el-row>

                      <el-row :gutter="16">
                        <el-col :span="12">
                          <el-form-item label="统计周期">
                            <el-select v-model="editForm.statisticsCycle" placeholder="可选">
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
                            <el-select v-model="editForm.updateType" placeholder="选择更新类型">
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

                      <el-row :gutter="16">
                        <el-col :span="12">
                          <el-form-item label="负责人" prop="owner">
                            <el-input v-model="editForm.owner" placeholder="负责人" />
                          </el-form-item>
                        </el-col>
                        <el-col :span="12">
                          <el-form-item label="数据库">
                            <el-input :model-value="selectedTable.dbName" disabled />
                          </el-form-item>
                        </el-col>
                      </el-row>

                      <el-form-item label="表注释">
                        <el-input
                          v-model="editForm.tableComment"
                          type="textarea"
                          :rows="3"
                          placeholder="请输入表业务含义"
                        />
                      </el-form-item>
                    </el-form>
                  </div>
                </template>
                <template v-else>
                  <el-row :gutter="20">
                    <el-col :span="12">
                      <el-descriptions title="表信息" :column="1" border>
                        <el-descriptions-item label="表名">
                          {{ selectedTable.tableName }}
                        </el-descriptions-item>
                        <el-descriptions-item label="表注释">
                          {{ selectedTable.tableComment || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="数据分层">
                          {{ selectedTable.layer || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="业务域">
                          {{ selectedTable.businessDomain || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="数据域">
                          {{ selectedTable.dataDomain || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="数据库">
                          {{ selectedTable.dbName || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="负责人">
                          {{ selectedTable.owner || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="创建时间">
                          {{ formatDateTime(selectedTable.createdAt) }}
                        </el-descriptions-item>
                      </el-descriptions>
                    </el-col>
                    <el-col :span="12">
                      <el-descriptions title="Doris 配置" :column="1" border>
                        <el-descriptions-item label="表模型">
                          {{ selectedTable.tableModel || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="主键列">
                          {{ selectedTable.keyColumns || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="分区字段">
                          {{ selectedTable.partitionColumn || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="分桶字段">
                          {{ selectedTable.distributionColumn || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="分桶数">
                          {{ selectedTable.bucketNum || '-' }}
                        </el-descriptions-item>
                        <el-descriptions-item label="副本数">
                          {{ selectedTable.replicaNum || '-' }}
                        </el-descriptions-item>
                      </el-descriptions>
                    </el-col>
                  </el-row>
                </template>
              </el-tab-pane>

              <!-- 统计信息 -->
              <el-tab-pane label="统计信息" name="statistics">
                <div class="statistics-section">
                  <div class="section-header">
                    <h3>数据统计</h3>
                    <el-button
                      type="primary"
                      size="small"
                      @click="refreshStatistics"
                      :loading="statisticsLoading"
                    >
                      刷新统计
                    </el-button>
                  </div>

                  <div v-if="statistics" class="statistics-content">
                    <el-row :gutter="20" class="stat-cards">
                      <el-col :span="8">
                        <div class="stat-card">
                          <div class="stat-label">数据行数</div>
                          <div class="stat-value">
                            {{ formatNumber(statistics.rowCount) }}
                          </div>
                        </div>
                      </el-col>
                      <el-col :span="8">
                        <div class="stat-card">
                          <div class="stat-label">数据大小</div>
                          <div class="stat-value">
                            {{ statistics.dataSizeReadable || '-' }}
                          </div>
                        </div>
                      </el-col>
                      <el-col :span="8">
                        <div class="stat-card">
                          <div class="stat-label">分区数量</div>
                          <div class="stat-value">
                            {{ statistics.partitionCount || '-' }}
                          </div>
                        </div>
                      </el-col>
                    </el-row>

                    <!-- 数据量趋势图 -->
                    <div class="trend-chart" ref="chartContainer">
                      <h4>数据量趋势（最近7天）</h4>
                      <div id="trendChart" style="height: 300px"></div>
                    </div>
                  </div>
                  <el-empty v-else description="暂无统计信息，点击刷新获取" />
                </div>
              </el-tab-pane>

              <!-- 字段列表 -->
              <el-tab-pane label="字段列表" name="fields">
                <el-table :data="fields" border style="width: 100%">
                  <el-table-column prop="fieldName" label="字段名" width="200" />
                  <el-table-column prop="fieldType" label="类型" width="150" />
                  <el-table-column prop="isNullable" label="可为空" width="100">
                    <template #default="{ row }">
                      <el-tag :type="row.isNullable ? 'success' : 'danger'">
                        {{ row.isNullable ? '是' : '否' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="isPrimary" label="主键" width="100">
                    <template #default="{ row }">
                      <el-tag v-if="row.isPrimary" type="info">是</el-tag>
                      <span v-else>-</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="defaultValue" label="默认值" width="150" />
                  <el-table-column prop="fieldComment" label="注释" />
                </el-table>
                <el-empty v-if="!fields.length" description="暂无字段信息" />
              </el-tab-pane>

              <!-- DDL -->
              <el-tab-pane label="DDL" name="ddl">
                <div class="ddl-section">
                  <div class="section-header">
                    <h3>Doris 建表语句</h3>
                    <el-button
                      type="primary"
                      size="small"
                      @click="copyDdl"
                      :disabled="!selectedTable.dorisDdl"
                    >
                      复制
                    </el-button>
                  </div>
                  <el-input
                    :value="selectedTable.dorisDdl || ''"
                    type="textarea"
                    :rows="20"
                    readonly
                    class="ddl-textarea"
                    placeholder="暂无 Doris 建表语句"
                  />
                </div>
              </el-tab-pane>

              <!-- 数据预览 -->
              <el-tab-pane label="数据预览" name="preview">
                <div class="preview-section">
                  <el-alert
                    type="info"
                    :closable="false"
                    show-icon
                    title="数据预览功能即将上线，敬请期待"
                  />
                </div>
              </el-tab-pane>

              <!-- 导出 -->
              <el-tab-pane label="导出" name="export">
                <div class="export-section">
                  <el-alert
                    type="info"
                    :closable="false"
                    show-icon
                    title="数据导出功能即将上线，敬请期待"
                  />
                </div>
              </el-tab-pane>
            </el-tabs>
          </template>
        </el-card>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Search,
  Plus,
  Folder,
  Document,
  List,
  Link,
  Connection,
  FolderOpened
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import * as echarts from 'echarts'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { tableDesignerApi } from '@/api/tableDesigner'

const router = useRouter()

const loading = ref(false)
const detailLoading = ref(false)
const statisticsLoading = ref(false)
const databases = ref([])
const tablesByDatabase = ref({})
const lineageCache = ref({})
const activeDatabase = ref('')
const searchKeyword = ref('')
const selectedTable = ref(null)
const fields = ref([])
const statistics = ref(null)
const statisticsHistory = ref([])
const activeTab = ref('basic')
const chartContainer = ref(null)
let chartInstance = null

const tableItemSize = 56

const isEditing = ref(false)
const editFormRef = ref(null)
const editSubmitting = ref(false)

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

const businessDomainOptions = ref([])
const dataDomainOptions = ref([])

const editForm = reactive({
  tableName: '',
  tableComment: '',
  layer: '',
  businessDomain: '',
  dataDomain: '',
  customIdentifier: '',
  statisticsCycle: '',
  updateType: '',
  owner: ''
})

const editFormRules = {
  layer: [{ required: true, message: '请选择数据分层', trigger: 'change' }],
  businessDomain: [{ required: true, message: '请选择业务域', trigger: 'change' }],
  dataDomain: [{ required: true, message: '请选择数据域', trigger: 'change' }],
  customIdentifier: [
    { required: true, message: '请输入自定义标识', trigger: 'blur' },
    {
      pattern: TABLE_NAME_PATTERN,
      message: '仅支持小写字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  updateType: [{ required: true, message: '请选择更新类型', trigger: 'change' }],
  tableName: [
    { required: true, message: '表名不能为空', trigger: 'blur' },
    {
      pattern: TABLE_NAME_PATTERN,
      message: '表名仅支持小写字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  owner: [{ required: true, message: '请输入负责人', trigger: 'blur' }]
}

const canGenerateEditName = computed(
  () =>
    !!(
      editForm.layer &&
      editForm.businessDomain &&
      editForm.dataDomain &&
      editForm.customIdentifier &&
      editForm.updateType
    )
)

// 排序选项
const sortField = ref('createdAt')
const sortOrder = ref('desc')

// 加载数据库列表
const loadDatabases = async () => {
  loading.value = true
  try {
    databases.value = await tableApi.listDatabases()
    if (databases.value.length > 0) {
      activeDatabase.value = databases.value[0]
      await loadTablesForDatabase(databases.value[0])
    }
  } catch (error) {
    console.error('加载数据库列表失败:', error)
    ElMessage.error('加载数据库列表失败')
  } finally {
    loading.value = false
  }
}

// 加载指定数据库的表列表
const loadTablesForDatabase = async (database) => {
  if (!database) return
  try {
    const tables = await tableApi.listByDatabase(database, sortField.value, sortOrder.value)
    tablesByDatabase.value[database] = tables

    // 加载血缘关系信息
    for (const table of tables) {
      loadLineageForTable(table.id)
    }
  } catch (error) {
    console.error('加载表列表失败:', error)
  }
}

// 加载表的血缘关系
const loadLineageForTable = async (tableId) => {
  if (lineageCache.value[tableId]) return
  try {
    const lineage = await tableApi.getLineage(tableId)
    lineageCache.value[tableId] = lineage
  } catch (error) {
    console.error('加载血缘关系失败:', error)
  }
}

// 获取指定数据库的表列表
const getTablesForDatabase = (database) => {
  const tables = tablesByDatabase.value[database] || []
  if (!searchKeyword.value) return tables
  return tables.filter(
    (table) =>
      table.tableName.toLowerCase().includes(searchKeyword.value.toLowerCase()) ||
      (table.tableComment &&
        table.tableComment.toLowerCase().includes(searchKeyword.value.toLowerCase()))
  )
}

// 获取表数量
const getTableCount = (database) => {
  return getTablesForDatabase(database).length
}

const getVirtualListHeight = (database) => {
  const count = getTablesForDatabase(database).length
  const visibleCount = Math.min(Math.max(count, 6), 18)
  return visibleCount * tableItemSize
}

const normalizeSegment = (value) =>
  value ? value.trim().toLowerCase().replace(/\s+/g, '_').replace(/-+/g, '_') : ''

const handleNormalizeSegment = (field) => {
  editForm[field] = normalizeSegment(editForm[field])
}

const loadBusinessDomainOptions = async () => {
  try {
    businessDomainOptions.value = await businessDomainApi.list()
  } catch (error) {
    console.error('加载业务域失败:', error)
    businessDomainOptions.value = []
  }
}

const loadDataDomainOptions = async (businessDomain) => {
  if (!businessDomain) {
    dataDomainOptions.value = []
    return
  }
  try {
    dataDomainOptions.value = await dataDomainApi.list({ businessDomain })
  } catch (error) {
    console.error('加载数据域失败:', error)
    dataDomainOptions.value = []
  }
}

const ensureBusinessDomainOption = (code) => {
  if (
    code &&
    !businessDomainOptions.value.some((item) => item.domainCode === code)
  ) {
    businessDomainOptions.value.push({
      domainCode: code,
      domainName: code
    })
  }
}

const ensureDataDomainOption = (code, businessDomain) => {
  if (
    code &&
    !dataDomainOptions.value.some((item) => item.domainCode === code)
  ) {
    dataDomainOptions.value.push({
      domainCode: code,
      domainName: code,
      businessDomain
    })
  }
}

const applyEditForm = async (data) => {
  if (!data) return
  editForm.tableName = data.tableName || ''
  editForm.tableComment = data.tableComment || ''
  editForm.layer = data.layer || ''
  editForm.businessDomain = data.businessDomain || ''
  await loadDataDomainOptions(editForm.businessDomain)
  ensureBusinessDomainOption(editForm.businessDomain)
  editForm.dataDomain = data.dataDomain || ''
  ensureDataDomainOption(editForm.dataDomain, editForm.businessDomain)
  editForm.customIdentifier = data.customIdentifier || ''
  editForm.statisticsCycle = data.statisticsCycle || ''
  editForm.updateType = data.updateType || ''
  editForm.owner = data.owner || ''
}

const handleEditBusinessDomainChange = async () => {
  await loadDataDomainOptions(editForm.businessDomain)
  if (
    editForm.dataDomain &&
    !dataDomainOptions.value.some((item) => item.domainCode === editForm.dataDomain)
  ) {
    editForm.dataDomain = ''
  }
}

const handleGenerateTableName = async () => {
  if (!canGenerateEditName.value) return
  try {
    const payload = {
      layer: editForm.layer,
      businessDomain: normalizeSegment(editForm.businessDomain),
      dataDomain: normalizeSegment(editForm.dataDomain),
      customIdentifier: normalizeSegment(editForm.customIdentifier),
      statisticsCycle: editForm.statisticsCycle || null,
      updateType: editForm.updateType
    }
    const name = await tableDesignerApi.generateTableName(payload)
    editForm.tableName = name
    ElMessage.success('已生成推荐表名')
  } catch (error) {
    console.error('生成表名失败:', error)
    ElMessage.error('生成表名失败')
  }
}

const startEditing = async () => {
  if (!selectedTable.value) return
  await loadBusinessDomainOptions()
  await applyEditForm(selectedTable.value)
  isEditing.value = true
  await nextTick()
  if (editFormRef.value) {
    editFormRef.value.clearValidate()
  }
}

const handleEditCancel = async () => {
  if (editSubmitting.value) return
  await applyEditForm(selectedTable.value)
  isEditing.value = false
  await nextTick()
  if (editFormRef.value) {
    editFormRef.value.clearValidate()
  }
}

const updateCachedTable = (updatedTable) => {
  if (!activeDatabase.value) return
  const list = tablesByDatabase.value[activeDatabase.value] || []
  const index = list.findIndex((item) => item.id === updatedTable.id)
  if (index === -1) return
  const updatedList = [...list]
  updatedList[index] = { ...updatedList[index], ...updatedTable }
  tablesByDatabase.value = {
    ...tablesByDatabase.value,
    [activeDatabase.value]: updatedList
  }
}

const handleEditSubmit = async () => {
  if (!selectedTable.value) return
  if (!editFormRef.value) return
  handleNormalizeSegment('customIdentifier')
  try {
    await editFormRef.value.validate()
  } catch (error) {
    return
  }

  editSubmitting.value = true
  try {
    const payload = {
      ...selectedTable.value,
      tableName: editForm.tableName.trim(),
      tableComment: editForm.tableComment,
      owner: editForm.owner,
      layer: editForm.layer,
      businessDomain: editForm.businessDomain,
      dataDomain: editForm.dataDomain,
      customIdentifier: editForm.customIdentifier,
      statisticsCycle: editForm.statisticsCycle || null,
      updateType: editForm.updateType
    }
    const updated = await tableApi.update(selectedTable.value.id, payload)
    await loadTableDetail(updated.id)
    updateCachedTable(updated)
    ElMessage.success('更新成功')
    await applyEditForm(selectedTable.value)
    isEditing.value = false
  } catch (error) {
    console.error('更新失败:', error)
    ElMessage.error('更新失败')
  } finally {
    editSubmitting.value = false
  }
}

// 获取上游表数量
const getUpstreamCount = (tableId) => {
  const lineage = lineageCache.value[tableId]
  return lineage?.upstreamTables?.length || 0
}

// 获取下游表数量
const getDownstreamCount = (tableId) => {
  const lineage = lineageCache.value[tableId]
  return lineage?.downstreamTables?.length || 0
}

// 处理数据库点击
const handleDatabaseClick = (database) => {
  if (!tablesByDatabase.value[database]) {
    loadTablesForDatabase(database)
  }
}

// 处理表点击
const handleTableClick = async (table) => {
  selectedTable.value = table
  activeTab.value = 'basic'
  isEditing.value = false
  if (editFormRef.value) {
    editFormRef.value.clearValidate()
  }
  await loadTableDetail(table.id)
}

// 加载表详情
const loadTableDetail = async (tableId) => {
  detailLoading.value = true
  try {
    const [tableInfo, fieldList] = await Promise.all([
      tableApi.getById(tableId),
      tableApi.getFields(tableId)
    ])
    selectedTable.value = tableInfo
    fields.value = fieldList
    await applyEditForm(tableInfo)
  } catch (error) {
    console.error('加载表详情失败:', error)
    ElMessage.error('加载表详情失败')
  } finally {
    detailLoading.value = false
  }
}

// 刷新统计信息
const refreshStatistics = async () => {
  if (!selectedTable.value) return
  statisticsLoading.value = true
  try {
    const [stat, history] = await Promise.all([
      tableApi.getStatistics(selectedTable.value.id, null, true),
      tableApi.getLast7DaysHistory(selectedTable.value.id)
    ])
    statistics.value = stat
    statisticsHistory.value = history

    // 绘制趋势图
    await nextTick()
    renderTrendChart()
  } catch (error) {
    console.error('刷新统计信息失败:', error)
    ElMessage.error('刷新统计信息失败')
  } finally {
    statisticsLoading.value = false
  }
}

// 绘制数据量趋势图
const renderTrendChart = () => {
  const chartDom = document.getElementById('trendChart')
  if (!chartDom) return

  if (chartInstance) {
    chartInstance.dispose()
  }

  chartInstance = echarts.init(chartDom)

  const dates = statisticsHistory.value.map((item) =>
    new Date(item.recordTime).toLocaleDateString()
  )
  const rowCounts = statisticsHistory.value.map((item) => item.rowCount)

  const option = {
    title: {
      text: ''
    },
    tooltip: {
      trigger: 'axis'
    },
    xAxis: {
      type: 'category',
      data: dates
    },
    yAxis: {
      type: 'value',
      name: '数据行数'
    },
    series: [
      {
        data: rowCounts,
        type: 'line',
        smooth: true,
        areaStyle: {
          color: '#409EFF',
          opacity: 0.3
        },
        itemStyle: {
          color: '#409EFF'
        }
      }
    ]
  }

  chartInstance.setOption(option)
}

// 搜索处理
const handleSearch = () => {
  // 触发重新计算
}

// 复制DDL
const copyDdl = async () => {
  if (!selectedTable.value?.dorisDdl) return
  try {
    await navigator.clipboard.writeText(selectedTable.value.dorisDdl)
    ElMessage.success('已复制到剪贴板')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

// 跳转到创建页面
const goCreate = () => {
  router.push('/tables/create')
}

// 跳转到血缘关系页面
const goLineage = () => {
  if (selectedTable.value) {
    router.push({
      path: '/lineage',
      query: { focus: selectedTable.value.tableName }
    })
  }
}

// 删除表
const handleDelete = async (id) => {
  try {
    await tableApi.delete(id)
    ElMessage.success('删除成功')
    selectedTable.value = null
    // 重新加载当前数据库的表列表
    if (activeDatabase.value) {
      await loadTablesForDatabase(activeDatabase.value)
    }
  } catch (error) {
    console.error('删除失败:', error)
    ElMessage.error('删除失败')
  }
}

// 获取层级标签类型
const getLayerType = (layer) => {
  const types = {
    ODS: '',
    DWD: 'success',
    DIM: 'warning',
    DWS: 'info',
    ADS: 'danger'
  }
  return types[layer] || ''
}

// 格式化数字
const formatNumber = (num) => {
  if (num === null || num === undefined) return '-'
  return num.toLocaleString('zh-CN')
}

// 格式化日期时间
const formatDateTime = (dateTime) => {
  if (!dateTime) return '-'
  try {
    const date = new Date(dateTime)
    return date.toLocaleString('zh-CN')
  } catch (error) {
    return dateTime
  }
}

onMounted(() => {
  loadDatabases()
  loadBusinessDomainOptions()
})
</script>

<style scoped>
.table-management {
  height: 100%;
  padding: 20px;
  background-color: #f5f7fa;
}

.management-container {
  display: flex;
  gap: 20px;
  height: calc(100vh - 140px);
}

/* 左侧面板 */
.left-panel {
  width: 320px;
  flex-shrink: 0;
}

.database-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.title {
  font-size: 16px;
}

.search-input {
  margin-bottom: 12px;
}

.database-list {
  flex: 1;
  overflow-y: auto;
  padding-right: 6px;
}

.database-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

.db-name {
  font-weight: 600;
  flex: 1;
}

.table-count-badge {
  margin-left: auto;
}

.sort-options {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 6px;
  background-color: #f5f7fa;
  border-radius: 6px;
}

.table-list {
  padding: 4px 0;
}

.table-virtual-list {
  width: 100%;
}

.table-item {
  padding: 8px 10px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
  background-color: #fff;
  min-height: 56px;
  display: flex;
  align-items: stretch;
}

.table-item:hover {
  border-color: #409eff;
  background-color: #ecf5ff;
}

.table-item.active {
  border-color: #409eff;
  background-color: #ecf5ff;
}


.table-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.table-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.table-icon {
  color: #409eff;
}


.table-name {
  font-weight: 600;
  font-size: 13px;
  flex: 1;
}

.layer-tag {
  margin-left: auto;
}

.table-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  font-size: 12px;
  color: #606266;
}

.table-comment {
  flex: 1 1 100%;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 编辑区域 */
.inline-edit-wrapper {
  padding: 20px;
  background-color: #f9fafc;
  border: 1px solid #ebeef5;
  border-radius: 8px;
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.table-name-input-group {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.table-name-input-group :deep(.el-input) {
  flex: 1;
}

/* 右侧面板 */
.right-panel {
  flex: 1;
  overflow: hidden;
}

.detail-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 2px solid #ebeef5;
}

.table-title-section {
  display: flex;
  align-items: center;
  gap: 12px;
}

.table-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
}

.action-buttons {
  display: flex;
  gap: 10px;
}

.detail-tabs {
  flex: 1;
  overflow-y: auto;
}

.statistics-section,
.ddl-section,
.preview-section,
.export-section {
  padding: 16px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-header h3 {
  margin: 0;
  font-size: 18px;
}

.stat-cards {
  margin-bottom: 24px;
}

.stat-card {
  padding: 24px;
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

.trend-chart {
  background-color: #fff;
  padding: 20px;
  border-radius: 8px;
  border: 1px solid #ebeef5;
}

.trend-chart h4 {
  margin: 0 0 16px 0;
  font-size: 16px;
}

.ddl-textarea {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

:deep(.el-card__body) {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

:deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

:deep(.el-tabs__content) {
  overflow-y: auto;
}
</style>
