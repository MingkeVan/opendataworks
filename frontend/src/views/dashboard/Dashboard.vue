<template>
  <div class="dashboard">
    <el-card class="header-card">
      <template #header>
        <div class="card-header">
          <span>数据平台控制台</span>
          <div class="header-actions">
            <el-button type="primary" :icon="Refresh" @click="refreshData">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- 基础统计卡片 -->
      <div class="section-title">资源统计</div>
      <el-row :gutter="20" class="stats-row">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon tables">
              <el-icon><Grid /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalTables || 0 }}</div>
              <div class="stat-label">数据表</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon tasks">
              <el-icon><List /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalTasks || 0 }}</div>
              <div class="stat-label">数据任务</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon domains">
              <el-icon><Folder /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalDomains || 0 }}</div>
              <div class="stat-label">数据域</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon executions">
              <el-icon><Document /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.totalExecutions || 0 }}</div>
              <div class="stat-label">总执行次数</div>
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- 执行统计卡片 -->
      <div class="section-title" style="margin-top: 30px;">执行统计</div>
      <el-row :gutter="20" class="stats-row">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon success">
              <el-icon><CircleCheck /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.successExecutions || 0 }}</div>
              <div class="stat-label">成功次数</div>
              <div class="stat-rate success-rate">
                成功率 {{ statistics.executionSuccessRate || 0 }}%
              </div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon failed">
              <el-icon><CircleClose /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.failedExecutions || 0 }}</div>
              <div class="stat-label">失败次数</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon running">
              <el-icon><Loading /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.runningExecutions || 0 }}</div>
              <div class="stat-label">运行中</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon issues">
              <el-icon><WarningFilled /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.openIssues || 0 }}</div>
              <div class="stat-label">待解决问题</div>
              <div class="stat-rate critical-rate" v-if="statistics.criticalIssues > 0">
                严重 {{ statistics.criticalIssues }}
              </div>
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- 今日统计卡片 -->
      <div class="section-title" style="margin-top: 30px;">今日执行</div>
      <el-row :gutter="20" class="stats-row">
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon today-total">
              <el-icon><Calendar /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.todayExecutions || 0 }}</div>
              <div class="stat-label">今日执行次数</div>
            </div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon today-success">
              <el-icon><Select /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.todaySuccessExecutions || 0 }}</div>
              <div class="stat-label">今日成功</div>
            </div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-icon today-failed">
              <el-icon><CloseBold /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ statistics.todayFailedExecutions || 0 }}</div>
              <div class="stat-label">今日失败</div>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Refresh,
  Grid,
  List,
  Folder,
  Document,
  CircleCheck,
  CircleClose,
  Loading,
  WarningFilled,
  Calendar,
  Select,
  CloseBold
} from '@element-plus/icons-vue'
import { getDashboardStatistics } from '@/api/dashboard'

// 数据定义
const statistics = ref({})
const loading = ref(false)

// 页面加载
onMounted(() => {
  loadStatistics()
})

// 加载统计信息
const loadStatistics = async () => {
  loading.value = true
  try {
    const res = await getDashboardStatistics()
    statistics.value = res
  } catch (error) {
    console.error('Failed to load statistics:', error)
    ElMessage.error('加载统计数据失败: ' + error.message)
  } finally {
    loading.value = false
  }
}

// 刷新数据
const refreshData = () => {
  loadStatistics()
}
</script>

<style scoped>
.dashboard {
  padding: 6px;
}

.header-card {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 18px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 15px;
  padding-left: 10px;
  border-left: 4px solid #409eff;
}

.stats-row {
  margin-top: 10px;
}

.stat-card {
  display: flex;
  align-items: center;
  padding: 20px;
  background: #f8fafc;
  border-radius: 12px;
  transition: all 0.3s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.12);
}

.stat-icon {
  width: 60px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 28px;
  margin-right: 15px;
  flex-shrink: 0;
}

/* 资源统计图标颜色 */
.stat-icon.tables {
  background: #e8f4fd;
  color: #1890ff;
}

.stat-icon.tasks {
  background: #f0f5ff;
  color: #597ef7;
}

.stat-icon.domains {
  background: #fff7e6;
  color: #fa8c16;
}

.stat-icon.executions {
  background: #f9f0ff;
  color: #9254de;
}

/* 执行统计图标颜色 */
.stat-icon.success {
  background: #f6ffed;
  color: #52c41a;
}

.stat-icon.failed {
  background: #fff1f0;
  color: #f5222d;
}

.stat-icon.running {
  background: #e6f7ff;
  color: #1890ff;
}

.stat-icon.issues {
  background: #fff7e6;
  color: #fa8c16;
}

/* 今日统计图标颜色 */
.stat-icon.today-total {
  background: #f0f5ff;
  color: #597ef7;
}

.stat-icon.today-success {
  background: #f6ffed;
  color: #52c41a;
}

.stat-icon.today-failed {
  background: #fff1f0;
  color: #f5222d;
}

.stat-info {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  line-height: 1;
  margin-bottom: 5px;
  color: #303133;
}

.stat-label {
  font-size: 14px;
  color: #606266;
}

.stat-rate {
  font-size: 12px;
  margin-top: 5px;
}

.stat-rate.success-rate {
  color: #52c41a;
}

.stat-rate.critical-rate {
  color: #f5222d;
  font-weight: 500;
}
</style>
