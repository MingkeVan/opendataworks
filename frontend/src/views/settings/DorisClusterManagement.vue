<template>
  <div class="doris-cluster-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <h3>Doris 集群管理</h3>
            <span class="sub-title">配置 Doris FE 地址、认证信息，支持设置默认集群与连通性检测</span>
          </div>
          <div class="actions">
            <el-button type="primary" @click="openCreate">
              <el-icon><Plus /></el-icon>
              新增集群
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="clusters"
        border
        style="width: 100%"
      >
        <el-table-column prop="clusterName" label="集群名称" min-width="160" />
        <el-table-column label="连接信息" min-width="220">
          <template #default="{ row }">
            <div class="host-info">
              <el-tag size="small" type="info">{{ row.feHost }}</el-tag>
              <span class="divider">:</span>
              <span>{{ row.fePort }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" min-width="100" />
        <el-table-column label="状态" min-width="120">
          <template #default="{ row }">
            <el-tag type="success" v-if="row.status === 'active'">启用</el-tag>
            <el-tag type="info" v-else>停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认集群" min-width="100">
          <template #default="{ row }">
            <el-tag type="warning" v-if="row.isDefault === 1">默认</el-tag>
            <el-tag type="info" v-else>否</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="最近更新" min-width="180">
          <template #default="{ row }">
            <span>{{ formatDateTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="260">
          <template #default="{ row }">
            <el-button type="primary" text size="small" @click="openEdit(row.id)">
              编辑
            </el-button>
            <el-button
              type="success"
              text
              size="small"
              @click="handleTestConnection(row.id)"
            >
              测试连接
            </el-button>
            <el-button
              type="warning"
              text
              size="small"
              :disabled="row.isDefault === 1"
              @click="handleSetDefault(row.id)"
            >
              设为默认
            </el-button>
            <el-button
              type="danger"
              text
              size="small"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="120px"
      >
        <el-form-item label="集群名称" prop="clusterName">
          <el-input v-model="form.clusterName" placeholder="例如：本地开发集群" />
        </el-form-item>
        <el-form-item label="FE 地址" prop="feHost">
          <el-input v-model="form.feHost" placeholder="例如：localhost" />
        </el-form-item>
        <el-form-item label="FE 端口" prop="fePort">
          <el-input-number v-model="form.fePort" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="Doris 登录用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            placeholder="请输入密码（编辑时留空表示不修改）"
            type="password"
            show-password
          />
        </el-form-item>
        <el-form-item label="默认集群" prop="isDefault">
          <el-switch
            v-model="form.isDefault"
            :active-value="1"
            :inactive-value="0"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status">
            <el-option label="启用" value="active" />
            <el-option label="停用" value="inactive" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取 消</el-button>
          <el-button type="primary" :loading="saving" @click="handleSubmit">
            保 存
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { dorisClusterApi } from '@/api/doris'

const loading = ref(false)
const saving = ref(false)
const clusters = ref([])

const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref(null)
const formRef = ref(null)
const form = reactive({
  clusterName: '',
  feHost: '',
  fePort: 9030,
  username: '',
  password: '',
  isDefault: 0,
  status: 'active'
})

const rules = {
  clusterName: [{ required: true, message: '请输入集群名称', trigger: 'blur' }],
  feHost: [{ required: true, message: '请输入 FE 地址', trigger: 'blur' }],
  fePort: [
    {
      required: true,
      message: '请输入 FE 端口',
      trigger: 'change'
    }
  ],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    {
      validator: (_, value, callback) => {
        if (!isEdit.value && !value) {
          callback(new Error('请输入密码'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const dialogTitle = computed(() => (isEdit.value ? '编辑 Doris 集群' : '新增 Doris 集群'))

const formatDateTime = value => {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss')
}

async function loadClusters() {
  loading.value = true
  try {
    clusters.value = await dorisClusterApi.list()
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.clusterName = ''
  form.feHost = ''
  form.fePort = 9030
  form.username = ''
  form.password = ''
  form.isDefault = 0
  form.status = 'active'
}

function openCreate() {
  resetForm()
  isEdit.value = false
  currentId.value = null
  dialogVisible.value = true
}

async function openEdit(id) {
  isEdit.value = true
  currentId.value = id
  try {
    const data = await dorisClusterApi.getById(id)
    resetForm()
    form.clusterName = data.clusterName
    form.feHost = data.feHost
    form.fePort = data.fePort
    form.username = data.username
    form.isDefault = data.isDefault
    form.status = data.status || 'active'
    form.password = ''
    dialogVisible.value = true
  } catch (error) {
    console.error(error)
  }
}

async function handleSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (error) {
    return
  }

  saving.value = true
  const payload = {
    clusterName: form.clusterName,
    feHost: form.feHost,
    fePort: form.fePort,
    username: form.username,
    isDefault: form.isDefault,
    status: form.status
  }
  if (form.password) {
    payload.password = form.password
  }

  try {
    if (isEdit.value && currentId.value) {
      await dorisClusterApi.update(currentId.value, payload)
      ElMessage.success('更新成功')
    } else {
      payload.password = form.password
      await dorisClusterApi.create(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await loadClusters()
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除集群「${row.clusterName}」吗？该操作不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  await dorisClusterApi.remove(row.id)
  ElMessage.success('删除成功')
  loadClusters()
}

async function handleSetDefault(id) {
  await dorisClusterApi.setDefault(id)
  ElMessage.success('已设为默认集群')
  loadClusters()
}

async function handleTestConnection(id) {
  try {
    await dorisClusterApi.testConnection(id)
    ElMessage.success('连接成功')
  } catch (error) {
    console.error(error)
  }
}

loadClusters()
</script>

<style scoped>
.doris-cluster-page {
  padding: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header h3 {
  margin: 0;
  font-size: 18px;
  color: #1f2933;
}

.sub-title {
  display: block;
  margin-top: 4px;
  color: #6b7280;
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 8px;
}

.host-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.divider {
  color: #94a3b8;
}

.dialog-footer {
  text-align: right;
}
</style>
