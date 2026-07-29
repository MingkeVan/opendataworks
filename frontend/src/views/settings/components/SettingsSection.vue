<template>
  <section class="settings-section">
    <header class="settings-section-head">
      <div class="settings-section-heading">
        <h3 class="settings-section-title">{{ title }}</h3>
        <p v-if="description" class="settings-section-desc">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="settings-section-actions">
        <slot name="actions" />
      </div>
    </header>

    <div class="settings-section-body">
      <slot />
    </div>
  </section>
</template>

<script setup>
// 设置分组的统一骨架：标题 + 说明 + 右侧操作，内容直接落在页面上，不套卡片。
defineProps({
  title: { type: String, required: true },
  description: { type: String, default: '' }
})
</script>

<style scoped>
.settings-section {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.settings-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--odw-border);
}

.settings-section-heading {
  min-width: 0;
}

.settings-section-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--odw-text-primary);
}

.settings-section-desc {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--odw-text-secondary);
}

.settings-section-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.settings-section-body {
  min-width: 0;
  padding-top: 4px;
}
</style>
