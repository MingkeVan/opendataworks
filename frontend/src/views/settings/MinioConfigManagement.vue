<template>
  <div class="minio-config">
    <el-card class="config-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>MinIO 环境管理</span>
          <el-button type="primary" @click="openCreate">新增环境</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="configs" border style="width: 100%">
        <el-table-column prop="configName" label="环境名称" min-width="160" />
        <el-table-column prop="endpoint" label="Endpoint" min-width="220" show-overflow-tooltip />
        <el-table-column prop="region" label="Region" min-width="120" />
        <el-table-column label="Path Style" min-width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.usePathStyle === 1 ? 'success' : 'info'">
              {{ row.usePathStyle === 1 ? '开启' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">
              {{ row.status === 'active' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" min-width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.isDefault === 1 ? 'warning' : 'info'">
              {{ row.isDefault === 1 ? '默认' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" :disabled="row.isDefault === 1" @click="setDefault(row)">设为默认</el-button>
            <el-button link type="danger" @click="removeConfig(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑 MinIO 环境' : '新增 MinIO 环境'"
      width="620px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="环境名称" prop="configName">
          <el-input v-model="form.configName" placeholder="例如：生产 MinIO / 测试 MinIO" />
        </el-form-item>

        <el-form-item label="Endpoint" prop="endpoint">
          <el-input v-model="form.endpoint" placeholder="例如：http://minio:9000" />
        </el-form-item>

        <el-form-item label="Region" prop="region">
          <el-input v-model="form.region" placeholder="默认 us-east-1" />
        </el-form-item>

        <el-form-item label="AccessKey" prop="accessKey">
          <el-input v-model="form.accessKey" placeholder="请输入 AccessKey" />
        </el-form-item>

        <el-form-item label="SecretKey" prop="secretKey">
          <el-input
            v-model="form.secretKey"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改 SecretKey' : '请输入 SecretKey'"
          />
        </el-form-item>

        <el-form-item label="Path Style" prop="usePathStyle">
          <el-switch v-model="form.usePathStyle" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <el-form-item label="默认环境" prop="isDefault">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status">
            <el-option label="启用" value="active" />
            <el-option label="停用" value="inactive" />
          </el-select>
        </el-form-item>

        <el-form-item label="说明" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="可选" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { settingsApi } from '@/api/settings'

const loading = ref(false)
const saving = ref(false)
const configs = ref([])

const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref(null)
const formRef = ref(null)
const form = reactive({
  configName: '',
  endpoint: '',
  region: 'us-east-1',
  accessKey: '',
  secretKey: '',
  usePathStyle: 1,
  isDefault: 0,
  status: 'active',
  description: ''
})

const secretRule = computed(() => {
  return {
    validator: (_, value, callback) => {
      if (!isEdit.value && !value) {
        callback(new Error('请输入 SecretKey'))
        return
      }
      callback()
    },
    trigger: 'blur'
  }
})

const rules = {
  configName: [{ required: true, message: '请输入环境名称', trigger: 'blur' }],
  endpoint: [{ required: true, message: '请输入 Endpoint', trigger: 'blur' }],
  accessKey: [{ required: true, message: '请输入 AccessKey', trigger: 'blur' }],
  secretKey: [secretRule.value]
}

const resetForm = () => {
  form.configName = ''
  form.endpoint = ''
  form.region = 'us-east-1'
  form.accessKey = ''
  form.secretKey = ''
  form.usePathStyle = 1
  form.isDefault = 0
  form.status = 'active'
  form.description = ''
}

const loadConfigs = async () => {
  loading.value = true
  try {
    const list = await settingsApi.listMinioConfigs()
    configs.value = Array.isArray(list) ? list : []
  } catch (error) {
    console.error('加载 MinIO 配置失败:', error)
    ElMessage.error('加载 MinIO 配置失败: ' + (error.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  resetForm()
  isEdit.value = false
  currentId.value = null
  dialogVisible.value = true
}

const openEdit = row => {
  resetForm()
  isEdit.value = true
  currentId.value = row.id
  form.configName = row.configName
  form.endpoint = row.endpoint
  form.region = row.region || 'us-east-1'
  form.accessKey = row.accessKey || ''
  form.secretKey = ''
  form.usePathStyle = row.usePathStyle === 0 ? 0 : 1
  form.isDefault = row.isDefault === 1 ? 1 : 0
  form.status = row.status || 'active'
  form.description = row.description || ''
  dialogVisible.value = true
}

const saveConfig = async () => {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  const payload = {
    configName: form.configName,
    endpoint: form.endpoint,
    region: form.region,
    accessKey: form.accessKey,
    secretKey: form.secretKey || null,
    usePathStyle: form.usePathStyle,
    isDefault: form.isDefault,
    status: form.status,
    description: form.description || null
  }

  saving.value = true
  try {
    if (isEdit.value && currentId.value) {
      await settingsApi.updateMinioConfig(currentId.value, payload)
      ElMessage.success('MinIO 环境已更新')
    } else {
      payload.secretKey = form.secretKey
      await settingsApi.createMinioConfig(payload)
      ElMessage.success('MinIO 环境已创建')
    }
    dialogVisible.value = false
    await loadConfigs()
  } catch (error) {
    console.error('保存 MinIO 配置失败:', error)
    ElMessage.error('保存 MinIO 配置失败: ' + (error.message || '未知错误'))
  } finally {
    saving.value = false
  }
}

const setDefault = async row => {
  try {
    await settingsApi.setDefaultMinioConfig(row.id)
    ElMessage.success('已设为默认 MinIO 环境')
    await loadConfigs()
  } catch (error) {
    console.error('设置默认 MinIO 环境失败:', error)
    ElMessage.error('设置默认失败: ' + (error.message || '未知错误'))
  }
}

const removeConfig = async row => {
  try {
    await ElMessageBox.confirm(`确认删除 MinIO 环境「${row.configName}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }

  try {
    await settingsApi.deleteMinioConfig(row.id)
    ElMessage.success('已删除 MinIO 环境')
    await loadConfigs()
  } catch (error) {
    console.error('删除 MinIO 环境失败:', error)
    ElMessage.error('删除失败: ' + (error.message || '未知错误'))
  }
}

loadConfigs()
</script>

<style scoped>
.minio-config {
  max-width: 1200px;
  margin: 0 auto;
}

.config-card {
  width: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
