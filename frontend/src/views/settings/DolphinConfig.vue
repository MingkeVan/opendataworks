<template>
  <div class="dolphin-config">
    <!-- Header removed for tab integration -->

    <el-card class="config-card">
      <template #header>
        <div class="card-header">
          <span>基础配置</span>
          <div class="actions">
            <el-button type="primary" :loading="testing" @click="handleTestConnection">测试连接</el-button>
            <el-button type="success" :loading="saving" @click="handleSave">保存配置</el-button>
          </div>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" status-icon v-loading="loading">
        <el-form-item label="服务地址" prop="url">
          <el-input v-model="form.url" placeholder="http://localhost:12345/dolphinscheduler" />
          <div class="form-tip">DolphinScheduler API 服务地址</div>
        </el-form-item>

        <el-form-item label="访问令牌" prop="token">
          <el-input v-model="form.token" type="password" show-password placeholder="请输入访问令牌" />
          <div class="form-tip">用于 API 认证的 Token</div>
        </el-form-item>

        <el-form-item label="项目名称" prop="projectName">
          <el-input v-model="form.projectName" placeholder="默认为 opendataworks" />
        </el-form-item>
        
        <el-form-item label="项目编码" prop="projectCode">
          <el-input v-model="form.projectCode" disabled placeholder="自动获取" />
          <div class="form-tip">系统自动获取，无需手动填写</div>
        </el-form-item>

        <el-form-item label="租户编码" prop="tenantCode">
          <el-input v-model="form.tenantCode" placeholder="default" />
        </el-form-item>
          
        <el-form-item label="Worker 分组" prop="workerGroup">
          <el-input v-model="form.workerGroup" placeholder="default" />
        </el-form-item>
      </el-form>
      
      <el-alert
        title="配置说明"
        type="info"
        :closable="false"
        show-icon
        class="mt-4"
      >
        <p>配置修改后即时生效。请确保服务地址和令牌正确，否则可能导致任务调度失败。</p>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { settingsApi } from '@/api/settings'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const formRef = ref(null)

const form = reactive({
  id: null,
  url: '',
  token: '',
  projectName: 'opendataworks',
  projectCode: '',
  tenantCode: 'default',
  workerGroup: 'default',
  executionType: 'PARALLEL',
  isActive: true
})

const rules = {
  url: [{ required: true, message: '请输入服务地址', trigger: 'blur' }],
  token: [{ required: true, message: '请输入访问令牌', trigger: 'blur' }],
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }]
}

async function loadConfig() {
  loading.value = true
  try {
    const data = await settingsApi.getDolphinConfig()
    if (data) {
      Object.assign(form, data)
    }
  } catch (error) {
    console.error('Failed to load config', error)
  } finally {
    loading.value = false
  }
}

async function handleTestConnection() {
  if (!form.url || !form.token) {
    ElMessage.warning('请先填写服务地址和令牌')
    return
  }
  
  testing.value = true
  try {
    const success = await settingsApi.testDolphinConnection(form)
    if (success) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error('连接失败，请检查配置')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('连接测试出错')
  } finally {
    testing.value = false
  }
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate()
  
  saving.value = true
  try {
    const updated = await settingsApi.updateDolphinConfig(form)
    Object.assign(form, updated)
    ElMessage.success('保存成功')
  } catch (error) {
    console.error(error)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadConfig()
})
</script>

<style scoped>
.dolphin-config {
  /* padding removed */
  max-width: 1200px;
  margin: 0 auto;
}

.config-card {
  max-width: 800px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.actions {
  display: flex;
  gap: 10px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
}

.mt-4 {
  margin-top: 24px;
}
</style>
