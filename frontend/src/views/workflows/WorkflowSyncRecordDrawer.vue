<template>
  <el-drawer
    v-model="visible"
    title="同步记录"
    size="52%"
    destroy-on-close
  >
    <div class="sync-record-drawer" v-loading="loading">
      <el-table :data="records" border size="small">
        <el-table-column prop="id" label="记录ID" width="100" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'success' ? 'success' : 'danger'">
              {{ row.status || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="versionId" label="版本ID" width="110" />
        <el-table-column label="差异" width="130">
          <template #default="{ row }">
            {{ diffText(row.diffSummary) }}
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="120" />
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="loadDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        v-model:current-page="pagination.pageNum"
        v-model:page-size="pagination.pageSize"
        :total="pagination.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadRecords"
        @size-change="handlePageSizeChange"
      />

      <div class="detail" v-if="detail">
        <div class="detail-title">记录详情 #{{ detail.id }}</div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="状态">{{ detail.status || '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本ID">{{ detail.versionId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="错误码">{{ detail.errorCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{ detail.operator || '-' }}</el-descriptions-item>
          <el-descriptions-item label="错误信息" :span="2">{{ detail.errorMessage || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-section" v-if="detail.diffSummary">
          <div class="detail-subtitle">差异摘要</div>
          <div class="diff-grid">
            <span>workflow: {{ detail.diffSummary.workflowFieldChanges?.length || 0 }}</span>
            <span>任务新增: {{ detail.diffSummary.taskAdded?.length || 0 }}</span>
            <span>任务删除: {{ detail.diffSummary.taskRemoved?.length || 0 }}</span>
            <span>任务修改: {{ detail.diffSummary.taskModified?.length || 0 }}</span>
            <span>边新增: {{ detail.diffSummary.edgeAdded?.length || 0 }}</span>
            <span>边删除: {{ detail.diffSummary.edgeRemoved?.length || 0 }}</span>
            <span>调度变更: {{ detail.diffSummary.scheduleChanges?.length || 0 }}</span>
          </div>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { workflowApi } from '@/api/workflow'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  workflowId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const loading = ref(false)
const records = ref([])
const detail = ref(null)

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

watch(
  () => visible.value,
  (open) => {
    if (open) {
      pagination.pageNum = 1
      detail.value = null
      loadRecords()
    } else {
      records.value = []
      detail.value = null
    }
  }
)

const loadRecords = async () => {
  if (!props.workflowId) {
    return
  }
  loading.value = true
  try {
    const page = await workflowApi.listRuntimeSyncRecords(props.workflowId, {
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    })
    records.value = page.records || []
    pagination.total = page.total || 0
  } catch (error) {
    console.error('加载同步记录失败', error)
    ElMessage.error(error.message || '加载同步记录失败')
  } finally {
    loading.value = false
  }
}

const handlePageSizeChange = () => {
  pagination.pageNum = 1
  loadRecords()
}

const loadDetail = async (row) => {
  if (!props.workflowId || !row?.id) {
    return
  }
  loading.value = true
  try {
    detail.value = await workflowApi.getRuntimeSyncRecordDetail(props.workflowId, row.id)
  } catch (error) {
    console.error('加载同步记录详情失败', error)
    ElMessage.error(error.message || '加载同步记录详情失败')
  } finally {
    loading.value = false
  }
}

const formatDateTime = (value) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const diffText = (summary) => {
  if (!summary) {
    return '-'
  }
  const total = (summary.workflowFieldChanges?.length || 0)
    + (summary.taskAdded?.length || 0)
    + (summary.taskRemoved?.length || 0)
    + (summary.taskModified?.length || 0)
    + (summary.edgeAdded?.length || 0)
    + (summary.edgeRemoved?.length || 0)
    + (summary.scheduleChanges?.length || 0)
  return `${total}项`
}
</script>

<style scoped>
.sync-record-drawer {
  padding-right: 8px;
}

.pagination {
  margin-top: 12px;
  justify-content: flex-end;
}

.detail {
  margin-top: 16px;
}

.detail-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.detail-section {
  margin-top: 10px;
}

.detail-subtitle {
  margin-bottom: 6px;
  color: #606266;
}

.diff-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 8px;
  font-size: 13px;
  color: #606266;
}
</style>
