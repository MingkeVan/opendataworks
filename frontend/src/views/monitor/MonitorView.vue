<template>
  <div class="monitor-view">
    <el-card>
      <div style="text-align: center; padding: 100px 0;">
        <el-icon :size="80" color="#909399"><Monitor /></el-icon>
        <h3>执行监控</h3>
        <p>此功能正在开发中，将提供任务执行状态、日志查看、性能监控等功能</p>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { Monitor } from '@element-plus/icons-vue'
</script>

<style scoped>
.monitor-view {
  height: 100%;
}
</style>
