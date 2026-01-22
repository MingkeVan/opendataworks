<template>
  <div class="table-list">
    <el-card>
      <div class="toolbar">
        <div class="filters">
          <el-select v-model="filters.layer" placeholder="选择层级" clearable style="width: 150px; margin-right: 10px">
            <el-option label="ODS" value="ODS" />
            <el-option label="DWD" value="DWD" />
            <el-option label="DIM" value="DIM" />
            <el-option label="DWS" value="DWS" />
            <el-option label="ADS" value="ADS" />
          </el-select>
          <el-input
            v-model="filters.keyword"
            placeholder="搜索表名或描述"
            clearable
            style="width: 250px; margin-right: 10px"
            @keyup.enter="loadData"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button type="primary" @click="loadData">查询</el-button>
        </div>
        <el-button type="primary" @click="goCreate">
          <el-icon><Plus /></el-icon>
          新建表
        </el-button>
      </div>

      <el-table :data="tableData" style="width: 100%; margin-top: 20px" v-loading="loading">
        <el-table-column prop="tableName" label="表名" width="220">
          <template #default="{ row }">
            <router-link :to="`/tables/${row.id}`" class="link">
              {{ row.tableName }}
            </router-link>
          </template>
        </el-table-column>
        <el-table-column prop="tableComment" label="描述" />
        <el-table-column prop="layer" label="层级" width="100">
          <template #default="{ row }">
            <el-tag :type="getLayerType(row.layer)">{{ row.layer }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="businessDomain" label="业务域" width="120">
          <template #default="{ row }">
            <el-tag type="info">{{ row.businessDomain || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dataDomain" label="数据域" width="120">
          <template #default="{ row }">
            <el-tag type="warning">{{ row.dataDomain || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dbName" label="数据库" width="150" />
        <el-table-column prop="owner" label="负责人" width="120" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'">
              {{ row.status === 'active' ? '活跃' : '已废弃' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row.id)">详情</el-button>
            <el-popconfirm title="确定删除吗?" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.pageNum"
        v-model:page-size="pagination.pageSize"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadData"
        @current-change="loadData"
        style="margin-top: 20px; justify-content: flex-end"
      />
    </el-card>

  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'

const router = useRouter()

const loading = ref(false)
const tableData = ref([])
const pagination = reactive({
  pageNum: 1,
  pageSize: 20,
  total: 0
})

const filters = reactive({
  layer: '',
  keyword: ''
})

const loadData = async () => {
  loading.value = true
  try {
    const res = await tableApi.list({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      ...filters
    })
    tableData.value = res.records
    pagination.total = res.total
  } catch (error) {
    console.error('加载数据失败:', error)
  } finally {
    loading.value = false
  }
}

const goCreate = () => {
  router.push({ path: '/datastudio-new', query: { create: '1' } })
}

const goDetail = (id) => {
  router.push(`/tables/${id}`)
}

const handleDelete = async (id) => {
  try {
    await tableApi.delete(id)
    ElMessage.success('删除成功')
    loadData()
  } catch (error) {
    console.error('删除失败:', error)
  }
}

const getLayerType = (layer) => {
  const types = {
    ODS: '',
    DWD: 'success',
    DIM: 'warning',
    DWS: 'info',
    ADS: 'danger'
  }
  return types[layer] || ''
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.table-list {
  height: 100%;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filters {
  display: flex;
  align-items: center;
}

.link {
  color: #409eff;
  text-decoration: none;
}

.link:hover {
  text-decoration: underline;
}
</style>
