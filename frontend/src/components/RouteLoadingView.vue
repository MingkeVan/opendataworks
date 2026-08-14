<template>
  <div class="route-loading" role="status" aria-live="polite" aria-label="页面加载中">
    <el-skeleton class="route-loading__skeleton" animated>
      <template #template>
        <div class="route-loading__toolbar">
          <el-skeleton-item variant="button" class="route-loading__chip" />
          <el-skeleton-item variant="button" class="route-loading__chip" />
          <el-skeleton-item variant="text" class="route-loading__chip-grow" />
        </div>
        <div class="route-loading__body">
          <el-skeleton-item variant="rect" class="route-loading__side" />
          <el-skeleton-item variant="rect" class="route-loading__main" />
        </div>
      </template>
    </el-skeleton>
  </div>
</template>

<script setup>
// 路由 chunk 加载中的占位。路由组件由 router/lazyView.js 包成异步组件后，
// 导航不再等待 chunk，未就绪的区域改由这个骨架屏顶上。
</script>

<style scoped>
.route-loading {
  height: 100%;
  min-height: 240px;
  padding: 12px;
  box-sizing: border-box;
}

.route-loading__skeleton {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.route-loading__toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.route-loading__chip {
  width: 96px;
  height: 28px;
  border-radius: 8px;
}

.route-loading__chip-grow {
  flex: 1;
  height: 16px;
  border-radius: 8px;
}

.route-loading__body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 12px;
}

.route-loading__side {
  width: 22%;
  min-width: 180px;
  height: 100%;
  border-radius: 10px;
}

.route-loading__main {
  flex: 1;
  height: 100%;
  border-radius: 10px;
}
</style>
