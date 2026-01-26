<template>
  <div class="data-integration">
    <div class="page-header">
      <h2>数据集成</h2>
    </div>

    <DataSourceManagement />
  </div>
</template>

<script setup>
import DataSourceManagement from '@/views/settings/DataSourceManagement.vue'
</script>

<style scoped>
.data-integration {
  padding: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-header {
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-header h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: #1a1a1a;
}
</style>
