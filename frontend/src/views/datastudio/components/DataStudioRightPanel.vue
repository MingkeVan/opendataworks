<template>
  <div :class="rootClass">
    <div v-if="hasTableTab && state" ref="panelShellRef" class="panel-shell" :style="panelShellStyle">

      <section class="meta-panel">
        <el-alert
          v-if="isPlatformMetadataMissing(state.table)"
          type="error"
          show-icon
          :closable="false"
          class="metadata-missing-alert"
        >
          <template #title>
            <div class="metadata-missing-title">
              <span>当前表在 Doris 中存在，平台中不存在。</span>
              <el-button
                class="metadata-sync-action"
                type="primary"
                link
                :loading="state.metadataSyncing"
                :disabled="isDemoMode"
                @click="syncMissingTableMetadata(activeTabId)"
              >
                立即同步
              </el-button>
            </div>
          </template>
        </el-alert>

        <el-tabs v-model="state.metaTab" class="meta-tabs detail-tabs">
          <el-tab-pane name="basic" label="基本信息">
            <DataStudioRightPanelBasic />
          </el-tab-pane>

          <el-tab-pane name="columns" label="明细信息">
            <DataStudioRightPanelColumns />
          </el-tab-pane>

          <el-tab-pane name="ddl" label="DDL">
            <div class="meta-section meta-section-fill" v-loading="state.ddlLoading">
              <section class="section-block section-fill">
                <div class="section-header">
                  <div class="section-title">建表语句</div>
                  <div class="section-actions">
                    <el-button size="small" :disabled="!state.ddl" @click="copyDdl(activeTabId)">复制</el-button>
                  </div>
                </div>
                <div class="code-shell">
                  <el-scrollbar class="ddl-scroll">
                    <pre v-if="state.ddl" class="ddl-content">{{ state.ddl }}</pre>
                    <div v-else class="ddl-placeholder">加载中或暂无 DDL</div>
                  </el-scrollbar>
                </div>
              </section>
            </div>
          </el-tab-pane>

          <el-tab-pane name="access" label="访问情况">
            <DataStudioRightPanelAccess />
          </el-tab-pane>

          <el-tab-pane name="versions" label="变更" lazy>
            <div class="meta-section meta-section-fill">
              <section class="section-block section-fill">
                <div class="section-header">
                  <div class="section-title">变更记录</div>
                </div>
                <el-scrollbar class="meta-scroll">
                  <TableVersionHistoryPanel
                    :table-id="state.table?.id"
                    :active="state.metaTab === 'versions'"
                  />
                </el-scrollbar>
              </section>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>

      <div class="panel-resizer" title="拖动调整高度" @mousedown="startPanelResize"></div>

      <DataStudioRightPanelLineage
        class="lineage-pane"
        :current-table="state.table"
        :upstream-tables="state.lineage.upstreamTables"
        :downstream-tables="state.lineage.downstreamTables"
        :write-tasks="state.tasks.writeTasks"
        :read-tasks="state.tasks.readTasks"
        :edges="state.lineage.edges || []"
        @open-table="openTableTab"
        @open-task="openTask"
        @create-task="(type) => goCreateRelatedTask(activeTabId, type)"
        @go-lineage="goLineage(activeTabId)"
      />
    </div>

    <div v-else class="right-empty">
      <el-empty :description="emptyDescription" :image-size="110" />
    </div>

    <SmartMetadataDialog />
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import DataStudioRightPanelLineage from './DataStudioRightPanelLineage.vue'
import TableVersionHistoryPanel from './TableVersionHistoryPanel.vue'
import DataStudioRightPanelBasic from './DataStudioRightPanelBasic.vue'
import DataStudioRightPanelColumns from './DataStudioRightPanelColumns.vue'
import DataStudioRightPanelAccess from './DataStudioRightPanelAccess.vue'
import SmartMetadataDialog from './SmartMetadataDialog.vue'
import { isDemoMode } from '@/demo/runtime'
import { usePanelVerticalResize } from '../composables/usePanelVerticalResize'

const props = defineProps({
  visualVariant: {
    type: String,
    default: 'control-deck',
    validator: (value) => ['control-deck', 'paper-blueprint', 'signal-cards', 'minimal-blue', 'navicat-blue', 'tech-grid', 'clean-slate', 'data-console'].includes(value)
  }
})

const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanel requires dataStudioCtx')
}

const {
  openTabs,
  activeTab,
  tabStates,
  isPlatformMetadataMissing,
  syncMissingTableMetadata,
  copyDdl,
  goLineage,
  goCreateRelatedTask,
  openTask,
  openTableTab
} = ctx

const activeTabId = computed(() => String(activeTab.value || ''))

const activeTabItem = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return (openTabs.value || []).find((item) => String(item?.id) === id) || null
})

