<template>
  <div v-if="visible && commands.length" class="slash-menu" role="listbox">
    <div class="slash-menu-title">命令</div>
    <button
      v-for="(cmd, i) in commands"
      :key="cmd.id"
      type="button"
      class="slash-menu-item"
      :class="{ 'is-active': i === activeIndex }"
      role="option"
      :aria-selected="i === activeIndex"
      @mousedown.prevent="$emit('select', cmd)"
      @mouseenter="$emit('hover', i)"
    >
      <span class="slash-menu-id">{{ cmd.id }}</span>
      <span class="slash-menu-label">{{ cmd.label }}</span>
      <span class="slash-menu-hint">{{ cmd.hint }}</span>
    </button>
  </div>
</template>

<script setup>
defineProps({
  visible: { type: Boolean, default: false },
  commands: { type: Array, default: () => [] },
  activeIndex: { type: Number, default: 0 },
})
defineEmits(['select', 'hover'])
</script>

<style scoped>
.slash-menu {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 6px);
  max-height: 260px;
  overflow-y: auto;
  background: #fff;
  border: 1px solid #E3E8F0;
  border-radius: 12px;
  box-shadow: 0 8px 28px rgba(20, 33, 61, 0.12);
  padding: 6px;
  z-index: 30;
}

.slash-menu-title {
  padding: 4px 10px;
  font-size: 11px;
  font-weight: 600;
  color: #9AA4B2;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.slash-menu-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: #2B3445;
}

.slash-menu-item:hover,
.slash-menu-item.is-active {
  background: #EEF3FB;
}

.slash-menu-id {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
  color: #3B6FE0;
  white-space: nowrap;
  max-width: 55%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.slash-menu-label {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: #5A6473;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slash-menu-hint {
  font-size: 11px;
  color: #A0AABF;
  white-space: nowrap;
}
</style>
