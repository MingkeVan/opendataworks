<template>
  <div class="intelligent-query-view">
    <NL2SqlChat />
  </div>
</template>

<script setup>
import NL2SqlChat from './NL2SqlChat.vue'
</script>

<style scoped>
.intelligent-query-view {
  min-height: 100%;
  height: 100%;
  padding: 16px;
  background:
    radial-gradient(circle at top right, rgba(56, 189, 248, 0.14), transparent 26%),
    linear-gradient(180deg, #f4f7fb 0%, #edf2f8 100%);
}

@media (max-width: 768px) {
  .intelligent-query-view {
    padding: 12px;
  }
}
</style>
