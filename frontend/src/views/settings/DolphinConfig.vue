<template>
  <div class="dolphin-config">
    <SettingsSection title="Dolphin 环境管理">
      <template #actions>
        <el-button type="primary" @click="openCreate">新增环境</el-button>
      </template>

      <el-table v-loading="loading" :data="configs" border style="width: 100%">
        <el-table-column prop="configName" label="环境名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="url" label="服务地址" min-width="240" show-overflow-tooltip />
        <el-table-column prop="projectName" label="项目名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="projectCode" label="项目编码" min-width="120">
          <template #default="{ row }">
            <span>{{ row.projectCode || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="租户/Worker" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.tenantCode || 'default' }} / {{ row.workerGroup || 'default' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.isActive ? 'success' : 'info'">
              {{ row.isActive ? '启用' : '停用' }}
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
        <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" :loading="testingId === row.id" @click="testSaved(row)">测试</el-button>
            <el-button link type="warning" :disabled="row.isDefault === 1 || !row.isActive" @click="setDefault(row)">
              设为默认
            </el-button>
            <el-button link :type="row.isActive ? 'warning' : 'success'" @click="toggleActive(row)">
              {{ row.isActive ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="removeConfig(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SettingsSection>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑 Dolphin 环境' : '新增 Dolphin 环境'"
      width="660px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" status-icon>
        <el-form-item label="环境名称" prop="configName">
          <el-input v-model="form.configName" placeholder="例如：生产 Dolphin / 新 Dolphin" />
        </el-form-item>

        <el-form-item label="服务地址" prop="url">
          <el-input v-model="form.url" placeholder="http://localhost:12345/dolphinscheduler" />
        </el-form-item>

        <el-form-item label="访问令牌" prop="token">
          <el-input
            v-model="form.token"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改 Token' : '请输入访问令牌'"
          />
        </el-form-item>

        <el-form-item label="项目名称" prop="projectName">
          <el-input v-model="form.projectName" placeholder="默认为 opendataworks" />
        </el-form-item>

        <el-form-item label="项目编码" prop="projectCode">
          <el-input v-model="form.projectCode" disabled placeholder="测试连接或发布时自动获取" />
        </el-form-item>

        <el-form-item label="租户编码" prop="tenantCode">
          <el-input v-model="form.tenantCode" placeholder="default" />
        </el-form-item>

        <el-form-item label="Worker 分组" prop="workerGroup">
          <el-input v-model="form.workerGroup" placeholder="default" />
        </el-form-item>

        <el-form-item label="执行模式" prop="executionType">
          <el-select v-model="form.executionType" style="width: 100%">
            <el-option label="PARALLEL" value="PARALLEL" />
            <el-option label="SERIAL_WAIT" value="SERIAL_WAIT" />
            <el-option label="SERIAL_DISCARD" value="SERIAL_DISCARD" />
            <el-option label="SERIAL_PRIORITY" value="SERIAL_PRIORITY" />
          </el-select>
        </el-form-item>

        <el-form-item label="默认环境" prop="isDefault">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <el-form-item label="状态" prop="isActive">
          <el-switch v-model="form.isActive" active-text="启用" inactive-text="停用" />
        </el-form-item>

        <el-form-item label="说明" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="可选" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button :loading="testingForm" @click="testFormConnection">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { settingsApi } from '@/api/settings'
import SettingsSection from './components/SettingsSection.vue'

const loading = ref(false)
const saving = ref(false)
const testingId = ref(null)
const testingForm = ref(false)
const configs = ref([])

const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref(null)
const formRef = ref(null)

const form = reactive({
  configName: '',
  url: '',
  token: '',
  projectName: 'opendataworks',
  projectCode: '',
  tenantCode: 'default',
  workerGroup: 'default',
  executionType: 'PARALLEL',
  isActive: true,
  isDefault: 0,
  description: ''
})

const tokenRule = computed(() => ({
  validator: (_, value, callback) => {
    if (!isEdit.value && !value) {
      callback(new Error('请输入访问令牌'))
      return
    }
    callback()
  },
  trigger: 'blur'
}))

const rules = computed(() => ({
  configName: [{ required: true, message: '请输入环境名称', trigger: 'blur' }],
  url: [{ required: true, message: '请输入服务地址', trigger: 'blur' }],
  token: [tokenRule.value],
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }]
}))

const resetForm = () => {
  form.configName = ''
  form.url = ''
  form.token = ''
  form.projectName = 'opendataworks'
  form.projectCode = ''
  form.tenantCode = 'default'
  form.workerGroup = 'default'
  form.executionType = 'PARALLEL'
  form.isActive = true
  form.isDefault = 0
  form.description = ''
}

const loadConfigs = async () => {
  loading.value = true
  try {
    const list = await settingsApi.listDolphinConfigs()
    configs.value = Array.isArray(list) ? list : []
  } catch (error) {
    console.error('加载 Dolphin 配置失败:', error)
    ElMessage.error('加载 Dolphin 配置失败: ' + (error.message || '未知错误'))
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
  form.configName = row.configName || ''
  form.url = row.url || ''
  form.token = ''
  form.projectName = row.projectName || 'opendataworks'
  form.projectCode = row.projectCode || ''
  form.tenantCode = row.tenantCode || 'default'
  form.workerGroup = row.workerGroup || 'default'
  form.executionType = row.executionType || 'PARALLEL'
  form.isActive = row.isActive !== false
  form.isDefault = row.isDefault === 1 ? 1 : 0
  form.description = row.description || ''
  dialogVisible.value = true
}

const buildPayload = () => ({
  configName: form.configName,
  url: form.url,
  token: form.token || null,
  projectName: form.projectName,
  projectCode: form.projectCode || null,
  tenantCode: form.tenantCode || 'default',
  workerGroup: form.workerGroup || 'default',
  executionType: form.executionType || 'PARALLEL',
  isActive: form.isActive,
  isDefault: form.isDefault,
  description: form.description || null
})

const saveConfig = async () => {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const payload = buildPayload()
    if (isEdit.value && currentId.value) {
      await settingsApi.updateDolphinConfigById(currentId.value, payload)
      ElMessage.success('Dolphin 环境已更新')
    } else {
      payload.token = form.token
      await settingsApi.createDolphinConfig(payload)
      ElMessage.success('Dolphin 环境已创建')
    }
    dialogVisible.value = false
    await loadConfigs()
  } catch (error) {
    console.error('保存 Dolphin 配置失败:', error)
    ElMessage.error('保存 Dolphin 配置失败: ' + (error.message || '未知错误'))
  } finally {
    saving.value = false
  }
}

const testFormConnection = async () => {
  if (!form.url || (!isEdit.value && !form.token)) {
    ElMessage.warning('请先填写服务地址和访问令牌')
    return
  }
  testingForm.value = true
  try {
    const success = isEdit.value && currentId.value && !form.token
      ? await settingsApi.testSavedDolphinConnection(currentId.value)
      : await settingsApi.testDolphinConnection(buildPayload())
    if (success) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error('连接失败，请检查配置')
    }
  } catch (error) {
    console.error('测试 Dolphin 连接失败:', error)
    ElMessage.error('连接测试出错: ' + (error.message || '未知错误'))
  } finally {
    testingForm.value = false
  }
}

const testSaved = async row => {
  testingId.value = row.id
  try {
    const success = await settingsApi.testSavedDolphinConnection(row.id)
    if (success) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error('连接失败，请检查配置')
    }
  } catch (error) {
    console.error('测试 Dolphin 环境失败:', error)
    ElMessage.error('连接测试出错: ' + (error.message || '未知错误'))
  } finally {
    testingId.value = null
  }
}

