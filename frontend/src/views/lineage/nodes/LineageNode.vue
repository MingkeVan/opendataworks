<template>
  <div class="lineage-node" :class="{ selected }" :style="{ '--layer-color': layerColor }">
    <Handle class="handle target" type="target" :position="targetHandlePosition" :connectable="false" />
    <Handle class="handle source" type="source" :position="sourceHandlePosition" :connectable="false" />
    <div class="header">
      <div class="title" :title="data?.name || ''">
        {{ data?.name || '-' }}
      </div>
      <div v-if="data?.layer" class="layer">
        {{ data.layer }}
      </div>
    </div>
    <div class="meta">
      <div class="degrees">
        <span>In {{ data?.inDegree ?? 0 }}</span>
        <span>Out {{ data?.outDegree ?? 0 }}</span>
      </div>
      <div v-if="data?.comment" class="comment" :title="data.comment">
        {{ data.comment }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, watch } from 'vue'
import { Handle } from '@vue-flow/core'
import { getLayerColor } from '../lineageFlow'

const props = defineProps({
  data: { type: Object, required: true },
  selected: { type: Boolean, default: false },
  onUpdateNodeInternals: { type: Function, default: null }
})

const layerColor = computed(() => getLayerColor(props.data?.layer))
const rankdir = computed(() => String(props.data?.rankdir || 'LR').toUpperCase())
const isVertical = computed(() => rankdir.value === 'TB' || rankdir.value === 'BT')
const targetHandlePosition = computed(() => (isVertical.value ? 'top' : 'left'))
const sourceHandlePosition = computed(() => (isVertical.value ? 'bottom' : 'right'))

watch(
  () => props.data?.rankdir,
  async () => {
    await nextTick()
    props.onUpdateNodeInternals?.()
  }
)
</script>

<style scoped>
.lineage-node {
  width: 240px;
  height: 88px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  border-left: 4px solid var(--layer-color);
  display: flex;
  flex-direction: column;
  gap: 6px;
  user-select: none;
}

.handle {
  opacity: 0;
  width: 8px;
  height: 8px;
  border: none;
  background: transparent;
  pointer-events: none;
}

.lineage-node.selected {
  border-color: rgba(0, 0, 0, 0.12);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.10);
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.title {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.layer {
  flex: none;
  font-size: 12px;
  line-height: 18px;
  padding: 0 8px;
  border-radius: 999px;
  color: var(--layer-color);
  background: color-mix(in srgb, var(--layer-color) 14%, #ffffff);
  border: 1px solid color-mix(in srgb, var(--layer-color) 26%, #ffffff);
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.degrees {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: #6b7280;
}

.comment {
  font-size: 12px;
  color: #9ca3af;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
