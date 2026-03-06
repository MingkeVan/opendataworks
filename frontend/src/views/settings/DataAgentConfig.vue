<template>
  <div class="dataagent-config">
    <el-row :gutter="16" class="summary-row">
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="summary-card">
          <div class="summary-label">配置文件</div>
          <div class="summary-value path">{{ settingsMeta.settings_file_path || '-' }}</div>
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
            <div class="card-title">模型与连接配置</div>
            <div class="card-subtitle">统一保存到 `dataagent` 数据库和 `.claude/settings.json`。</div>
          </div>
          <div class="actions">
            <el-button @click="loadSettings" :loading="loading">刷新</el-button>
            <el-button type="primary" @click="saveSettings" :loading="saving">保存配置</el-button>
          </div>
        </div>
      </template>

      <el-form
        ref="formRef"
        v-loading="loading"
        :model="form"
        :rules="rules"
        label-width="128px"
        class="config-form"
      >
        <div class="form-section">
          <div class="section-title">LLM 提供商</div>
          <el-row :gutter="16">
            <el-col :xs="24" :md="8">
              <el-form-item label="Provider" prop="provider_id">
                <el-select v-model="form.provider_id" @change="handleProviderChange">
                  <el-option
                    v-for="provider in providers"
                    :key="provider.provider_id"
                    :label="provider.display_name"
                    :value="provider.provider_id"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="16">
              <el-form-item label="Model" prop="model">
                <el-select
                  v-model="form.model"
                  filterable
                  allow-create
                  default-first-option
                  placeholder="选择或直接输入模型名"
                >
                  <el-option
                    v-for="model in availableModels"
                    :key="model"
                    :label="model"
                    :value="model"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="API Key" prop="anthropic_api_key">
                <el-input v-model="form.anthropic_api_key" type="password" show-password />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="Auth Token" prop="anthropic_auth_token">
                <el-input v-model="form.anthropic_auth_token" type="password" show-password />
              </el-form-item>
            </el-col>
            <el-col :xs="24">
              <el-form-item label="Base URL" prop="anthropic_base_url">
                <el-input v-model="form.anthropic_base_url" placeholder="兼容网关地址，可留空" />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <div class="form-section">
          <div class="section-title">MySQL / DataAgent</div>
          <el-row :gutter="16">
            <el-col :xs="24" :md="8">
              <el-form-item label="MySQL Host" prop="mysql_host">
                <el-input v-model="form.mysql_host" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="4">
              <el-form-item label="Port" prop="mysql_port">
                <el-input-number v-model="form.mysql_port" :min="1" :max="65535" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="6">
              <el-form-item label="User" prop="mysql_user">
                <el-input v-model="form.mysql_user" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="6">
              <el-form-item label="Password" prop="mysql_password">
                <el-input v-model="form.mysql_password" type="password" show-password />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="元数据库" prop="mysql_database">
                <el-input v-model="form.mysql_database" placeholder="默认 opendataworks" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="DataAgent 库">
                <el-input :model-value="settingsMeta.session_mysql_database" disabled />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <div class="form-section">
          <div class="section-title">Doris 与 Skills</div>
          <el-row :gutter="16">
            <el-col :xs="24" :md="8">
              <el-form-item label="Doris Host" prop="doris_host">
                <el-input v-model="form.doris_host" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="4">
              <el-form-item label="Port" prop="doris_port">
                <el-input-number v-model="form.doris_port" :min="1" :max="65535" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="6">
              <el-form-item label="User" prop="doris_user">
                <el-input v-model="form.doris_user" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="6">
              <el-form-item label="Password" prop="doris_password">
                <el-input v-model="form.doris_password" type="password" show-password />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="Doris DB" prop="doris_database">
                <el-input v-model="form.doris_database" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="Skills 输出目录" prop="skills_output_dir">
                <el-input v-model="form.skills_output_dir" placeholder="../.claude/skills/dataagent-nl2sql" />
              </el-form-item>
            </el-col>
          </el-row>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dataagentApi } from '@/api/dataagent'