const rootClass = computed(() => [
  'right-root',
  `variant-${props.visualVariant}`,
  { 'is-pane-resizing': isPanelResizing.value }
])

const emptyDescription = computed(() => {
  if (activeTabItem.value?.kind === 'query') return '没有可用的对象信息'
  return '选择表后在此查看基本信息、列详情、DDL 与数据血缘'
})

const hasTableTab = computed(() => {
  return !!activeTabItem.value && activeTabItem.value.kind !== 'query'
})

const state = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return tabStates[id] || null
})

// 右侧面板上下分栏（P2-2 F17b）：拖拽与按 tab 记忆高度
const {
  panelShellRef,
  isPanelResizing,
  panelShellStyle,
  startPanelResize,
} = usePanelVerticalResize({
  activeTabId,
  hasTableTab,
})

</script>

<style scoped>
.right-root {
  --bg: #f4f8ff;
  --panel: #ffffff;
  --panel-muted: #f6f9ff;
  --line: #d8e3f1;
  --line-strong: #c3d4e7;
  --text: #19314d;
  --text-sub: #5d7491;
  --text-muted: #8298b2;
  --accent: #2f6aa3;
  --accent-soft: #e9f1fb;
  --tab-bg: #eef4fc;
  --tab-active: #ffffff;
  --flow-task-bg: #f7fbff;
  --flow-table-bg: #ffffff;

  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  color: var(--text);
  font-family: 'IBM Plex Sans', 'Avenir Next', 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  overflow: hidden;
}

