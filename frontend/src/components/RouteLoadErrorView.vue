<template>
  <div class="route-load-error">
    <el-result icon="warning" title="页面加载失败" :sub-title="subTitle">
      <template #extra>
        <el-button type="primary" @click="reload">重新加载</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 路由 chunk 加载失败时的占位。defineAsyncComponent 只向 errorComponent 传 error，
// 不传 retry；chunk 加载失败多半是发版换了文件 hash，整页重载才是对的处理。
const props = defineProps({
  error: {
    type: [Object, String],
    default: null
  }
})

const subTitle = computed(() => {
  const message = typeof props.error === 'string' ? props.error : props.error?.message
  return message ? `${message}，请重新加载页面` : '网络异常或版本已更新，请重新加载页面'
})

const reload = () => {
  window.location.reload()
}
</script>

<style scoped>
.route-load-error {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
