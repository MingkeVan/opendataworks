<template>
  <div class="diff-content">
    <div class="diff-group">
      <div class="group-title">workflow 字段</div>
      <div class="tag-wrap">
        <el-tag v-for="item in section?.workflowFields || []" :key="`wf-${item}`" :type="type" size="small" class="item-tag">{{ item }}</el-tag>
        <span v-if="!(section?.workflowFields || []).length" class="empty-text">-</span>
      </div>
    </div>
    <div class="diff-group">
      <div class="group-title">任务</div>
      <div class="tag-wrap">
        <el-tag v-for="item in section?.tasks || []" :key="`task-${item}`" :type="type" size="small" class="item-tag">{{ item }}</el-tag>
        <span v-if="!(section?.tasks || []).length" class="empty-text">-</span>
      </div>
    </div>
    <div class="diff-group">
      <div class="group-title">边</div>
      <div class="tag-wrap">
        <el-tag v-for="item in section?.edges || []" :key="`edge-${item}`" :type="type" size="small" class="item-tag">{{ item }}</el-tag>
        <span v-if="!(section?.edges || []).length" class="empty-text">-</span>
      </div>
    </div>
    <div class="diff-group">
      <div class="group-title">调度</div>
      <div class="tag-wrap">
        <el-tag v-for="item in section?.schedules || []" :key="`sch-${item}`" :type="type" size="small" class="item-tag">{{ item }}</el-tag>
        <span v-if="!(section?.schedules || []).length" class="empty-text">-</span>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  section: {
    type: Object,
    default: () => ({})
  },
  type: {
    type: String,
    default: 'info'
  }
})
</script>

<style scoped>
.diff-group {
  margin-bottom: 8px;
}

.group-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.tag-wrap {
  display: flex;
  flex-wrap: wrap;
}

.item-tag {
  margin: 0 6px 6px 0;
}

.empty-text {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
