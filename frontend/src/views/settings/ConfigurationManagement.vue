<template>
  <div class="configuration-management">
    <div class="page-header">
      <h2>配置管理</h2>
    </div>

    <el-card shadow="never" class="config-tabs-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="Dolphin 配置" name="dolphin">
          <DolphinConfig />
        </el-tab-pane>
        <el-tab-pane label="MinIO 环境" name="minio">
          <MinioConfigManagement />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import DolphinConfig from './DolphinConfig.vue'
import MinioConfigManagement from './MinioConfigManagement.vue'

const activeTab = ref('dolphin')
</script>

<style scoped>
.configuration-management {
  padding: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-header {
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: #1a1a1a;
}

.config-tabs-card :deep(.el-card__body) {
  padding: 16px;
}
</style>
