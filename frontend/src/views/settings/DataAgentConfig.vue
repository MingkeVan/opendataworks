<template>
  <div class="dataagent-config">
    <el-row :gutter="16" class="summary-row">
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="summary-card">
          <div class="summary-label">配置存储</div>
          <div class="summary-value">DataAgent 数据库 / 运行时</div>
          <div class="summary-hint">不再依赖 project `.claude/settings.json`</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="summary-card">
          <div class="summary-label">Skills 目录</div>
          <div class="summary-value path">{{ settingsMeta.skills_root_dir || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="summary-card">
          <div class="summary-label">DataAgent 库</div>
          <div class="summary-value">{{ settingsMeta.session_mysql_database || 'dataagent' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="config-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">模型接入与候选管理</div>
            <div class="card-subtitle">按供应商分组管理 Token 与模型候选。Token 不回显，留空表示保持现有值。</div>
          </div>
          <div class="actions">
            <el-button @click="loadSettings" :loading="loading">刷新</el-button>
            <el-button type="primary" @click="saveSettings" :loading="saving">保存配置</el-button>
          </div>
        </div>
      </template>

      <div v-loading="loading" class="provider-workbench">
        <aside class="provider-nav">
          <div
            v-for="group in groupedProviders"
            :key="group.group"
            class="provider-group"
          >
            <div class="provider-group-title">{{ group.group }}</div>
            <button
              v-for="provider in group.items"
              :key="provider.provider_id"
              type="button"
              class="provider-card"
              :class="{ active: provider.provider_id === selectedProviderId }"
              @click="selectedProviderId = provider.provider_id"
            >
              <div class="provider-card-head">
                <div>
                  <div class="provider-card-name">{{ provider.display_name }}</div>
                  <div class="provider-card-id">{{ provider.provider_id }}</div>
                </div>
                <span class="provider-status" :class="statusClass(providerPreview(provider).status)">
                  {{ statusLabel(providerPreview(provider).status) }}
                </span>
              </div>
              <div class="provider-card-meta">
                <span>{{ getDraft(provider.provider_id).enabled_models.length }} 个已开模型</span>
                <span>{{ credentialSummary(provider) }}</span>
              </div>
            </button>
          </div>
        </aside>

        <section v-if="currentProvider && currentDraft" class="provider-detail">
          <div class="provider-hero">
            <div>
              <div class="provider-kicker">{{ currentProvider.provider_group }}</div>
              <h3>{{ currentProvider.display_name }}</h3>
              <p>{{ currentProviderPreview.message }}</p>
            </div>
            <div class="provider-hero-status">
              <span class="provider-status" :class="statusClass(currentProviderPreview.status)">
                {{ statusLabel(currentProviderPreview.status) }}
              </span>
              <strong>{{ currentProviderPreview.enabled ? '会出现在问数对话框' : '暂不会出现在问数对话框' }}</strong>
            </div>
          </div>

          <div class="provider-settings-grid">
            <div class="provider-panel">
              <div class="panel-title">供应商配置</div>
              <div class="panel-subtitle">预留凭证由用户填写，保存时仅在后端配置存储中更新。</div>

              <el-form label-position="top" class="provider-form">
                <el-form-item :label="credentialLabel(currentProvider.provider_id)">
                  <el-input
                    v-model="currentDraft.token"
                    type="password"
                    show-password
                    :placeholder="credentialPlaceholder(currentProvider.provider_id)"
                  />
                </el-form-item>
                <el-form-item label="Base URL">
                  <el-input
                    v-model="currentDraft.base_url"
                    :placeholder="baseUrlPlaceholder(currentProvider.provider_id)"
                  />
                </el-form-item>
                <div class="provider-inline-hints">
                  <span>{{ storedCredentialHint(currentProvider) }}</span>
                  <span v-if="currentProvider.provider_id === 'anthropic_compatible'">兼容网关必须填写 Base URL</span>
                </div>
              </el-form>
            </div>

            <div class="provider-panel">
              <div class="panel-title">支持模型列表</div>
              <div class="panel-subtitle">参考 Cherry Studio 的供应商分组方式，模型启用由用户自己控制。</div>

              <div class="custom-model-row">
                <el-input
                  v-model="customModelInput"
                  placeholder="追加自定义模型，例如 qwen/qwen3-coder"
                  @keyup.enter="addCustomModel"
                />
                <el-button @click="addCustomModel">追加</el-button>
              </div>

              <div v-if="currentDraft.custom_models.length" class="custom-model-list">
                <div
                  v-for="model in currentDraft.custom_models"
                  :key="model"
                  class="custom-model-item"
                >
                  <span>{{ model }}</span>
                  <button type="button" class="candidate-remove" @click="removeCustomModel(model)">
                    删除
                  </button>
                </div>
              </div>

              <div class="model-chip-grid">
                <button
                  v-for="model in currentSupportedModels"
                  :key="model"
                  type="button"
                  class="model-chip"
                  :class="{ active: currentDraft.enabled_models.includes(model) }"
                  @click="toggleModel(model)"
                >
                  <span>{{ model }}</span>
                  <strong>{{ currentDraft.enabled_models.includes(model) ? '已开启' : '加入候选' }}</strong>
                </button>
              </div>
            </div>
          </div>

          <div class="provider-panel candidate-panel">
            <div class="panel-title">已验证候选</div>
            <div class="panel-subtitle">只有通过校验且已开启的模型，才会进入智能问数对话框。</div>

            <div v-if="currentProviderPreview.enabledModels.length && currentProviderPreview.enabled" class="candidate-list">
              <div
                v-for="model in currentProviderPreview.enabledModels"
                :key="model"
                class="candidate-item"
              >
                <span>{{ model }}</span>
                <button
                  v-if="currentDraft.custom_models.includes(model)"
                  type="button"
                  class="candidate-remove"
                  @click="removeCustomModel(model)"
                >
                  移除自定义
                </button>
              </div>
            </div>
            <div v-else class="candidate-empty">
              <div class="candidate-empty-title">当前供应商还没有可用候选</div>
              <div class="candidate-empty-text">{{ currentProviderPreview.message }}</div>
            </div>
          </div>
        </section>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="config-form"
      >
        <div class="form-section">
          <div class="section-title">问数默认入口</div>
          <div class="section-subtitle">对话框只展示这里可见的供应商与模型。</div>
          <el-row :gutter="16">
            <el-col :xs="24" :md="12">
              <el-form-item label="默认供应商">
                <el-select v-model="form.provider_id" placeholder="请先开启可用供应商" :disabled="!validatedProviders.length">
                  <el-option
                    v-for="provider in validatedProviders"
                    :key="provider.provider_id"
                    :label="provider.display_name"
                    :value="provider.provider_id"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="默认模型">
                <el-select v-model="form.model" placeholder="请先开启可用模型" :disabled="!validatedModels.length">
                  <el-option
                    v-for="model in validatedModels"
                    :key="model"
                    :label="model"
                    :value="model"
                  />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <div v-if="!validatedProviders.length" class="section-note">
            还没有通过校验的候选模型。请先为供应商填写 Token，并在“支持模型列表”中开启模型。
          </div>
        </div>

        <div class="form-section">
          <div class="section-title">数据源说明与 Skills</div>
          <div class="section-subtitle">MySQL / Doris 连接信息不在此页维护，统一从 opendataworks 平台数据源表读取。</div>
          <el-row :gutter="16">
            <el-col :xs="24" :md="12">
              <el-form-item label="Skills 输出目录" prop="skills_output_dir">
                <el-input v-model="form.skills_output_dir" placeholder="../.claude/skills/dataagent-nl2sql" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="DataAgent 库">
                <el-input :model-value="settingsMeta.session_mysql_database" disabled />
              </el-form-item>
            </el-col>
          </el-row>
          <div class="datasource-notes">
            <div class="datasource-note-title">执行与推理约束</div>
            <div class="datasource-note-item">业务表与数据库归属优先从 `opendataworks.data_table` 判断。</div>
            <div class="datasource-note-item">数据源主机、端口、类型、用户名从 `opendataworks.doris_cluster` 查询。</div>
            <div class="datasource-note-item">若需要库级只读账号，再查询 `opendataworks.doris_database_users`。</div>
          </div>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dataagentApi } from '@/api/dataagent'

const formRef = ref(null)
const loading = ref(false)
const saving = ref(false)
const providers = ref([])
const selectedProviderId = ref('')
const customModelInput = ref('')

const providerDrafts = reactive({})
const settingsMeta = reactive({
  skills_root_dir: '',
  session_mysql_database: '',
  updated_at: ''
})

const form = reactive({
  provider_id: '',
  model: '',
  skills_output_dir: ''
})

const rules = {
  skills_output_dir: [{ required: true, message: '请输入 Skills 输出目录', trigger: 'blur' }]
}

const uniqueStrings = (values = []) => {
  const result = []
  const seen = new Set()
  values.forEach((value) => {
    const text = String(value || '').trim()
    if (!text || seen.has(text)) return
    seen.add(text)
    result.push(text)
  })
  return result
}

const statusLabel = (status) => {
  if (status === 'verified') return '已验证'
  if (status === 'invalid') return '异常'
  return '待校验'
}

const statusClass = (status) => {
  if (status === 'verified') return 'is-verified'
  if (status === 'invalid') return 'is-invalid'
  return 'is-pending'
}

const credentialLabel = (providerId) => (providerId === 'anthropic' ? 'API Key' : 'Token')
const credentialPlaceholder = (providerId) => (providerId === 'anthropic' ? '留空保持现有 API Key' : '留空保持现有 Token')

const baseUrlPlaceholder = (providerId) => {
  if (providerId === 'anthropic') return 'https://api.anthropic.com'
  if (providerId === 'openrouter') return 'https://openrouter.ai/api'
  if (providerId === 'anyrouter') return 'https://a-ocnfniawgw.cn-shanghai.fcapp.run'
  return '请输入兼容网关地址'
}

const groupedProviders = computed(() => {
  const groups = new Map()
  providers.value.forEach((provider) => {
    const groupName = provider.provider_group || '其他'
    if (!groups.has(groupName)) {
      groups.set(groupName, [])
    }
    groups.get(groupName).push(provider)
  })
  return Array.from(groups.entries()).map(([group, items]) => ({
    group,
    items
  }))
})

const currentProvider = computed(() => {
  return providers.value.find((item) => item.provider_id === selectedProviderId.value) || providers.value[0] || null
})

const currentDraft = computed(() => {
  if (!currentProvider.value) return null
  return providerDrafts[currentProvider.value.provider_id] || null
})

const getDraft = (providerId) => {
  return providerDrafts[providerId] || {
    enabled_models: [],
    custom_models: [],
    base_supported_models: [],
    token: '',
    base_url: ''
  }
}

const supportedModelsFor = (providerId) => {
  const draft = getDraft(providerId)
  return uniqueStrings([
    ...(draft.base_supported_models || []),
    ...(draft.custom_models || []),
    ...(draft.enabled_models || [])
  ])
}

const currentSupportedModels = computed(() => {
  if (!currentProvider.value) return []
  return supportedModelsFor(currentProvider.value.provider_id)
})

const providerHasCredential = (provider, draft) => {
  const typed = String(draft?.token || '').trim()
  if (typed) return true
  if (provider.provider_id === 'anthropic') return Boolean(provider.api_key_set)
  return Boolean(provider.auth_token_set || provider.api_key_set)
}

const providerPreview = (provider) => {
  const draft = providerDrafts[provider.provider_id]
  if (!draft) {
    return {
      status: provider.validation_status || 'unverified',
      message: provider.validation_message || '待校验',
      enabled: Boolean(provider.enabled),
      enabledModels: uniqueStrings(provider.models || [])
    }
  }

  const enabledModels = uniqueStrings(draft.enabled_models)
  if (provider.provider_id === 'anthropic_compatible' && !String(draft.base_url || '').trim()) {
    return {
      status: 'unverified',
      message: '请填写兼容网关地址',
      enabled: false,
      enabledModels: []
    }
  }
  if (!providerHasCredential(provider, draft)) {
    return {
      status: 'unverified',
      message: provider.provider_id === 'anthropic' ? '请填写 API Key' : '请填写 Token',
      enabled: false,
      enabledModels: []
    }
  }
  if (!enabledModels.length) {
    return {
      status: 'unverified',
      message: '请至少开启一个模型',
      enabled: false,
      enabledModels: []
    }
  }
  return {
    status: 'verified',
    message: '已完成本地配置校验，保存后会进入智能问数候选',
    enabled: true,
    enabledModels
  }
}

const currentProviderPreview = computed(() => {
  if (!currentProvider.value) {
    return {
      status: 'unverified',
      message: '请选择供应商',
      enabled: false,
      enabledModels: []
    }
  }
  return providerPreview(currentProvider.value)
})

const validatedProviders = computed(() => {
  return providers.value
    .map((provider) => ({
      ...provider,
      models: providerPreview(provider).enabledModels,
      enabled: providerPreview(provider).enabled
    }))
    .filter((provider) => provider.enabled && provider.models.length)
})

const validatedModels = computed(() => {
  const provider = validatedProviders.value.find((item) => item.provider_id === form.provider_id)
  return provider ? provider.models : []
})

const storedCredentialHint = (provider) => {
  if (provider.provider_id === 'anthropic') {
    return provider.api_key_set ? '后端已保存 API Key，可留空不改' : '当前未保存 API Key'
  }
  return provider.auth_token_set || provider.api_key_set ? '后端已保存 Token，可留空不改' : '当前未保存 Token'
}

const credentialSummary = (provider) => {
  const draft = providerDrafts[provider.provider_id]
  if (String(draft?.token || '').trim()) return '本次有新凭证'
  return providerHasCredential(provider, draft) ? '凭证已保存' : '未配置凭证'
}

const resetProviderDrafts = (items) => {
  Object.keys(providerDrafts).forEach((key) => {
    delete providerDrafts[key]
  })
  items.forEach((provider) => {
    providerDrafts[provider.provider_id] = {
      provider_id: provider.provider_id,
      token: '',
      base_url: provider.base_url || '',
      enabled_models: [...(provider.models || [])],
      custom_models: [...(provider.custom_models || [])],
      base_supported_models: (provider.supported_models || []).filter((model) => !(provider.custom_models || []).includes(model))
    }
  })
}

const applySettings = (payload) => {
  providers.value = Array.isArray(payload?.providers) ? payload.providers : []
  resetProviderDrafts(providers.value)

  form.provider_id = payload?.provider_id || validatedProviders.value[0]?.provider_id || providers.value[0]?.provider_id || ''
  form.model = payload?.model || ''
  form.skills_output_dir = payload?.skills_output_dir || ''

  settingsMeta.skills_root_dir = payload?.skills_root_dir || ''
  settingsMeta.session_mysql_database = payload?.session_mysql_database || ''
  settingsMeta.updated_at = payload?.updated_at || ''

  selectedProviderId.value = providers.value.find((item) => item.provider_id === payload?.provider_id)?.provider_id
    || providers.value[0]?.provider_id
    || ''
  customModelInput.value = ''
}

const loadSettings = async () => {
  loading.value = true
  try {
    const payload = await dataagentApi.getSettings()
    applySettings(payload)
  } finally {
    loading.value = false
  }
}

const toggleModel = (model) => {
  if (!currentDraft.value) return
  const list = new Set(currentDraft.value.enabled_models)
  if (list.has(model)) {
    list.delete(model)
  } else {
    list.add(model)
  }
  currentDraft.value.enabled_models = Array.from(list)
}

const addCustomModel = () => {
  if (!currentDraft.value) return
  const model = String(customModelInput.value || '').trim()
  if (!model) return
  currentDraft.value.custom_models = uniqueStrings([...(currentDraft.value.custom_models || []), model])
  currentDraft.value.enabled_models = uniqueStrings([...(currentDraft.value.enabled_models || []), model])
  customModelInput.value = ''
}

const removeCustomModel = (model) => {
  if (!currentDraft.value) return
  currentDraft.value.custom_models = currentDraft.value.custom_models.filter((item) => item !== model)
  currentDraft.value.enabled_models = currentDraft.value.enabled_models.filter((item) => item !== model)
}

const buildProviderPayload = (provider) => {
  const draft = providerDrafts[provider.provider_id]
  const payload = {
    provider_id: provider.provider_id,
    base_url: draft.base_url,
    enabled_models: uniqueStrings(draft.enabled_models),
    custom_models: uniqueStrings(draft.custom_models)
  }
  const token = String(draft.token || '').trim()
  if (token) {
    if (provider.provider_id === 'anthropic') {
      payload.api_key = token
    } else {
      payload.auth_token = token
    }
  }
  return payload
}

const saveSettings = async () => {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const payload = await dataagentApi.updateSettings({
      provider_id: form.provider_id || selectedProviderId.value || providers.value[0]?.provider_id || 'openrouter',
      model: form.model || '',
      skills_output_dir: form.skills_output_dir,
      providers: providers.value.map(buildProviderPayload)
    })
    applySettings(payload)
    ElMessage.success('DataAgent 配置已保存')
  } finally {
    saving.value = false
  }
}

watch(validatedProviders, (list) => {
  if (!list.length) {
    form.model = ''
    return
  }
  if (!list.some((provider) => provider.provider_id === form.provider_id)) {
    form.provider_id = list[0].provider_id
  }
}, { deep: true, immediate: true })

watch(validatedModels, (models) => {
  if (!models.length) {
    form.model = ''
    return
  }
  if (!models.includes(form.model)) {
    form.model = models[0]
  }
}, { immediate: true })

onMounted(() => {
  loadSettings()
})
</script>

<style scoped>
.dataagent-config {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary-row {
  margin: 0 !important;
}

.summary-card {
  height: 100%;
  border-radius: 18px;
  background:
    radial-gradient(circle at top right, rgba(249, 115, 22, 0.14), transparent 36%),
    linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
}

.summary-label {
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #94a3b8;
}

.summary-value {
  margin-top: 10px;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}

.summary-value.path {
  font-size: 13px;
  line-height: 1.6;
  word-break: break-all;
}

.summary-hint {
  margin-top: 10px;
  font-size: 12px;
  color: #64748b;
}

.config-card {
  border-radius: 22px;
  border: 1px solid #e2e8f0;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(248, 250, 252, 0.98) 100%),
    radial-gradient(circle at top, rgba(245, 158, 11, 0.12), transparent 36%);
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.card-title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.card-subtitle {
  margin-top: 6px;
  max-width: 680px;
  font-size: 13px;
  color: #64748b;
}

.actions {
  display: flex;
  gap: 8px;
}

.provider-workbench {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 18px;
  margin-bottom: 18px;
}

.provider-nav {
  padding: 18px;
  border-radius: 20px;
  border: 1px solid #e2e8f0;
  background:
    linear-gradient(180deg, #fff7ed 0%, #ffffff 65%),
    radial-gradient(circle at bottom, rgba(245, 158, 11, 0.18), transparent 38%);
}

.provider-group + .provider-group {
  margin-top: 16px;
}

.provider-group-title {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #9a3412;
}

.provider-card {
  width: 100%;
  margin-bottom: 10px;
  padding: 14px;
  border: 1px solid #fed7aa;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.8);
  text-align: left;
  transition: transform 160ms ease, border-color 160ms ease, box-shadow 160ms ease;
  cursor: pointer;
}

.provider-card:hover,
.provider-card.active {
  transform: translateY(-1px);
  border-color: #fb923c;
  box-shadow: 0 16px 32px rgba(249, 115, 22, 0.12);
}

.provider-card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.provider-card-name {
  font-size: 15px;
  font-weight: 700;
  color: #111827;
}

.provider-card-id {
  margin-top: 3px;
  font-size: 12px;
  color: #9ca3af;
}

.provider-card-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  font-size: 12px;
  color: #6b7280;
}

.provider-status {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.provider-status.is-verified {
  color: #166534;
  background: #dcfce7;
}

.provider-status.is-pending {
  color: #92400e;
  background: #fef3c7;
}

.provider-status.is-invalid {
  color: #991b1b;
  background: #fee2e2;
}

.provider-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.provider-hero {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 22px;
  border-radius: 22px;
  border: 1px solid #e2e8f0;
  background:
    radial-gradient(circle at top left, rgba(14, 165, 233, 0.14), transparent 30%),
    linear-gradient(135deg, #ffffff 0%, #eff6ff 100%);
}

.provider-kicker {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #0369a1;
}

.provider-hero h3 {
  margin: 8px 0 6px;
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
}

.provider-hero p {
  margin: 0;
  font-size: 14px;
  color: #475569;
}

.provider-hero-status {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  text-align: right;
  font-size: 13px;
  color: #334155;
}

.provider-settings-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.provider-panel,
.form-section {
  padding: 20px;
  border-radius: 20px;
  border: 1px solid #e2e8f0;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96) 0%, rgba(248, 250, 252, 0.96) 100%);
}

.panel-title,
.section-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}

.panel-subtitle,
.section-subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
}

.provider-form {
  margin-top: 14px;
}

.provider-inline-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #64748b;
}

.custom-model-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  margin-top: 14px;
}

.model-chip-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.custom-model-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}

.custom-model-item {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 999px;
  border: 1px solid #cbd5e1;
  background: #f8fafc;
  font-size: 12px;
  color: #334155;
}

.model-chip {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid #dbeafe;
  border-radius: 18px;
  background: #f8fbff;
  text-align: left;
  transition: border-color 160ms ease, transform 160ms ease, box-shadow 160ms ease;
  cursor: pointer;
}

.model-chip span {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  word-break: break-word;
}

.model-chip strong {
  font-size: 12px;
  color: #1d4ed8;
}

.model-chip:hover,
.model-chip.active {
  transform: translateY(-1px);
  border-color: #38bdf8;
  box-shadow: 0 14px 28px rgba(14, 165, 233, 0.14);
}

.model-chip.active {
  background: linear-gradient(180deg, #eff6ff 0%, #dbeafe 100%);
}

.candidate-panel {
  border-style: dashed;
}

.candidate-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.candidate-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 16px;
  background: #f8fafc;
  border: 1px solid #cbd5e1;
  font-size: 14px;
  color: #0f172a;
}

.candidate-remove {
  border: none;
  background: none;
  color: #dc2626;
  cursor: pointer;
}

.candidate-empty {
  margin-top: 18px;
  padding: 20px;
  border-radius: 16px;
  background: #fff7ed;
  color: #9a3412;
}

.candidate-empty-title {
  font-size: 14px;
  font-weight: 700;
}

.candidate-empty-text {
  margin-top: 6px;
  font-size: 13px;
}

.config-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-note {
  margin-top: 8px;
  font-size: 12px;
  color: #b45309;
}

.datasource-notes {
  margin-top: 6px;
  padding: 16px 18px;
  border-radius: 16px;
  border: 1px dashed #cbd5e1;
  background: #f8fafc;
}

.datasource-note-title {
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
}

.datasource-note-item {
  margin-top: 6px;
  font-size: 12px;
  color: #475569;
}

@media (max-width: 1100px) {
  .provider-workbench {
    grid-template-columns: 1fr;
  }

  .provider-settings-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .card-header,
  .provider-hero {
    flex-direction: column;
  }

  .provider-hero-status {
    align-items: flex-start;
    text-align: left;
  }

  .actions {
    width: 100%;
  }

  .custom-model-row {
    grid-template-columns: 1fr;
  }
}
</style>
