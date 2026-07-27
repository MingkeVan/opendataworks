<template>
  <div v-loading="loading" class="agent-settings">
    <el-alert type="info" :closable="false" show-icon class="agent-alert">
      <template #title>
        指定 Data Studio「智能元数据」生成时使用的助手。助手清单来自智能问数，可见范围受助手自身配置约束。
      </template>
    </el-alert>

    <el-form label-width="160px" class="agent-form">
      <el-form-item label="智能元数据助手">
        <el-select
          v-model="metadataAgentId"
          class="agent-select"
          clearable
          filterable
          :disabled="loading || !agents.length"
          placeholder="请选择助手"
        >
          <el-option
            v-for="agent in agents"
            :key="agent.agent_id"
            :label="agent.display_name || agent.agent_id"
            :value="agent.agent_id"
          >
            <span class="agent-option-name">{{ agent.display_name || agent.agent_id }}</span>
            <span class="agent-option-id">{{ agent.agent_id }}</span>
          </el-option>
        </el-select>
        <div v-if="agentsError" class="agent-hint is-error">{{ agentsError }}</div>
        <div v-else-if="!agents.length" class="agent-hint is-error">
          没有可用的助手，请先在智能问数中创建助手，或检查助手的可见范围设置
        </div>
        <div v-else-if="!metadataAgentId" class="agent-hint">未配置时，「智能元数据」会提示先来此处选择助手</div>
        <div v-else-if="savedMissing" class="agent-hint is-error">
          已保存的助手 {{ savedAgentId }} 不在当前可用清单中，请重新选择
        </div>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" :disabled="loading || !dirty" @click="save">保存</el-button>
        <el-button :disabled="loading || saving" @click="load">刷新</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { settingsApi } from '@/api/settings'
import { nl2sqlApi, nl2sqlErrorMessage } from '@/api/nl2sql'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'

const loading = ref(false)
const saving = ref(false)
const agents = ref([])
const agentsError = ref('')
const metadataAgentId = ref('')
const savedAgentId = ref('')

const dirty = computed(() => metadataAgentId.value !== savedAgentId.value)

// 已保存的助手可能因为被删除或可见范围收紧而不在清单里，需要显式提示而不是静默失效
const savedMissing = computed(
  () =>
    !!savedAgentId.value &&
    agents.value.length > 0 &&
    !agents.value.some((agent) => agent.agent_id === savedAgentId.value)
)

const load = async () => {
  loading.value = true
  agentsError.value = ''
  try {
    const [agentList, settings] = await Promise.all([
      nl2sqlApi.listAgents().catch((error) => {
        agentsError.value = nl2sqlErrorMessage(error, '获取助手清单失败')
        return []
      }),
      settingsApi.getAgentSettings().catch(() => ({}))
    ])
    agents.value = Array.isArray(agentList) ? agentList.filter((item) => item?.agent_id) : []
    savedAgentId.value = String(settings?.metadataAgentId || '')
    metadataAgentId.value = savedAgentId.value
  } finally {
    loading.value = false
  }
}

const save = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存智能助手设置')
    return
  }
  saving.value = true
  try {
    const result = await settingsApi.updateAgentSettings({ metadataAgentId: metadataAgentId.value || '' })
    savedAgentId.value = String(result?.metadataAgentId ?? metadataAgentId.value ?? '')
    metadataAgentId.value = savedAgentId.value
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error(error?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.agent-settings {
  padding: 4px 0;
}
.agent-alert {
  margin-bottom: 16px;
}
.agent-form {
  max-width: 720px;
}
.agent-select {
  width: 360px;
}
.agent-option-name {
  margin-right: 12px;
}
.agent-option-id {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.agent-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.agent-hint.is-error {
  color: var(--el-color-danger);
}
</style>
