<template>
  <div class="metadata-sync">
    <div class="panel-header">
      <div class="title">元数据比对与同步</div>
      <div class="actions">
        <el-select
          v-model="clusterId"
          size="small"
          placeholder="选择集群"
          clearable
          class="cluster-select"
        >
          <el-option
            v-for="item in clusterOptions"
            :key="item.id"
            :label="item.clusterName"
            :value="item.id"
          />
        </el-select>
        <el-button type="warning" size="small" @click="auditMetadata" :loading="auditLoading">
          <el-icon><Refresh /></el-icon>
          比对元数据
        </el-button>
      </div>
    </div>

    <div v-if="auditResult" class="audit-info">
      <el-icon :color="auditResult.hasDifferences ? '#E6A23C' : '#67C23A'">
        <Warning v-if="auditResult.hasDifferences" />
        <CircleCheck v-else />
      </el-icon>
      <span class="audit-text">
        最近比对: {{ auditResult.time }}
        <template v-if="auditResult.statisticsSynced > 0">
          | 已自动同步 {{ auditResult.statisticsSynced }} 张表的统计信息
        </template>
        <template v-if="auditResult.hasDifferences">
          | 发现 {{ auditResult.totalDifferences }} 处结构差异
          <el-button type="primary" size="small" @click="showDifferenceDialog" style="margin-left: 6px">
            查看详情
          </el-button>
          <el-button type="success" size="small" @click="confirmSync" :loading="syncLoading" style="margin-left: 4px">
            确认同步
          </el-button>
        </template>
        <template v-else>
          | 结构一致，无需同步
        </template>
      </span>
    </div>

    <el-empty v-else description="请点击“比对元数据”获取差异" :image-size="100" />

    <el-dialog
      v-model="differenceDialogVisible"
      title="元数据差异详情"
      width="70%"
      :close-on-click-modal="false"
    >
      <div v-if="auditResult && auditResult.differences" class="difference-container">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 16px"
        >
          <template #title>
            共发现 {{ auditResult.totalDifferences }} 处差异，请仔细检查后决定是否同步
          </template>
        </el-alert>

        <el-collapse>
          <el-collapse-item
            v-for="(diff, index) in auditResult.differences"
            :key="index"
            :name="index"
          >
            <template #title>
              <div class="diff-title">
                <el-tag
                  :type="diff.type === 'NEW' ? 'success' : diff.type === 'REMOVED' ? 'danger' : 'warning'"
                  size="small"
                >
                  {{ diff.type === 'NEW' ? '新表' : diff.type === 'REMOVED' ? '已删除' : '已更新' }}
                </el-tag>
                <span class="table-name-diff">{{ diff.database }}.{{ diff.tableName }}</span>
              </div>
            </template>

            <div v-if="diff.changes && diff.changes.length > 0" class="changes-section">
              <h4>表信息变更:</h4>
              <ul>
                <li v-for="(change, idx) in diff.changes" :key="idx">{{ change }}</li>
              </ul>
            </div>

            <div v-if="diff.fieldDifferences && diff.fieldDifferences.length > 0" class="field-diff-section">
              <h4>字段变更:</h4>
              <el-table :data="diff.fieldDifferences" border size="small" style="margin-top: 8px">
                <el-table-column prop="fieldName" label="字段名" width="200" />
                <el-table-column prop="type" label="变更类型" width="120">
                  <template #default="{ row }">
                    <el-tag
                      :type="row.type === 'NEW' ? 'success' : row.type === 'REMOVED' ? 'danger' : 'warning'"
                      size="small"
                    >
                      {{ row.type === 'NEW' ? '新增' : row.type === 'REMOVED' ? '删除' : '更新' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="变更详情">
                  <template #default="{ row }">
                    <div v-if="row.changes">
                      <div v-for="(value, key) in row.changes" :key="key" class="field-change-item">
                        <strong>{{ key }}:</strong> {{ value }}
                      </div>
                    </div>
                    <div v-else>-</div>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <template #footer>
        <el-button @click="differenceDialogVisible = false">关闭</el-button>
        <el-button type="success" @click="confirmSyncFromDialog" :loading="syncLoading">
          确认同步
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Warning, CircleCheck } from '@element-plus/icons-vue'
import { dorisClusterApi } from '@/api/doris'
import { tableApi } from '@/api/table'

const clusterOptions = ref([])
const clusterId = ref(null)
const auditLoading = ref(false)
const syncLoading = ref(false)
const auditResult = ref(null)
const differenceDialogVisible = ref(false)

const loadClusters = async () => {
  try {
    const clusters = await dorisClusterApi.list()
    clusterOptions.value = clusters
    if (!clusterId.value && clusters.length) {
      const defaultCluster = clusters.find((item) => item.isDefault === 1) || clusters[0]
      clusterId.value = defaultCluster?.id || null
    }
  } catch (error) {
    console.error('加载集群失败', error)
  }
}

const auditMetadata = async () => {
  auditLoading.value = true
  try {
    const response = await tableApi.auditMetadata(clusterId.value || null)
    auditResult.value = {
      hasDifferences: response.hasDifferences,
      totalDifferences: response.totalDifferences,
      differences: response.differences,
      errors: response.errors,
      statisticsSynced: response.statisticsSynced || 0,
      time: new Date().toLocaleString()
    }

    let message = ''
    if (response.statisticsSynced > 0) {
      message += `已自动同步 ${response.statisticsSynced} 张表的统计信息。`
    }
    if (response.hasDifferences) {
      message += `发现 ${response.totalDifferences} 处结构差异，请查看详情并确认是否同步。`
      ElMessage.warning({ message, duration: 5000 })
    } else {
      message += '结构一致，无需同步。'
      ElMessage.success({ message, duration: 3000 })
    }
  } catch (error) {
    console.error('比对元数据失败:', error)
    ElMessage.error('比对元数据失败: ' + (error.message || '未知错误'))
  } finally {
    auditLoading.value = false
  }
}

const showDifferenceDialog = () => {
  differenceDialogVisible.value = true
}

const confirmSync = async () => {
  if (!auditResult.value?.totalDifferences) return
  try {
    await ElMessageBox.confirm(
      `确认要同步 ${auditResult.value.totalDifferences} 处差异吗？`,
      '确认同步',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }
    )
    await performSync()
  } catch {
    // cancel
  }
}

