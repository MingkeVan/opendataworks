<template>
  <div class="task-list">
    <el-card shadow="always" :body-style="{ padding: '20px' }">
      <TaskTable />
    </el-card>
  </div>
</template>

<script setup>
import TaskTable from './TaskTable.vue'
</script>

<style scoped>
.task-list {
  height: 100%;
  padding: 6px;
}

.task-list :deep(.el-card) {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
</style>
