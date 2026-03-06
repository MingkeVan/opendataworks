<template>
  <div class="nl2sql-view">
    <NL2SqlChat :nl2sql-base="nl2sqlBase" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import NL2SqlChat from './NL2SqlChat.vue'

const nl2sqlBase = ref(
  import.meta.env.VITE_NL2SQL_BASE ?? ''
)
</script>

<style scoped>
.nl2sql-view {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  overflow: hidden;
}
</style>
