<template>
  <div class="configuration-management">
    <div class="page-header">
      <h2>配置管理</h2>
    </div>

    <el-tabs v-model="activeTab" class="config-tabs">
      <el-tab-pane label="数据源" name="datasource">
        <DataSourceManagement />
      </el-tab-pane>
      <el-tab-pane label="Dolphin 配置" name="dolphin">
        <DolphinConfig />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import DataSourceManagement from './DataSourceManagement.vue'
import DolphinConfig from './DolphinConfig.vue'

const activeTab = ref('datasource')
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

.config-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

:deep(.el-tabs__content) {
  flex: 1;
  overflow-y: auto;
  padding: 20px 0;
}
</style>
