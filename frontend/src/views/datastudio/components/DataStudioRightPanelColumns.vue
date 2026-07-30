<template>
  <div class="meta-section meta-section-fill">
    <section class="section-block section-fill">
      <div class="section-header">
        <div class="section-title">字段定义</div>
        <div class="section-actions">
          <el-tag
            v-if="state.fieldsEditing && isAggregateTable(state.table)"
            type="warning"
            size="small"
            effect="plain"
          >
            AGGREGATE 表仅支持修改注释
          </el-tag>
          <el-tag
            v-if="state.fieldsEditing && isDorisTable(state.table)"
            type="warning"
            size="small"
            effect="plain"
          >
            主键列不可在线修改
          </el-tag>
        </div>
      </div>

      <div v-if="fieldRows.length" class="meta-table">
        <el-table :data="fieldRows" border size="small" height="100%" class="columns-table">
          <el-table-column label="字段名" width="136" show-overflow-tooltip>
            <template #default="{ row }">
              <el-input
                v-if="state.fieldsEditing"
                v-model="row.fieldName"
                size="small"
                placeholder="字段名"
                :disabled="isAggregateTable(state.table)"
              />
              <span v-else>{{ row.fieldName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="136">
            <template #default="{ row }">
              <el-input
                v-if="state.fieldsEditing"
                v-model="row.fieldType"
                size="small"
                placeholder="VARCHAR(255)"
                :disabled="isAggregateTable(state.table)"
              />
              <span v-else>{{ row.fieldType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="可为空" width="84">
            <template #default="{ row }">
              <el-switch
                v-if="state.fieldsEditing"
                v-model="row.isNullable"
                :active-value="1"
                :inactive-value="0"
                size="small"
                :disabled="isAggregateTable(state.table)"
              />
              <el-tag v-else :type="row.isNullable ? 'success' : 'danger'" size="small">
                {{ row.isNullable ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="主键" width="84">
            <template #default="{ row }">
              <template v-if="state.fieldsEditing">
                <el-tooltip v-if="isDorisTable(state.table)" content="Doris 不支持在线修改主键列" placement="top">
                  <span>
                    <el-switch v-model="row.isPrimary" :active-value="1" :inactive-value="0" size="small" disabled />
                  </span>
                </el-tooltip>
                <el-switch
                  v-else
                  v-model="row.isPrimary"
                  :active-value="1"
                  :inactive-value="0"
                  size="small"
                  :disabled="isAggregateTable(state.table)"
                />
              </template>
              <el-tag v-else :type="row.isPrimary ? 'success' : 'info'" size="small">
                {{ row.isPrimary ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="默认值" width="120">
            <template #default="{ row }">
              <el-input
                v-if="state.fieldsEditing"
                v-model="row.defaultValue"
                size="small"
                placeholder="可选"
                :disabled="isAggregateTable(state.table)"
              />
              <span v-else>{{ row.defaultValue || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="注释" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <el-input v-if="state.fieldsEditing" v-model="row.fieldComment" size="small" placeholder="字段注释" />
              <span v-else>{{ row.fieldComment || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            v-if="!state.fieldsEditing && state.metaSuggestion"
            label="智能描述"
            min-width="200"
            show-overflow-tooltip
          >
            <template #header>
              <span class="smart-col-header">
                智能描述
                <el-tooltip content="基于建表语句、上下游血缘与关联任务代码生成，请复核后采纳" placement="top">
                  <el-icon class="smart-col-info"><InfoFilled /></el-icon>
                </el-tooltip>
                <el-button link type="primary" size="small" @click="generateMetadata(activeTabId)">采纳描述</el-button>
              </span>
            </template>
            <template #default="{ row }">
              <span v-if="isAdopted(row)" class="col-adopted">已采纳</span>
              <span v-else class="col-suggest">{{ suggestFor(row.fieldName) || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="state.fieldsEditing" label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-tooltip
                v-if="isAggregateTable(state.table)"
                content="AGGREGATE 表不支持新增字段"
                placement="top"
              >
                <span>
                  <el-button link type="primary" size="small" disabled>新增</el-button>
                </span>
              </el-tooltip>
              <el-button v-else link type="primary" size="small" @click="addField(activeTabId, row)">新增</el-button>
              <el-popconfirm
                width="240"
                confirm-button-text="确定"
                cancel-button-text="取消"
                :title="`确定删除字段「${row.fieldName || '未命名'}」吗？`"
                @confirm="removeField(activeTabId, row)"
              >
                <template #reference>
                  <el-tooltip
                    v-if="isAggregateTable(state.table)"
                    content="AGGREGATE 表不支持删除字段"
                    placement="top"
                  >
                    <span>
                      <el-button link type="danger" size="small" disabled>删除</el-button>
                    </span>
                  </el-tooltip>
                  <el-button v-else link type="danger" size="small">删除</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-empty v-else description="暂无字段" :image-size="60">
        <template #default>
          <el-button
            v-if="state.fieldsEditing"
            type="primary"
            size="small"
            :disabled="isDemoMode || isAggregateTable(state.table)"
            @click="addField(activeTabId)"
          >
            新增字段
          </el-button>
        </template>
      </el-empty>
    </section>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'
import { isDemoMode } from '@/demo/runtime'

// P2-2 F17d：右侧面板「列信息」tab pane 从 DataStudioRightPanel.vue 抽出。
// 编辑/智能元数据等操作已上移到面板头部，这里只负责字段表本身。
// 共享脚手架样式由父组件的 .meta-tabs :deep() 提供。
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelColumns 需要 dataStudioCtx')
}

const {
  activeTab,
  tabStates,
  isDorisTable,
  isAggregateTable,
  getFieldRows,
  addField,
  removeField,
  generateMetadata,
} = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})
const fieldRows = computed(() => getFieldRows(activeTabId.value))

const suggestFor = (fieldName) =>
  (state.value?.metaSuggestion?.fields || []).find((item) => item.fieldName === fieldName)?.suggestedComment || ''

const isAdopted = (row) => {
  const suggested = suggestFor(row.fieldName)
  return !!suggested && suggested === (row.fieldComment || '')
}
</script>

<style scoped>
.meta-table {
  flex: 1;
  min-height: 0;
}
:deep(.columns-table th.el-table__cell) {
  background: #f2f7ff;
  color: var(--text-sub);
}
.smart-col-header {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.smart-col-info {
  color: var(--text-muted);
}
.col-suggest {
  color: #7c3aed;
}
.col-adopted {
  color: #16a34a;
}
</style>
