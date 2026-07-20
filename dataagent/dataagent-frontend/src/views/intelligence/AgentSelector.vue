<template>
  <div class="agent-selector" :class="`is-${mode}`">
    <div v-if="loading" class="agent-selector-skeletons" aria-label="正在加载助手">
      <span v-for="index in 3" :key="index" class="agent-selector-skeleton" />
    </div>

    <div v-else-if="error" class="agent-selector-state is-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="agent-selector-retry" @click="$emit('retry')">重新加载</button>
    </div>

    <div v-else-if="!agents.length" class="agent-selector-state">
      暂无可用助手，请联系管理员配置
    </div>

    <div v-else class="agent-selector-list" role="listbox" aria-label="选择助手">
      <el-tooltip
        v-for="agent in agents"
        :key="agent.agent_id"
        :content="agent.description || `选择${agent.name}`"
        placement="top"
        :show-after="350"
      >
        <button
          type="button"
          class="agent-selector-pill"
          :class="{ 'is-selected': agent.agent_id === selectedId, 'is-busy': busy && agent.agent_id === busyId }"
          :aria-label="agentAriaLabel(agent)"
          :aria-selected="agent.agent_id === selectedId"
          :disabled="disabled || busy"
          role="option"
          @click="$emit('select', agent.agent_id)"
        >
          <svg class="agent-selector-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 2v2" />
            <rect x="4" y="6" width="16" height="12" rx="3" />
            <circle cx="9" cy="12" r="1.25" fill="currentColor" stroke="none" />
            <circle cx="15" cy="12" r="1.25" fill="currentColor" stroke="none" />
            <path d="M9 16h6" />
          </svg>
          <span class="agent-selector-name">{{ agent.name }}</span>
          <span v-if="agent.is_default" class="agent-selector-default">默认</span>
          <span v-if="busy && agent.agent_id === busyId" class="agent-selector-spinner" aria-hidden="true" />
        </button>
      </el-tooltip>
    </div>
  </div>
</template>

<script setup>
defineProps({
  agents: { type: Array, default: () => [] },
  selectedId: { type: String, default: '' },
  mode: { type: String, default: 'welcome' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
  busyId: { type: String, default: '' },
})

defineEmits(['select', 'retry'])

function agentAriaLabel(agent) {
  const parts = [String(agent?.name || '助手')]
  if (agent?.is_default) parts.push('默认助手')
  if (agent?.description) parts.push(String(agent.description))
  return parts.join('，')
}
</script>

<style scoped>
.agent-selector {
  min-width: 0;
}

.agent-selector-list,
.agent-selector-skeletons {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 14px;
}

.agent-selector-pill,
.agent-selector-skeleton {
  min-width: 154px;
  min-height: 54px;
  box-sizing: border-box;
  border-radius: 16px;
}

.agent-selector-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 12px 18px;
  border: 1px solid var(--odw-border);
  background: #fff;
  color: var(--odw-text-primary);
  box-shadow: 0 2px 7px rgba(44, 82, 130, 0.08);
  font: inherit;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--odw-transition), color var(--odw-transition), box-shadow var(--odw-transition), transform var(--odw-transition);
}

.agent-selector-pill:hover:not(:disabled),
.agent-selector-pill:focus-visible {
  border-color: var(--odw-primary-light);
  color: var(--odw-primary);
  box-shadow: 0 8px 18px rgba(44, 82, 130, 0.14);
  transform: translateY(-1px);
  outline: none;
}

.agent-selector-pill.is-selected {
  border-color: var(--odw-primary-light);
  background: #f3f7fc;
  color: var(--odw-primary);
}

.agent-selector-pill:disabled {
  cursor: default;
  opacity: 0.68;
}

.agent-selector-icon {
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  color: var(--odw-primary-light);
}

.agent-selector-name {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-selector-default {
  padding: 2px 6px;
  border-radius: 999px;
  background: #e8f1fb;
  color: var(--odw-primary);
  font-size: 10px;
  font-weight: 600;
}

.agent-selector-spinner {
  width: 13px;
  height: 13px;
  border: 2px solid rgba(44, 82, 130, 0.2);
  border-top-color: var(--odw-primary);
  border-radius: 50%;
  animation: agent-selector-spin 0.8s linear infinite;
}

.agent-selector-skeleton {
  display: block;
  background: linear-gradient(90deg, #edf1f6 25%, #f8fafc 50%, #edf1f6 75%);
  background-size: 200% 100%;
  animation: agent-selector-shimmer 1.4s ease infinite;
}

.agent-selector-state {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--odw-text-secondary);
  font-size: 14px;
  text-align: center;
}

.agent-selector-state.is-error {
  color: #b42318;
}

.agent-selector-retry {
  padding: 7px 14px;
  border: 1px solid var(--odw-border);
  border-radius: 9px;
  background: #fff;
  color: var(--odw-primary);
  font: inherit;
  cursor: pointer;
}

.agent-selector.is-popover .agent-selector-list {
  align-items: stretch;
  flex-direction: column;
  gap: 8px;
}

.agent-selector.is-popover .agent-selector-pill {
  width: 100%;
  min-width: 0;
  min-height: 44px;
  justify-content: flex-start;
  padding: 9px 12px;
  border-radius: 10px;
  box-shadow: none;
  font-size: 13px;
}

@media (max-width: 640px) {
  .agent-selector.is-welcome .agent-selector-list,
  .agent-selector.is-welcome .agent-selector-skeletons {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    width: min(100%, 320px);
    margin: 0 auto;
  }

  .agent-selector.is-welcome .agent-selector-pill,
  .agent-selector.is-welcome .agent-selector-skeleton {
    width: 100%;
  }
}

@keyframes agent-selector-spin {
  to { transform: rotate(360deg); }
}

@keyframes agent-selector-shimmer {
  to { background-position: -200% 0; }
}
</style>
