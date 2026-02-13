<template>
  <div class="version-block-list">
    <div
      v-for="version in versions"
      :key="version.id"
      class="version-block"
      :class="{
        'is-right': version.id === rightVersionId,
        'is-left': version.id === leftVersionId
      }"
      @click="handleSelect(version)"
    >
      <div class="version-header">
        <span class="version-no">v{{ version.versionNo }}</span>
        <div class="version-tags">
          <el-tag v-if="version.id === rightVersionId" size="small" type="primary">右</el-tag>
          <el-tag v-if="version.id === leftVersionId" size="small" type="info">左</el-tag>
        </div>
      </div>
      <div class="version-meta">{{ formatDateTime(version.createdAt) }}</div>
      <div class="version-meta">{{ version.createdBy || '-' }}</div>
    </div>
  </div>
</template>

<script setup>
import dayjs from 'dayjs'

const props = defineProps({
  versions: {
    type: Array,
    default: () => []
  },
  leftVersionId: {
    type: Number,
    default: null
  },
  rightVersionId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits(['select-right'])

const handleSelect = (version) => {
  if (!version?.id) {
    return
  }
  emit('select-right', version)
}

const formatDateTime = (value) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}
</script>

<style scoped>
.version-block-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 10px;
}

.version-block {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fff;
  padding: 10px;
  cursor: pointer;
  transition: border-color 0.2s ease;
}

.version-block:hover {
  border-color: #409eff;
}

.version-block.is-right {
  border-color: #409eff;
  box-shadow: 0 0 0 1px rgba(64, 158, 255, 0.2) inset;
}

.version-block.is-left {
  border-color: #67c23a;
}

.version-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.version-no {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.version-meta {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
</style>
