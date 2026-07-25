<template>
  <div class="meta-section meta-section-fill">
    <section class="section-block section-fill">
      <div class="section-header">
        <div class="section-title">
          分区信息
          <el-tag v-if="isPartitioned" size="small" type="success" effect="plain" class="partition-tag">
            分区表
          </el-tag>
          <el-tag v-else size="small" type="info" effect="plain" class="partition-tag">非分区表</el-tag>
        </div>
        <div class="section-actions">
          <span class="partition-counts">
            分区字段 {{ partitionFields.length }} · 非分区字段 {{ normalFieldCount }}
          </span>
        </div>
      </div>

      <el-scrollbar class="meta-scroll">
        <el-descriptions :column="1" border size="small" class="meta-descriptions">
          <el-descriptions-item label="分区列">{{ state.table.partitionColumn || '-' }}</el-descriptions-item>
          <el-descriptions-item label="分桶列">{{ state.table.distributionColumn || '-' }}</el-descriptions-item>
          <el-descriptions-item label="分桶数">{{ state.table.bucketNum ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="副本数">{{ state.table.replicaNum ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="表模型">{{ state.table.tableModel || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Key 列">{{ state.table.keyColumns || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="partition-fields">
          <div class="partition-fields-title">分区字段</div>
          <el-table v-if="partitionFields.length" :data="partitionFields" border size="small">
            <el-table-column type="index" label="序号" width="60" />
            <el-table-column prop="fieldName" label="字段名" min-width="140" show-overflow-tooltip />
            <el-table-column prop="fieldType" label="类型" width="130" show-overflow-tooltip />
            <el-table-column label="注释" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.fieldComment || '-' }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="该表未配置分区字段" :image-size="60" />
        </div>

        <div v-loading="partitionsLoading" class="partition-list">
          <div class="partition-list-head">
            <span class="partition-fields-title">分区列表</span>
            <span class="partition-list-actions">
              <span v-if="partitions.length" class="partition-counts">共 {{ partitions.length }} 个分区</span>
              <el-button link type="primary" size="small" :disabled="partitionsLoading" @click="loadPartitions">
                刷新
              </el-button>
            </span>
          </div>
          <el-table v-if="partitions.length" :data="partitions" border size="small" max-height="320">
            <el-table-column prop="partitionName" label="分区名" min-width="150" show-overflow-tooltip />
            <el-table-column label="范围" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.range || '-' }}</template>
            </el-table-column>
            <el-table-column label="大小" width="100" align="right">
              <template #default="{ row }">{{ row.dataSize || '-' }}</template>
            </el-table-column>
            <el-table-column label="行数" width="110" align="right">
              <template #default="{ row }">{{ formatRowCount(row.rowCount) }}</template>
            </el-table-column>
            <el-table-column label="分桶" width="70" align="right">
              <template #default="{ row }">{{ row.buckets ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="副本" width="70" align="right">
              <template #default="{ row }">{{ row.replicationNum ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90" show-overflow-tooltip>
              <template #default="{ row }">{{ row.state || '-' }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else-if="!partitionsLoading" :description="partitionsError || '暂无分区'" :image-size="60" />
        </div>
      </el-scrollbar>
    </section>
  </div>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import { tableApi } from '@/api/table'

// 「明细信息 / 分区信息」子页：
// - 分区与分桶配置、分区字段清单：取自表详情已加载的 state，不额外请求
// - 分区列表：进入该子页时按需请求 GET /v1/tables/{id}/partitions（Doris SHOW PARTITIONS）
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelPartitions 需要 dataStudioCtx')
}

const { activeTab, tabStates, clusterId } = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})

const allFields = computed(() => state.value?.fields || [])
const partitionFields = computed(() => allFields.value.filter((field) => Number(field.isPartition ?? 0) === 1))
const normalFieldCount = computed(() => allFields.value.length - partitionFields.value.length)
const isPartitioned = computed(() => !!state.value?.table?.partitionColumn || partitionFields.value.length > 0)

const partitions = ref([])
const partitionsLoading = ref(false)
const partitionsError = ref('')

const formatRowCount = (value) =>
  value === null || value === undefined ? '-' : Number(value).toLocaleString('en-US')

const loadPartitions = async () => {
  const tableId = state.value?.table?.id
  if (!tableId) {
    partitions.value = []
    return
  }
  partitionsLoading.value = true
  partitionsError.value = ''
  try {
    // 分区查询依赖 Doris SHOW PARTITIONS；失败时就地提示，不弹全局错误
    const list = await tableApi.listPartitions(tableId, clusterId?.value ?? null, { skipErrorMessage: true })
    partitions.value = Array.isArray(list) ? list : []
  } catch (error) {
    partitions.value = []
    partitionsError.value = error?.message || '获取分区列表失败'
  } finally {
    partitionsLoading.value = false
  }
}

watch(() => state.value?.table?.id, loadPartitions, { immediate: true })
</script>

<style scoped>
.partition-tag {
  margin-left: 8px;
  font-weight: 400;
}
.partition-counts {
  color: var(--text-muted);
  font-size: 12px;
}
.meta-descriptions :deep(.el-descriptions__label.is-bordered-label) {
  width: 96px;
  background: #f7faff;
  color: var(--text-sub);
}
.partition-fields {
  margin-top: 12px;
}
.partition-fields-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
  margin-bottom: 8px;
}
.partition-list {
  margin-top: 16px;
}
.partition-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.partition-list-head .partition-fields-title {
  margin-bottom: 0;
}
.partition-list-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
</style>