/* 统一滚动条样式 */
.right-root :deep(*::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.right-root :deep(*::-webkit-scrollbar-track) {
  background: transparent;
  border-radius: 3px;
}

.right-root :deep(*::-webkit-scrollbar-thumb) {
  background: var(--line);
  border-radius: 3px;
  transition: background 0.2s ease;
}

.right-root :deep(*::-webkit-scrollbar-thumb:hover) {
  background: var(--line-strong);
}

.variant-paper-blueprint {
  --bg: #f8fbff;
  --panel: #ffffff;
  --panel-muted: #f8fbff;
  --line: #d7e2f1;
  --line-strong: #bdd0e6;
  --text: #1b334f;
  --text-sub: #5a7394;
  --text-muted: #8699b1;
  --accent: #356ea8;
  --accent-soft: #ecf3fd;
  --tab-bg: #f1f6fd;
  --tab-active: #ffffff;
  --flow-task-bg: #f8fbff;
  --flow-table-bg: #ffffff;
}

.variant-signal-cards {
  --bg: #eff6ff;
  --panel: #ffffff;
  --panel-muted: #f3f8ff;
  --line: #d2dff0;
  --line-strong: #b7cae3;
  --text: #163050;
  --text-sub: #55739a;
  --text-muted: #7f97b6;
  --accent: #245f99;
  --accent-soft: #e4eefb;
  --tab-bg: #eaf2fd;
  --tab-active: #ffffff;
  --flow-task-bg: #f2f8ff;
  --flow-table-bg: #ffffff;
}

.variant-minimal-blue {
  --bg: #f9fbff;
  --panel: #ffffff;
  --panel-muted: #ffffff;
  --line: #dce6f3;
  --line-strong: #c7d7ea;
  --text: #1f3652;
  --text-sub: #637b98;
  --text-muted: #8ba0b9;
  --accent: #3c78b1;
  --accent-soft: #edf4fe;
  --tab-bg: #f3f7fd;
  --tab-active: #ffffff;
  --flow-task-bg: #ffffff;
  --flow-table-bg: #ffffff;
}

.variant-navicat-blue {
  --bg: #eef2f8;
  --panel: #ffffff;
  --panel-muted: #f5f8fc;
  --line: #cfd9e6;
  --line-strong: #bac8d9;
  --text: #24364e;
  --text-sub: #5f738c;
  --text-muted: #8697ac;
  --accent: #3f6f9e;
  --accent-soft: #e9f0f9;
  --tab-bg: #e8edf4;
  --tab-active: #ffffff;
  --flow-task-bg: #f5f8fc;
  --flow-table-bg: #ffffff;
}

.variant-tech-grid {
  --bg: #f2f5fa;
  --panel: #ffffff;
  --panel-muted: #f8fafd;
  --line: #dae1eb;
  --line-strong: #c5d0df;
  --text: #1a2f47;
  --text-sub: #5a6e87;
  --text-muted: #7d91ab;
  --accent: #2563b8;
  --accent-soft: #e6eef8;
  --tab-bg: #eff3f9;
  --tab-active: #ffffff;
  --flow-task-bg: #f8fafd;
  --flow-table-bg: #ffffff;
}

.variant-clean-slate {
  --bg: #fafbfd;
  --panel: #ffffff;
  --panel-muted: #ffffff;
  --line: #e4e8f0;
  --line-strong: #d0d7e3;
  --text: #212d3f;
  --text-sub: #657186;
  --text-muted: #8a99b0;
  --accent: #4178c1;
  --accent-soft: #eff5fd;
  --tab-bg: #f5f8fc;
  --tab-active: #ffffff;
  --flow-task-bg: #ffffff;
  --flow-table-bg: #fafbfd;
}

.metadata-missing-alert :deep(.metadata-sync-action.el-button--primary.is-link) {
  color: #2563eb;
}

.metadata-missing-alert :deep(.metadata-sync-action.el-button--primary.is-link:hover),
.metadata-missing-alert :deep(.metadata-sync-action.el-button--primary.is-link:focus) {
  color: #1d4ed8;
}

.variant-data-console {
  --bg: #f0f4f9;
  --panel: #ffffff;
  --panel-muted: #f6f9fc;
  --line: #d4dce8;
  --line-strong: #bfcbdb;
  --text: #1d3047;
  --text-sub: #5b6f89;
  --text-muted: #7e93ac;
  --accent: #2d66a4;
  --accent-soft: #e8f0f9;
  --tab-bg: #ebeef4;
  --tab-active: #ffffff;
  --flow-task-bg: #f6f9fc;
  --flow-table-bg: #ffffff;
}

.panel-shell {
  --right-top: 340px;
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-rows: var(--right-top) 6px minmax(280px, 1fr);
  gap: 0;
}

.meta-panel {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  overflow: hidden;
  min-height: 0;
}

.metadata-missing-alert {
  border-radius: 0;
  border-width: 0 0 1px;
}

.metadata-missing-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.panel-resizer {
  cursor: row-resize;
  position: relative;
  background: transparent;
}

.panel-resizer::after {
  content: '⋯';
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 14px;
  line-height: 1;
  color: var(--text-muted);
  padding: 0 8px 2px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease, color 0.15s ease;
}

.panel-resizer:hover::after,
.right-root.is-pane-resizing .panel-resizer::after {
  opacity: 1;
  color: var(--text-sub);
}

.lineage-pane {
  min-height: 0;
  height: 100%;
}

.right-root.is-pane-resizing {
  user-select: none;
}

.meta-tabs {
  height: 100%;
}

:deep(.detail-tabs > .el-tabs__header) {
  margin: 0;
  padding: 8px 10px 6px;
  border-bottom: 1px solid var(--line);
  box-sizing: border-box;
}

:deep(.detail-tabs .el-tabs__nav-wrap::after) {
  display: none;
}

:deep(.detail-tabs .el-tabs__active-bar) {
  display: none;
}

:deep(.detail-tabs .el-tabs__nav) {
  float: none;
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border-radius: 8px;
  background: var(--tab-bg);
  border: 1px solid var(--line);
}

:deep(.detail-tabs .el-tabs__item) {
  height: 28px;
  line-height: 28px;
  border-radius: 6px;
  border: 1px solid transparent;
  padding: 0 10px;
  font-weight: 600;
  color: var(--text-sub);
  transition: background-color 100ms ease, color 100ms ease, border-color 100ms ease;
}

:deep(.detail-tabs .el-tabs__item.is-active) {
  color: var(--text);
  border-color: var(--line);
  background: var(--tab-active);
}

:deep(.detail-tabs .el-tabs__content) {
  height: calc(100% - 44px);
  padding: 10px;
  box-sizing: border-box;
}

:deep(.detail-tabs .el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.meta-tabs :deep(.meta-section) {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-tabs :deep(.meta-section-fill) {
  flex: 1;
  min-height: 0;
}

.meta-tabs :deep(.section-block) {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-muted);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.meta-tabs :deep(.section-fill) {
  flex: 1;
}

.meta-tabs :deep(.section-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.meta-tabs :deep(.section-title) {
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
}

.meta-tabs :deep(.section-actions) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-tabs :deep(.meta-scroll) {
  flex: 1;
  min-height: 0;
  max-height: 100%;
  overflow: auto;
}

.meta-tabs :deep(.meta-scroll .el-scrollbar__view) {
  padding-right: 4px;
  box-sizing: border-box;
}

.code-shell {
  flex: 1;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfdff;
}

.ddl-scroll {
  height: 100%;
  font-family: 'JetBrains Mono', 'IBM Plex Mono', 'Fira Mono', Menlo, Consolas, monospace;
}

.ddl-content {
  margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
  color: #1f3552;
}

.ddl-placeholder {
  padding: 10px 12px;
  font-size: 12px;
  color: var(--text-muted);
}

.lineage-panel {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  padding: 10px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  min-height: 0;
  max-height: none;
  overflow-y: auto;
  overflow-x: hidden;
  flex: 1;
}

.right-empty {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--line-strong);
  border-radius: 10px;
  background: var(--panel);
}
</style>