const setDefault = async row => {
  try {
    await settingsApi.setDefaultDolphinConfig(row.id)
    ElMessage.success('已设为默认 Dolphin 环境')
    await loadConfigs()
  } catch (error) {
    console.error('设置默认 Dolphin 环境失败:', error)
    ElMessage.error('设置默认失败: ' + (error.message || '未知错误'))
  }
}

const toggleActive = async row => {
  const payload = {
    ...row,
    token: null,
    isActive: !row.isActive
  }
  try {
    await settingsApi.updateDolphinConfigById(row.id, payload)
    ElMessage.success(payload.isActive ? 'Dolphin 环境已启用' : 'Dolphin 环境已停用')
    await loadConfigs()
  } catch (error) {
    console.error('更新 Dolphin 环境状态失败:', error)
    ElMessage.error('状态更新失败: ' + (error.message || '未知错误'))
  }
}

const removeConfig = async row => {
  try {
    await ElMessageBox.confirm(`确认删除 Dolphin 环境「${row.configName || row.id}」吗？已绑定运行态的环境不能删除。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }

  try {
    await settingsApi.deleteDolphinConfig(row.id)
    ElMessage.success('已删除 Dolphin 环境')
    await loadConfigs()
  } catch (error) {
    console.error('删除 Dolphin 环境失败:', error)
    ElMessage.error('删除失败: ' + (error.message || '未知错误'))
  }
}

loadConfigs()
</script>

<style scoped>
.dolphin-config {
  max-width: 1200px;
  margin: 0 auto;
}

</style>
