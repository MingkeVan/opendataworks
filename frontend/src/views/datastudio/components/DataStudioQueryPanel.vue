<template>
  <div class="query-panel">
    <div class="query-topbar">
      <div class="query-topbar__left">
        <div class="query-context">
          <template v-if="tab.kind === 'query'">
            <el-select
              v-model="tabStates[tab.id].table.sourceId"
              size="small"
              filterable
              clearable
              class="query-select query-select--source"
              placeholder="选择数据源"
              @change="(value) => handleQuerySourceSelect(tab.id, value)"
            >
              <el-option
                v-for="source in dataSources"
                :key="String(source.id)"
                :label="source.clusterName || source.name || `DataSource ${source.id}`"
                :value="String(source.id)"
              />
            </el-select>

            <el-select
              v-model="tabStates[tab.id].table.dbName"
              size="small"
              filterable
              clearable
              class="query-select query-select--db"
              placeholder="选择数据库"
              :disabled="!tabStates[tab.id].table.sourceId"
              @change="(value) => handleQueryDatabaseSelect(tab.id, value)"
            >
              <el-option
                v-for="db in getSchemaOptions(tabStates[tab.id].table.sourceId)"
                :key="db"
                :label="db"
                :value="db"
              />
            </el-select>
          </template>

          <template v-else>
            <el-tag size="small" type="info">{{ getSourceName(tab.sourceId) || '-' }}</el-tag>
            <el-tag size="small" type="info">{{ tabStates[tab.id].table.dbName || '-' }}</el-tag>
          </template>
        </div>

        <div class="query-divider"></div>

        <span class="limit-label">Limit</span>
        <el-input-number
          v-model="tabStates[tab.id].query.limit"
          :min="1"
          :max="5000"
          :step="100"
          size="small"
          controls-position="right"
          class="limit-input"
        />
      </div>

      <div class="query-topbar__actions">
        <el-button
          type="success"
          size="small"
          :loading="tabStates[tab.id].queryLoading"
          :disabled="tabStates[tab.id].queryLoading"
          @click="executeQuery(tab.id)"
        >
          <el-icon><CaretRight /></el-icon>
          {{ tabStates[tab.id].query.hasSelection ? '运行已选择' : '运行全部' }}
        </el-button>
          <el-button
            size="small"
            :loading="tabStates[tab.id].queryStopping"
            :disabled="!tabStates[tab.id].queryCancelable || tabStates[tab.id].queryStopping"
            @click="stopQuery(tab.id)"
          >
            <el-icon><VideoPause /></el-icon>
            停止
        </el-button>
        <el-button size="small" :disabled="tabStates[tab.id].queryLoading" @click="resetQuery(tab.id)">
          重置
        </el-button>
        <el-button
          size="small"
          type="success"
          plain
          :disabled="tabStates[tab.id].queryLoading || isDemoMode"
          @click="saveAsTask(tab.id)"
        >
          存为任务
        </el-button>
      </div>
    </div>
    <SqlEditor
      v-model="tabStates[tab.id].query.sql"
      class="sql-editor"
      placeholder="-- 输入 SQL，支持查询与变更语句（高风险语句需强确认）"
      :completion-context="getSqlCompletionContext(tab.id)"
      @selection-change="(payload) => handleSqlSelectionChange(tab.id, payload)"
    />
  </div>
</template>

<script setup>
import { defineAsyncComponent, inject } from 'vue'
import { CaretRight, VideoPause } from '@element-plus/icons-vue'
import { isDemoMode } from '@/demo/runtime'

const SqlEditor = defineAsyncComponent({
  loader: () => import('@/components/SqlEditor.vue'),
  suspensible: false
})

// P2-2 F16b：查询工具栏 + SQL 编辑器从 DataStudioNew.vue 抽出。
// 数据与行为通过 dataStudioQueryCtx 注入，键集合见 DataStudioNew.vue 的 provide。
defineProps({
  tab: {
    type: Object,
    required: true,
  },
})

const ctx = inject('dataStudioQueryCtx')
if (!ctx) {
  throw new Error('DataStudioQueryPanel 需要 dataStudioQueryCtx')
}

const {
  tabStates,
  dataSources,
  getSourceName,
  getSchemaOptions,
  handleQuerySourceSelect,
  handleQueryDatabaseSelect,
  executeQuery,
  stopQuery,
  resetQuery,
  saveAsTask,
  getSqlCompletionContext,
  handleSqlSelectionChange,
} = ctx
</script>

<style scoped>
.query-panel {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  padding: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.query-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
  gap: 10px;
  flex-wrap: nowrap;
}

.query-topbar__left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
  overflow: hidden;
}

.query-topbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.query-context {
  display: flex;
  gap: 6px;
  flex-wrap: nowrap;
  align-items: center;
  min-width: 0;
}

.query-divider {
  width: 1px;
  height: 20px;
  background: #e2e8f0;
  flex-shrink: 0;
}

.query-select {
  width: 160px;
  flex: 0 0 160px;
}

.query-select--source {
  width: 180px;
  flex: 0 0 180px;
}

.query-select--db {
  width: 180px;
  flex: 0 0 180px;
}

.query-select :deep(.el-select__selected-item) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.limit-label {
  font-size: 12px;
  color: #64748b;
}

.limit-input {
  width: 110px;
}

.sql-editor {
  flex: 1;
  min-height: 0;
}

.sql-editor :deep(.cm-editor) {
  height: 100%;
}
</style>
