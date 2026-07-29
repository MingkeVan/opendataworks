<template>
  <div v-loading="loading" class="agent-settings">
    <SettingsSection title="智能助手">
      <template #actions>
        <el-button :disabled="loading || saving" :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :loading="saving" :disabled="loading || !dirty" @click="save">保存</el-button>
      </template>

      <el-alert v-if="alert" :type="alert.type" :closable="false" show-icon class="agent-alert">
        <template #title>{{ alert.text }}</template>
      </el-alert>

      <SettingsRow label="智能元数据助手" description="用于生成表与字段描述">
        <div class="agent-field">
          <el-select
            v-model="metadataAgentId"
            class="agent-select"
            clearable
            filterable
            :disabled="loading || !agents.length"
            :placeholder="agents.length ? '请选择助手' : '暂无可用助手'"
          >
            <el-option
              v-for="agent in agents"
              :key="agent.agent_id"
              :label="agent.display_name || agent.agent_id"
              :value="agent.agent_id"
            >
              <div class="agent-option">
                <span>{{ agent.display_name || agent.agent_id }}</span>
                <span class="agent-option-id">{{ agent.agent_id }}</span>
              </div>
            </el-option>
          </el-select>
          <el-tag :type="statusTag.type" size="small" effect="plain">{{ statusTag.text }}</el-tag>
        </div>
      </SettingsRow>
    </SettingsSection>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { settingsApi } from '@/api/settings'
import { nl2sqlApi, nl2sqlErrorMessage } from '@/api/nl2sql'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import SettingsSection from './components/SettingsSection.vue'
import SettingsRow from './components/SettingsRow.vue'

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

const statusTag = computed(() => {
  if (!savedAgentId.value) return { type: 'info', text: '未配置' }
  if (savedMissing.value) return { type: 'danger', text: '助手不可用' }
  return { type: 'success', text: '已生效' }
})

const alert = computed(() => {
  if (agentsError.value) {
    return { type: 'error', text: `获取助手清单失败：${agentsError.value}` }
  }
  if (!agents.value.length) {
    return { type: 'warning', text: '没有可用的助手，请先在智能问数中创建助手，或检查助手的可见范围设置' }
  }
  if (savedMissing.value) {
    return { type: 'error', text: `已保存的助手 ${savedAgentId.value} 不在当前可用清单中，请重新选择并保存` }
  }
  if (!savedAgentId.value) {
    return { type: 'info', text: '尚未配置助手，「智能元数据」会提示先来此处选择' }
  }
  return null
})

const load = async () => {
  loading.value = true
  agentsError.value = ''
  try {
    const [agentList, settings] = await Promise.all([
      nl2sqlApi.listAgents().catch((error) => {
        agentsError.value = nl2sqlErrorMessage(error, '请稍后重试')
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
  min-width: 0;
}

.agent-alert {
  margin: 16px 0 0;
}

.agent-field {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.agent-select {
  width: 320px;
  max-width: 100%;
}

.agent-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.agent-option-id {
  font-size: 12px;
  color: var(--odw-text-secondary);
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

</style>
