<template>
  <div class="version-compare-panel" v-loading="loading">
    <div class="toolbar">
      <el-button @click="$emit('back')">返回记录列表</el-button>
      <div class="step-actions">
        <el-button size="small" @click="$emit('step', 'left')">向左</el-button>
        <el-button size="small" @click="$emit('step', 'right')">向右</el-button>
      </div>
    </div>

    <div class="version-hint" v-if="compareResult">
      <el-tag size="small" type="info">左侧版本: {{ compareResult.leftVersionNo ?? '空基线' }}</el-tag>
      <el-tag size="small" type="primary">右侧版本: {{ compareResult.rightVersionNo }}</el-tag>
      <el-tag size="small" :type="compareResult.changed ? 'warning' : 'success'">
        {{ compareResult.changed ? '有变化' : '无变化' }}
      </el-tag>
    </div>

    <WorkflowVersionBlockList
      :versions="versions"
      :left-version-id="leftVersionId"
      :right-version-id="rightVersionId"
      :rollback-loading-id="rollbackLoadingId"
      @select-right="$emit('select-right', $event)"
      @rollback="$emit('rollback', $event)"
    />

    <div v-if="compareResult" class="summary-row">
      <span>新增: {{ compareResult.summary?.added || 0 }}</span>
      <span>删除: {{ compareResult.summary?.removed || 0 }}</span>
      <span>修改: {{ compareResult.summary?.modified || 0 }}</span>
      <span>不变: {{ compareResult.summary?.unchanged || 0 }}</span>
    </div>

    <div class="diff-sections" v-if="compareResult">
      <div class="diff-card">
        <div class="diff-title">新增</div>
        <DiffSectionView :section="compareResult.added" type="success" />
      </div>
      <div class="diff-card">
        <div class="diff-title">删除</div>
        <DiffSectionView :section="compareResult.removed" type="danger" />
      </div>
      <div class="diff-card">
        <div class="diff-title">修改</div>
        <DiffSectionView :section="compareResult.modified" type="warning" />
      </div>
      <div class="diff-card">
        <div class="diff-title">不变</div>
        <DiffSectionView :section="compareResult.unchanged" type="info" />
      </div>
    </div>
  </div>
</template>

<script setup>
import WorkflowVersionBlockList from './WorkflowVersionBlockList.vue'
import DiffSectionView from './WorkflowVersionDiffSectionView.vue'

defineProps({
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
  },
  compareResult: {
    type: Object,
    default: null
  },
  loading: {
    type: Boolean,
    default: false
  },
  rollbackLoadingId: {
    type: Number,
    default: null
  }
})

defineEmits(['back', 'step', 'select-right', 'rollback'])
</script>

<style scoped>
.version-compare-panel {
  margin-top: 12px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.step-actions {
  display: flex;
  gap: 8px;
}

.version-hint {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.summary-row {
  display: flex;
  gap: 14px;
  margin: 12px 0;
  color: #606266;
  font-size: 13px;
}

.diff-sections {
  display: grid;
  grid-template-columns: repeat(2, minmax(260px, 1fr));
  gap: 10px;
}

.diff-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  background: #fff;
}

.diff-title {
  font-weight: 600;
  margin-bottom: 8px;
}
</style>