const formRef = ref(null)
const loading = ref(false)
const saving = ref(false)
const providers = ref([])
const settingsMeta = reactive({
  settings_file_path: '',
  skills_root_dir: '',
  session_mysql_database: '',
  updated_at: ''
})

const form = reactive({
  provider_id: 'openrouter',
  model: '',
  anthropic_api_key: '',
  anthropic_auth_token: '',
  anthropic_base_url: '',
  mysql_host: '',
  mysql_port: 3306,
  mysql_user: '',
  mysql_password: '',
  mysql_database: '',
  doris_host: '',
  doris_port: 9030,
  doris_user: '',
  doris_password: '',
  doris_database: '',
  skills_output_dir: ''
})

const rules = {
  provider_id: [{ required: true, message: '请选择 provider', trigger: 'change' }],
  model: [{ required: true, message: '请输入模型名', trigger: 'blur' }],
  mysql_host: [{ required: true, message: '请输入 MySQL Host', trigger: 'blur' }],
  mysql_database: [{ required: true, message: '请输入元数据库名', trigger: 'blur' }],
  skills_output_dir: [{ required: true, message: '请输入 Skills 输出目录', trigger: 'blur' }]
}

const currentProvider = computed(() => {
  return providers.value.find((item) => item.provider_id === form.provider_id) || null
})

const availableModels = computed(() => {
  const models = Array.isArray(currentProvider.value?.models) ? [...currentProvider.value.models] : []
  if (currentProvider.value?.default_model && !models.includes(currentProvider.value.default_model)) {
    models.unshift(currentProvider.value.default_model)
  }
  if (form.model && !models.includes(form.model)) {
    models.unshift(form.model)
  }
  return models
})

const applySettings = (payload) => {
  providers.value = Array.isArray(payload?.providers) ? payload.providers : []
  form.provider_id = payload?.provider_id || 'openrouter'
  form.model = payload?.model || ''
  form.anthropic_api_key = payload?.anthropic_api_key || ''
  form.anthropic_auth_token = payload?.anthropic_auth_token || ''
  form.anthropic_base_url = payload?.anthropic_base_url || ''
  form.mysql_host = payload?.mysql_host || ''
  form.mysql_port = payload?.mysql_port || 3306
  form.mysql_user = payload?.mysql_user || ''
  form.mysql_password = payload?.mysql_password || ''
  form.mysql_database = payload?.mysql_database || ''
  form.doris_host = payload?.doris_host || ''
  form.doris_port = payload?.doris_port || 9030
  form.doris_user = payload?.doris_user || ''
  form.doris_password = payload?.doris_password || ''
  form.doris_database = payload?.doris_database || ''
  form.skills_output_dir = payload?.skills_output_dir || ''
  settingsMeta.settings_file_path = payload?.settings_file_path || ''
  settingsMeta.skills_root_dir = payload?.skills_root_dir || ''
  settingsMeta.session_mysql_database = payload?.session_mysql_database || ''
  settingsMeta.updated_at = payload?.updated_at || ''
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

const handleProviderChange = () => {
  if (!currentProvider.value) return
  if (!form.model || !availableModels.value.includes(form.model)) {
    form.model = currentProvider.value.default_model || ''
  }
  if (!form.anthropic_base_url && currentProvider.value.base_url) {
    form.anthropic_base_url = currentProvider.value.base_url
  }
}

const saveSettings = async () => {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const payload = await dataagentApi.updateSettings({ ...form })
    applySettings(payload)
    ElMessage.success('DataAgent 配置已保存')
  } finally {
    saving.value = false
  }
}

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
}

.summary-label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}

.summary-value {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  word-break: break-all;
}

.summary-value.path {
  font-size: 13px;
  line-height: 1.5;
}

.config-card {
  border-radius: 14px;
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.card-subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
}

.actions {
  display: flex;
  gap: 8px;
}

.config-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-section {
  padding: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
}

.section-title {
  margin-bottom: 16px;
  font-size: 14px;
  font-weight: 600;
  color: #334155;
}

@media (max-width: 768px) {
  .card-header {
    flex-direction: column;
  }

  .actions {
    width: 100%;
  }
}
</style>
