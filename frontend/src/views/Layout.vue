<template>
  <el-container class="layout-container">
    <el-header height="60px">
      <div class="header-wrapper">
        <div class="logo">
          <h2>数据门户</h2>
        </div>
        <el-menu
          :default-active="activeMenu"
          router
          mode="horizontal"
          class="menu"
        >
          <el-menu-item index="/tables">
            <el-icon><Grid /></el-icon>
            <span>表管理</span>
          </el-menu-item>
          <el-menu-item index="/query">
            <el-icon><Search /></el-icon>
            <span>数据查询</span>
          </el-menu-item>
          <el-menu-item index="/domains">
            <el-icon><Collection /></el-icon>
            <span>域管理</span>
          </el-menu-item>
          <el-menu-item index="/tasks">
            <el-icon><Management /></el-icon>
            <span>任务管理</span>
          </el-menu-item>
          <el-menu-item index="/lineage">
            <el-icon><Connection /></el-icon>
            <span>血缘关系</span>
          </el-menu-item>
          <el-menu-item index="/monitor">
            <el-icon><Monitor /></el-icon>
            <span>执行监控</span>
          </el-menu-item>
          <el-menu-item index="/inspection">
            <el-icon><Warning /></el-icon>
            <span>数据巡检</span>
          </el-menu-item>
        </el-menu>
      </div>
    </el-header>

    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Grid, Management, Connection, Monitor, Collection, Warning, Search } from '@element-plus/icons-vue'

const route = useRoute()
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/tables')) {
    return '/tables'
  }
  if (path.startsWith('/query')) {
    return '/query'
  }
  if (path.startsWith('/domains')) {
    return '/domains'
  }
  if (path.startsWith('/tasks')) {
    return '/tasks'
  }
  if (path.startsWith('/lineage')) {
    return '/lineage'
  }
  if (path.startsWith('/monitor')) {
    return '/monitor'
  }
  if (path.startsWith('/inspection')) {
    return '/inspection'
  }
  return path
})
</script>

<style scoped>
.layout-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.el-header {
  background-color: #304156;
  padding: 0;
  border-bottom: 2px solid #263445;
}

.header-wrapper {
  display: flex;
  align-items: center;
  height: 100%;
}

.logo {
  height: 60px;
  min-width: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #263445;
}

.logo h2 {
  color: #fff;
  font-size: 18px;
  margin: 0;
}

.menu {
  flex: 1;
  border: none;
  background-color: #304156;
}

.el-menu--horizontal {
  border-bottom: none;
}

.el-menu-item {
  color: #bfcbd9;
  border-bottom: none;
}

.el-menu-item:hover,
.el-menu-item.is-active {
  background-color: #263445 !important;
  color: #409eff !important;
  border-bottom: 2px solid #409eff;
}

.el-main {
  background-color: #f0f2f5;
  padding: 20px;
  flex: 1;
  overflow-y: auto;
}
</style>
