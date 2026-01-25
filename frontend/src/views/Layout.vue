<template>
  <el-container class="layout-container">
    <el-header height="60px">
      <div class="header-wrapper">
        <div class="logo">
          <picture class="logo-icon">
            <source srcset="/opendataworks-icon-dark.svg" media="(prefers-color-scheme: dark)">
            <img src="/opendataworks-icon-light.svg" alt="OpenDataWorks 图标">
          </picture>
          <h2>数据门户</h2>
        </div>
        <el-menu
          :default-active="activeMenu"
          router
          mode="horizontal"
          class="menu"
        >
          <el-menu-item index="/dashboard">
            <el-icon><DataBoard /></el-icon>
            <span>控制台</span>
          </el-menu-item>
          <el-menu-item index="/datastudio">
            <el-icon><DataLine /></el-icon>
            <span>Data Studio</span>
          </el-menu-item>
          <el-menu-item index="/workflows">
            <el-icon><Share /></el-icon>
            <span>任务调度</span>
          </el-menu-item>
          <el-menu-item index="/domains">
            <el-icon><Collection /></el-icon>
            <span>数据建模</span>
          </el-menu-item>
          <el-menu-item index="/lineage">
            <el-icon><Connection /></el-icon>
            <span>数据血缘</span>
          </el-menu-item>
          <el-menu-item index="/inspection">
            <el-icon><Warning /></el-icon>
            <span>数据质量</span>
          </el-menu-item>
          <el-menu-item index="/integration">
            <el-icon><Link /></el-icon>
            <span>数据集成</span>
          </el-menu-item>
          <el-menu-item index="/settings">
            <el-icon><Setting /></el-icon>
            <span>管理员</span>
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
import { DataBoard, DataLine, Connection, Collection, Warning, Setting, Share, Link } from '@element-plus/icons-vue'

const route = useRoute()
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/dashboard')) {
    return '/dashboard'
  }
  if (path.startsWith('/datastudio')) {
    return '/datastudio'
  }
  if (path.startsWith('/workflows') || path.startsWith('/tasks')) {
    return '/workflows'
  }
  if (path.startsWith('/domains')) {
    return '/domains'
  }
  if (path.startsWith('/lineage')) {
    return '/lineage'
  }
  if (path.startsWith('/inspection')) {
    return '/inspection'
  }
  if (path.startsWith('/integration')) {
    return '/integration'
  }
  if (path.startsWith('/settings')) {
    return '/settings'
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
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 0;
  box-shadow: 0 2px 12px rgba(102, 126, 234, 0.15);
  position: relative;
  z-index: 100;
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
  background: rgba(0, 0, 0, 0.1);
  backdrop-filter: blur(10px);
}

.logo-icon {
  width: 44px;
  height: 44px;
  margin-right: 12px;
  display: inline-flex;
}

.logo-icon img {
  width: 100%;
  height: 100%;
}

.logo h2 {
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  letter-spacing: 1px;
}

.menu {
  flex: 1;
  border: none;
  background: transparent;
}

.el-menu--horizontal {
  border-bottom: none;
}

.el-menu-item {
  color: rgba(255, 255, 255, 0.85);
  border-bottom: none;
  transition: all 0.3s ease;
  font-weight: 500;
}

.el-menu-item:hover {
  background-color: rgba(255, 255, 255, 0.1) !important;
  color: #fff !important;
  border-bottom: none;
  transform: translateY(-2px);
}

.el-menu-item.is-active {
  background-color: rgba(255, 255, 255, 0.15) !important;
  color: #fff !important;
  border-bottom: 3px solid rgba(255, 255, 255, 0.9);
  font-weight: 600;
}

.el-main {
  background-color: #f8fafc;
  padding: 4px;
  flex: 1;
  overflow-y: auto;
}
</style>
