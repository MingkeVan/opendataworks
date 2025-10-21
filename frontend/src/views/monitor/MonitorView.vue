<template>
  <div class="monitor-view">
    <el-card class="monitor-card">
      <template #header>
        <div class="card-header">
          <span>执行监控</span>
        </div>
      </template>

      <div style="text-align: center; padding: 60px 0;">
        <el-icon :size="80" color="#909399"><Monitor /></el-icon>
        <p style="margin-top: 20px; color: #606266;">此功能正在开发中，将提供任务执行状态、日志查看、性能监控等功能</p>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { Monitor } from '@element-plus/icons-vue'
</script>

<style scoped>
.monitor-view {
  padding: 6px;
}

.monitor-card {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
