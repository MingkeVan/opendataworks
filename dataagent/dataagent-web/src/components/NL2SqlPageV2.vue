<template>
  <div class="nl2sql-v2-view">
    <NL2SqlChatV2 :nl2sql-base="nl2sqlBase" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import NL2SqlChatV2 from './NL2SqlChatV2.vue'

const nl2sqlBase = ref(
  import.meta.env.VITE_NL2SQL_BASE || 'http://localhost:8900'
)
</script>

<style scoped>
.nl2sql-v2-view {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  overflow: hidden;
}
</style>
