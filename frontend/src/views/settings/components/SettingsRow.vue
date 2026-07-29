<template>
  <div class="settings-row">
    <div class="settings-row-label">
      <span class="settings-row-title">{{ label }}</span>
      <span v-if="description" class="settings-row-desc">{{ description }}</span>
    </div>
    <div class="settings-row-control">
      <slot />
    </div>
  </div>
</template>

<script setup>
// 单条设置项：左侧标签与说明，右侧控件，行间以细分隔线区隔。
defineProps({
  label: { type: String, required: true },
  description: { type: String, default: '' }
})
</script>

<style scoped>
.settings-row {
  display: grid;
  grid-template-columns: minmax(200px, 280px) minmax(0, 1fr);
  gap: 24px;
  align-items: start;
  padding: 18px 0;
  border-bottom: 1px solid var(--odw-border);
}

.settings-row:last-child {
  border-bottom: none;
}

.settings-row-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.settings-row-title {
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
  color: var(--odw-text-primary);
}

.settings-row-desc {
  font-size: 12px;
  line-height: 1.6;
  color: var(--odw-text-secondary);
}

.settings-row-control {
  min-width: 0;
}

@media (max-width: 900px) {
  .settings-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }
}
</style>