const confirmSyncFromDialog = async () => {
  differenceDialogVisible.value = false
  await performSync()
}

const performSync = async () => {
  syncLoading.value = true
  try {
    const response = await tableApi.syncMetadata(clusterId.value || null)
    if (response.success) {
      ElMessage.success('元数据同步成功！')
    } else {
      ElMessage.warning('元数据同步完成，但存在部分错误')
    }
    auditResult.value = null
  } catch (error) {
    console.error('同步元数据失败:', error)
    ElMessage.error('同步元数据失败: ' + (error.message || '未知错误'))
  } finally {
    syncLoading.value = false
  }
}

onMounted(() => {
  loadClusters()
})
</script>

<style scoped>
.metadata-sync {
  padding: 12px 4px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2f3d;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cluster-select {
  width: 200px;
}

.audit-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background-color: #fef0e6;
  border-radius: 6px;
  font-size: 13px;
  color: #555;
}

.audit-info .audit-text {
  flex: 1;
}

.difference-container {
  max-height: 600px;
  overflow-y: auto;
}

.diff-title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 600;
}

.table-name-diff {
  color: #333;
}

.changes-section {
  padding: 12px;
  background-color: #f8fafc;
  border-radius: 6px;
  margin-bottom: 12px;
}

.changes-section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #666;
}

.changes-section ul {
  margin: 0;
  padding-left: 20px;
}

.changes-section li {
  margin: 4px 0;
  color: #555;
  font-size: 13px;
}

.field-diff-section {
  padding: 12px;
  margin-top: 12px;
}

.field-diff-section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #666;
}

.field-change-item {
  padding: 2px 0;
  font-size: 13px;
}

.field-change-item strong {
  color: #666;
  font-weight: 600;
}
</style>
