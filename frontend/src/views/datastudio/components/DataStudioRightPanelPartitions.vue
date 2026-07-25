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
      </el-scrollbar>
    </section>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'

// 「明细信息 / 分区信息」子页：分区与分桶配置 + 分区字段清单。
// 数据全部来自表详情已加载的 state（data_table 分区/分桶列与 data_field.isPartition），
// 不额外请求后端；平台当前没有列举 Doris 实际分区实例的接口。
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelPartitions 需要 dataStudioCtx')
}

const { activeTab, tabStates } = ctx

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
</style>
