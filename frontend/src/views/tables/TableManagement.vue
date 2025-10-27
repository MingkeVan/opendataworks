<template>
  <div class="table-management">
    <div class="management-container">
      <!-- 左侧：数据库和表列表 -->
      <div class="left-panel">
        <el-card shadow="never" class="database-card">
          <!-- 搜索框和新建按钮 -->
          <div class="search-bar">
            <el-input
              v-model="searchKeyword"
              placeholder="搜索表名..."
              clearable
              class="search-input"
              @input="handleSearch"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button type="warning" @click="auditDorisMetadata" :loading="auditLoading">
              <el-icon><Refresh /></el-icon>
              比对元数据
            </el-button>
            <el-button type="primary" @click="goCreate">
              <el-icon><Plus /></el-icon>
              新建表
            </el-button>
          </div>

          <!-- 稽核/同步状态提示 -->
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
                <el-button type="primary" size="small" @click="showDifferenceDialog" style="margin-left: 8px">
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

          <!-- 排序选项 -->
          <div class="sort-options">
            <el-select
              v-model="sortField"
              size="small"
              placeholder="排序字段"
              style="width: 140px"
              @change="handleSortChange"
            >
              <el-option label="创建时间" value="createdAt" />
              <el-option label="更新时间" value="lastUpdated" />
              <el-option label="表名" value="tableName" />
              <el-option label="数据量" value="rowCount" />
              <el-option label="存储大小" value="storageSize" />
            </el-select>
            <el-select
              v-model="sortOrder"
              size="small"
              style="width: 100px"
              @change="handleSortChange"
            >
              <el-option label="降序" value="desc" />
              <el-option label="升序" value="asc" />
            </el-select>
          </div>

          <!-- 数据库列表 -->
          <div class="database-list" v-loading="loading">
            <el-collapse v-model="activeDatabase" accordion>
              <el-collapse-item
                v-for="db in databases"
                :key="db"
                :name="db"
                @click="handleDatabaseClick(db)"
              >
                <template #title>
                  <div class="database-title">
                    <el-icon><Folder /></el-icon>
                    <span class="db-name">{{ db }}</span>
                    <el-badge
                      :value="getTableCount(db)"
                      class="table-count-badge"
                      type="info"
                    />
                  </div>
                </template>

                <!-- 表列表 -->
                <div class="table-list">
                  <template v-if="getTablesForDatabase(db).length">
                    <div
                      v-for="item in getTablesForDatabase(db)"
                      :key="item.id"
                      class="table-item"
                      :class="{ active: selectedTable?.id === item.id }"
                      @click.stop="handleTableClick(item)"
                    >
                      <!-- 数据量进度条背景 -->
                      <div
                        class="table-progress-bg"
                        :style="{ width: getTableProgressWidth(db, item) }"
                      ></div>

                      <div class="table-content">
                        <el-icon class="table-icon"><Document /></el-icon>
                        <div class="table-info">
                          <span class="table-name" :title="item.tableName">
                            {{ item.tableName }}
                          </span>
                          <span v-if="item.tableComment" class="table-comment" :title="item.tableComment">
                            {{ item.tableComment }}
                          </span>
                        </div>
                        <div class="table-meta-tags">
                          <span class="row-count" :title="`数据量: ${formatNumber(getTableRowCount(item))} 行`">
                            {{ formatRowCount(getTableRowCount(item)) }}
                          </span>
                          <span v-if="getUpstreamCount(item.id) > 0" class="lineage-count upstream" :title="`上游表: ${getUpstreamCount(item.id)} 个`">
                            ↑{{ getUpstreamCount(item.id) }}
                          </span>
                          <span v-if="getDownstreamCount(item.id) > 0" class="lineage-count downstream" :title="`下游表: ${getDownstreamCount(item.id)} 个`">
                            ↓{{ getDownstreamCount(item.id) }}
                          </span>
                          <el-tag
                            v-if="item.layer"
                            size="small"
                            :type="getLayerType(item.layer)"
                            class="layer-tag"
                          >
                            {{ item.layer }}
                          </el-tag>
                        </div>
                      </div>
                    </div>
                  </template>
                  <el-empty
                    v-else
                    description="暂无表"
                    :image-size="60"
                  />
                </div>
              </el-collapse-item>
            </el-collapse>
            <el-empty
              v-if="databases.length === 0"
              description="暂无数据库"
              :image-size="80"
            />
          </div>
        </el-card>
      </div>

      <!-- 右侧：表详情 -->
      <div class="right-panel">
        <el-card shadow="never" class="detail-card" v-loading="detailLoading">
          <div v-if="!selectedTable" class="empty-state">
            <el-empty description="请从左侧选择一个表查看详情">
              <template #image>
                <el-icon :size="100" color="#909399"><FolderOpened /></el-icon>
              </template>
            </el-empty>
          </div>

          <template v-else>
            <!-- 标签页 -->
            <el-tabs v-model="activeTab" class="detail-tabs">
              <!-- 基本信息 -->
              <el-tab-pane label="基本信息" name="basic">
                <div class="basic-section">
                  <div class="section-header">
                    <h3>表信息</h3>
                    <template v-if="isEditing">
                      <div class="action-buttons">
                        <el-button @click="handleEditCancel" :disabled="editSubmitting">
                          取消
                        </el-button>
                        <el-button
                          type="primary"
                          :loading="editSubmitting"
                          @click="handleEditSubmit"
                        >
                          保存
                        </el-button>
                      </div>
                    </template>
                    <el-button v-else type="primary" @click="startEditing">
                      编辑表信息
                    </el-button>
                  </div>

                  <template v-if="isEditing">
                    <div class="inline-edit-wrapper">
                      <el-form
                        ref="editFormRef"
                        :model="editForm"
                        :rules="editFormRules"
                        label-width="110px"
                        class="edit-form"
                      >
                        <el-row :gutter="16">
                          <el-col :span="24">
                            <el-form-item label="表名" prop="tableName">
                              <div class="table-name-input-group">
                                <el-input v-model="editForm.tableName" placeholder="请输入表名" />
                                <el-button
                                  type="primary"
                                  link
                                  size="small"
                                  :disabled="!canGenerateEditName"
                                  @click="handleGenerateTableName"
                                >
                                  生成
                                </el-button>
                              </div>
                            </el-form-item>
                          </el-col>
                        </el-row>

                        <el-row :gutter="16">
                          <el-col :span="12">
                            <el-form-item label="数据分层" prop="layer">
                              <el-select v-model="editForm.layer" placeholder="选择数据分层">
                                <el-option
                                  v-for="item in layerOptions"
                                  :key="item.value"
                                  :label="item.label"
                                  :value="item.value"
                                />
                              </el-select>
                            </el-form-item>
                          </el-col>
                          <el-col :span="12">
                            <el-form-item label="业务域" prop="businessDomain">
                              <el-select
                                v-model="editForm.businessDomain"
                                placeholder="选择业务域"
                                filterable
                                @change="handleEditBusinessDomainChange"
                              >
                                <el-option
                                  v-for="item in businessDomainOptions"
                                  :key="item.domainCode"
                                  :label="`${item.domainCode} - ${item.domainName}`"
                                  :value="item.domainCode"
                                />
                              </el-select>
                            </el-form-item>
                          </el-col>
                        </el-row>

                        <el-row :gutter="16">
                          <el-col :span="12">
                            <el-form-item label="数据域" prop="dataDomain">
                              <el-select
                                v-model="editForm.dataDomain"
                                placeholder="选择数据域"
                                :disabled="!editForm.businessDomain"
                                filterable
                              >
                                <el-option
                                  v-for="item in dataDomainOptions"
                                  :key="item.domainCode"
                                  :label="`${item.domainCode} - ${item.domainName}`"
                                  :value="item.domainCode"
                                />
                              </el-select>
                            </el-form-item>
                          </el-col>
                          <el-col :span="12">
                            <el-form-item label="自定义标识" prop="customIdentifier">
                              <el-input
                                v-model="editForm.customIdentifier"
                                placeholder="如: cmp_performance"
                                @blur="() => handleNormalizeSegment('customIdentifier')"
                              />
                            </el-form-item>
                          </el-col>
                        </el-row>

                        <el-row :gutter="16">
                          <el-col :span="12">
                            <el-form-item label="统计周期">
                              <el-select v-model="editForm.statisticsCycle" placeholder="可选">
                                <el-option label="无" value="" />
                                <el-option
                                  v-for="item in statisticsOptions"
                                  :key="item.value"
                                  :label="item.label"
                                  :value="item.value"
                                />
                              </el-select>
                            </el-form-item>
                          </el-col>
                          <el-col :span="12">
                            <el-form-item label="更新类型" prop="updateType">
                              <el-select v-model="editForm.updateType" placeholder="选择更新类型">
                                <el-option
                                  v-for="item in updateTypeOptions"
                                  :key="item.value"
                                  :label="item.label"
                                  :value="item.value"
                                />
                              </el-select>
                            </el-form-item>
                          </el-col>
                        </el-row>

                        <el-row :gutter="16">
                          <el-col :span="12">
                            <el-form-item label="负责人" prop="owner">
                              <el-input v-model="editForm.owner" placeholder="负责人" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="12">
                            <el-form-item label="数据库">
                              <el-input :model-value="selectedTable.dbName" disabled />
                            </el-form-item>
                          </el-col>
                        </el-row>

                        <el-form-item label="表注释">
                          <el-input
                            v-model="editForm.tableComment"
                            type="textarea"
                            :rows="3"
                            placeholder="请输入表业务含义"
                          />
                        </el-form-item>
                      </el-form>
                    </div>
                  </template>
                  <template v-else>
                    <el-row :gutter="20" class="info-cards">
                      <el-col :span="12">
                        <el-descriptions title="表信息" :column="1" border>
                          <el-descriptions-item label="表名">
                            {{ selectedTable.tableName }}
                          </el-descriptions-item>
                          <el-descriptions-item label="表注释">
                            {{ selectedTable.tableComment || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="数据分层">
                            {{ selectedTable.layer || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="业务域">
                            {{ selectedTable.businessDomain || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="数据域">
                            {{ selectedTable.dataDomain || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="数据库">
                            {{ selectedTable.dbName || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="负责人">
                            {{ selectedTable.owner || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="创建时间">
                            {{ formatDateTime(selectedTable.createdAt) }}
                          </el-descriptions-item>
                        </el-descriptions>
                      </el-col>
                      <el-col :span="12">
                        <el-descriptions title="Doris 配置" :column="1" border>
                          <el-descriptions-item label="表模型">
                            {{ selectedTable.tableModel || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="主键列">
                            {{ selectedTable.keyColumns || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="分区字段">
                            {{ selectedTable.partitionColumn || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="分桶字段">
                            {{ selectedTable.distributionColumn || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="分桶数">
                            {{ selectedTable.bucketNum || '-' }}
                          </el-descriptions-item>
                          <el-descriptions-item label="副本数">
                            {{ selectedTable.replicaNum || '-' }}
                          </el-descriptions-item>
                        </el-descriptions>
                      </el-col>
                    </el-row>

                    <!-- 血缘关系 -->
                    <div class="lineage-section">
                      <h3>血缘关系</h3>
                      <el-row :gutter="20">
                        <el-col :span="12">
                          <el-card shadow="never" class="lineage-card">
                            <template #header>
                              <div class="lineage-header">
                                <span>上游表 ({{ lineage.upstreamTables?.length || 0 }})</span>
                                <el-button type="primary" link size="small" @click="goLineage">
                                  查看完整血缘
                                </el-button>
                              </div>
                            </template>
                            <div v-if="lineage.upstreamTables?.length" class="lineage-list">
                              <div
                                v-for="table in lineage.upstreamTables"
                                :key="table.id"
                                class="lineage-item"
                                @click="handleTableClick(table)"
                              >
                                <el-icon class="table-icon"><Document /></el-icon>
                                <div class="table-info-mini">
                                  <div class="table-name">{{ table.tableName }}</div>
                                  <div class="table-desc">{{ table.tableComment || '-' }}</div>
                                </div>
                                <el-tag v-if="table.layer" size="small" :type="getLayerType(table.layer)">
                                  {{ table.layer }}
                                </el-tag>
                              </div>
                            </div>
                            <el-empty v-else description="暂无上游表" :image-size="60" />
                          </el-card>
                        </el-col>
                        <el-col :span="12">
                          <el-card shadow="never" class="lineage-card">
                            <template #header>
                              <div class="lineage-header">
                                <span>下游表 ({{ lineage.downstreamTables?.length || 0 }})</span>
                                <el-button type="primary" link size="small" @click="goLineage">
                                  查看完整血缘
                                </el-button>
                              </div>
                            </template>
                            <div v-if="lineage.downstreamTables?.length" class="lineage-list">
                              <div
                                v-for="table in lineage.downstreamTables"
                                :key="table.id"
                                class="lineage-item"
                                @click="handleTableClick(table)"
                              >
                                <el-icon class="table-icon"><Document /></el-icon>
                                <div class="table-info-mini">
                                  <div class="table-name">{{ table.tableName }}</div>
                                  <div class="table-desc">{{ table.tableComment || '-' }}</div>
                                </div>
                                <el-tag v-if="table.layer" size="small" :type="getLayerType(table.layer)">
                                  {{ table.layer }}
                                </el-tag>
                              </div>
                            </div>
                            <el-empty v-else description="暂无下游表" :image-size="60" />
                          </el-card>
                        </el-col>
                      </el-row>
                    </div>
                  </template>
                </div>
              </el-tab-pane>

              <!-- 字段列表 -->
              <el-tab-pane label="字段列表" name="fields">
                <div class="fields-section">
                  <div class="section-header">
                    <h3>字段定义</h3>
                    <el-button type="primary" @click="handleAddField" :disabled="isAddingField">
                      <el-icon><Plus /></el-icon>
                      新增字段
                    </el-button>
                  </div>
                  <el-table :data="displayFields" border style="width: 100%">
                    <el-table-column label="字段名" width="180">
                      <template #default="{ row }">
                        <el-input
                          v-if="row._isEditing || row._isNew"
                          v-model="row.fieldName"
                          placeholder="字段名"
                          size="small"
                        />
                        <span v-else>{{ row.fieldName }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="类型" width="150">
                      <template #default="{ row }">
                        <el-input
                          v-if="row._isEditing || row._isNew"
                          v-model="row.fieldType"
                          placeholder="VARCHAR(255)"
                          size="small"
                        />
                        <span v-else>{{ row.fieldType }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="可为空" width="100">
                      <template #default="{ row }">
                        <el-switch
                          v-if="row._isEditing || row._isNew"
                          v-model="row.isNullable"
                          :active-value="1"
                          :inactive-value="0"
                          size="small"
                        />
                        <el-tag v-else :type="row.isNullable ? 'success' : 'danger'" size="small">
                          {{ row.isNullable ? '是' : '否' }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column label="主键" width="90">
                      <template #default="{ row }">
                        <el-switch
                          v-if="row._isEditing || row._isNew"
                          v-model="row.isPrimary"
                          :active-value="1"
                          :inactive-value="0"
                          size="small"
                        />
                        <template v-else>
                          <el-tag v-if="row.isPrimary" type="info" size="small">是</el-tag>
                          <span v-else>-</span>
                        </template>
                      </template>
                    </el-table-column>
                    <el-table-column label="默认值" width="120">
                      <template #default="{ row }">
                        <el-input
                          v-if="row._isEditing || row._isNew"
                          v-model="row.defaultValue"
                          placeholder="可选"
                          size="small"
                        />
                        <span v-else>{{ row.defaultValue || '-' }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="注释" min-width="150">
                      <template #default="{ row }">
                        <el-input
                          v-if="row._isEditing || row._isNew"
                          v-model="row.fieldComment"
                          placeholder="字段注释"
                          size="small"
                        />
                        <span v-else>{{ row.fieldComment || '-' }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="操作" width="150" fixed="right">
                      <template #default="{ row, $index }">
                        <template v-if="row._isEditing || row._isNew">
                          <el-button
                            type="primary"
                            link
                            size="small"
                            @click="handleSaveField(row, $index)"
                            :loading="fieldSubmitting"
                          >
                            保存
                          </el-button>
                          <el-button
                            link
                            size="small"
                            @click="handleCancelFieldEdit(row, $index)"
                            :disabled="fieldSubmitting"
                          >
                            取消
                          </el-button>
                        </template>
                        <template v-else>
                          <el-button type="primary" link size="small" @click="handleEditFieldInline(row)">
                            编辑
                          </el-button>
                          <el-popconfirm
                            width="250"
                            confirm-button-text="确定"
                            cancel-button-text="取消"
                            :title="`确定删除字段「${row.fieldName}」吗？`"
                            @confirm="handleDeleteField(row)"
                          >
                            <template #reference>
                              <el-button type="danger" link size="small">删除</el-button>
                            </template>
                          </el-popconfirm>
                        </template>
                      </template>
                    </el-table-column>
                  </el-table>
                  <el-empty v-if="!displayFields.length" description="暂无字段信息" />
                </div>
              </el-tab-pane>

              <!-- 统计信息 -->
              <el-tab-pane label="统计信息" name="statistics">
                <div class="statistics-section">
                  <div class="section-header">
                    <h3>数据统计</h3>
                    <el-button
                      type="primary"
                      size="small"
                      @click="refreshStatistics"
                      :loading="statisticsLoading"
                    >
                      刷新统计
                    </el-button>
                  </div>

                  <div v-if="statistics" class="statistics-content">
                    <el-row :gutter="20" class="stat-cards">
                      <el-col :span="8">
                        <div class="stat-card">
                          <div class="stat-label">数据行数</div>
                          <div class="stat-value">
                            {{ formatNumber(statistics.rowCount) }}
                          </div>
                        </div>
                      </el-col>
                      <el-col :span="8">
                        <div class="stat-card">
                          <div class="stat-label">数据大小</div>
                          <div class="stat-value">
                            {{ statistics.dataSizeReadable || '-' }}
                          </div>
                        </div>
                      </el-col>
                      <el-col :span="8">
                        <div class="stat-card">
                          <div class="stat-label">分区数量</div>
                          <div class="stat-value">
                            {{ statistics.partitionCount || '-' }}
                          </div>
                        </div>
                      </el-col>
                    </el-row>

                    <!-- 数据量趋势图 -->
                    <div class="trend-chart" ref="chartContainer">
                      <h4>数据量趋势（最近7天）</h4>
                      <div id="trendChart" style="height: 300px"></div>
                    </div>
                  </div>
                  <el-empty v-else description="暂无统计信息，点击刷新获取" />
                </div>
              </el-tab-pane>

              <!-- DDL -->
              <el-tab-pane label="DDL" name="ddl">
                <div class="ddl-section">
                  <div class="section-header">
                    <h3>Doris 建表语句</h3>
                    <div class="action-buttons">
                      <el-button
                        type="primary"
                        size="small"
                        @click="loadTableDdl"
                        :loading="ddlLoading"
                        v-if="!tableDdl"
                      >
                        加载DDL
                      </el-button>
                      <el-button
                        type="primary"
                        size="small"
                        @click="copyDdl"
                        :disabled="!tableDdl"
                        v-if="tableDdl"
                      >
                        复制
                      </el-button>
                    </div>
                  </div>
                  <el-input
                    v-model="tableDdl"
                    type="textarea"
                    :rows="20"
                    readonly
                    class="ddl-textarea"
                    placeholder="点击「加载DDL」按钮获取建表语句"
                  />
                </div>
              </el-tab-pane>

              <!-- 数据预览 -->
              <el-tab-pane label="数据预览" name="preview">
                <div class="preview-section">
                  <div class="section-header">
                    <h3>数据预览</h3>
                    <div class="preview-controls">
                      <el-input-number
                        v-model="previewLimit"
                        :min="10"
                        :max="1000"
                        :step="10"
                        size="small"
                        style="width: 120px"
                      />
                      <el-button
                        type="primary"
                        size="small"
                        @click="loadPreviewData"
                        :loading="previewLoading"
                      >
                        {{ previewData.length > 0 ? '刷新' : '加载数据' }}
                      </el-button>
                    </div>
                  </div>

                  <div v-if="previewData.length > 0" class="preview-table-wrapper">
                    <el-table
                      :data="previewData"
                      border
                      stripe
                      style="width: 100%"
                      max-height="600"
                    >
                      <el-table-column
                        v-for="column in previewColumns"
                        :key="column"
                        :prop="column"
                        :label="column"
                        min-width="120"
                        show-overflow-tooltip
                      >
                        <template #default="{ row }">
                          {{ formatCellValue(row[column]) }}
                        </template>
                      </el-table-column>
                    </el-table>
                    <div class="preview-footer">
                      <el-text type="info">
                        显示 {{ previewData.length }} 条记录
                        {{ previewData.length >= previewLimit ? '（已达到限制）' : '' }}
                      </el-text>
                    </div>
                  </div>
                  <el-empty
                    v-else
                    description="点击「加载数据」按钮预览表数据"
                    :image-size="80"
                  />
                </div>
              </el-tab-pane>

              <!-- 导出 -->
              <el-tab-pane label="导出" name="export">
                <div class="export-section">
                  <el-alert
                    type="info"
                    :closable="false"
                    show-icon
                    title="数据导出功能即将上线，敬请期待"
                  />
                </div>
              </el-tab-pane>
            </el-tabs>
          </template>
        </el-card>
      </div>
    </div>

    <!-- 差异详情对话框 -->
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

            <!-- 表级别的变更 -->
            <div v-if="diff.changes && diff.changes.length > 0" class="changes-section">
              <h4>表信息变更:</h4>
              <ul>
                <li v-for="(change, idx) in diff.changes" :key="idx">{{ change }}</li>
              </ul>
            </div>

            <!-- 字段级别的变更 -->
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
                <el-table-column label="变更内容">
                  <template #default="{ row }">
                    <div v-for="(value, key) in row.changes" :key="key" class="field-change-item">
                      <strong>{{ key }}:</strong>
                      {{ value.old || 'null' }} → {{ value.new || 'null' }}
                    </div>
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
import { ref, reactive, onMounted, computed, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search,
  Plus,
  Folder,
  Document,
  List,
  Link,
  Connection,
  FolderOpened,
  Refresh,
  CircleCheck,
  CircleClose,
  Warning
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import * as echarts from 'echarts'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { tableDesignerApi } from '@/api/tableDesigner'

const router = useRouter()

const loading = ref(false)
const detailLoading = ref(false)
const statisticsLoading = ref(false)
const ddlLoading = ref(false)
const previewLoading = ref(false)
const databases = ref([])
const tablesByDatabase = ref({})
const lineageCache = ref({})
const lineage = ref({ upstreamTables: [], downstreamTables: [] })
const activeDatabase = ref('')
const searchKeyword = ref('')
const selectedTable = ref(null)
const fields = ref([])
const isAddingField = ref(false)
const fieldSubmitting = ref(false)
const editingFieldBackup = ref(null)
const statistics = ref(null)
const statisticsHistory = ref([])
const tableDdl = ref('')
const previewData = ref([])
const previewLimit = ref(100)
const activeTab = ref('basic')
const chartContainer = ref(null)
let chartInstance = null

// 表编辑相关
const isEditing = ref(false)
const editFormRef = ref(null)
const editSubmitting = ref(false)

// 元数据稽核和同步相关
const auditLoading = ref(false)
const syncLoading = ref(false)
const auditResult = ref(null)
const differenceDialogVisible = ref(false)

// 字段管理计算属性
const displayFields = computed(() => fields.value)

// 数据预览列名
const previewColumns = computed(() => {
  if (previewData.value.length === 0) return []
  return Object.keys(previewData.value[0])
})

const TABLE_NAME_PATTERN = /^[a-z0-9_]+$/

const layerOptions = [
  { label: 'ODS - 原始数据层', value: 'ODS' },
  { label: 'DWD - 明细数据层', value: 'DWD' },
  { label: 'DIM - 维度数据层', value: 'DIM' },
  { label: 'DWS - 汇总数据层', value: 'DWS' },
  { label: 'ADS - 应用数据层', value: 'ADS' }
]

const statisticsOptions = [
  { label: '10分钟', value: '10m' },
  { label: '30分钟', value: '30m' },
  { label: '1小时', value: '1h' },
  { label: '1天', value: '1d' },
  { label: '实时', value: 'realtime' }
]

const updateTypeOptions = [
  { label: 'di - 日增量', value: 'di' },
  { label: 'df - 日全量', value: 'df' },
  { label: 'hi - 小时增量', value: 'hi' },
  { label: 'hf - 小时全量', value: 'hf' },
  { label: 'ri - 实时增量', value: 'ri' }
]

const businessDomainOptions = ref([])
const dataDomainOptions = ref([])

const editForm = reactive({
  tableName: '',
  tableComment: '',
  layer: '',
  businessDomain: '',
  dataDomain: '',
  customIdentifier: '',
  statisticsCycle: '',
  updateType: '',
  owner: ''
})

const editFormRules = {
  layer: [{ required: true, message: '请选择数据分层', trigger: 'change' }],
  businessDomain: [{ required: true, message: '请选择业务域', trigger: 'change' }],
  dataDomain: [{ required: true, message: '请选择数据域', trigger: 'change' }],
  customIdentifier: [
    { required: true, message: '请输入自定义标识', trigger: 'blur' },
    {
      pattern: TABLE_NAME_PATTERN,
      message: '仅支持小写字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  updateType: [{ required: true, message: '请选择更新类型', trigger: 'change' }],
  tableName: [
    { required: true, message: '表名不能为空', trigger: 'blur' },
    {
      pattern: TABLE_NAME_PATTERN,
      message: '表名仅支持小写字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  owner: [{ required: true, message: '请输入负责人', trigger: 'blur' }]
}

const canGenerateEditName = computed(
  () =>
    !!(
      editForm.layer &&
      editForm.businessDomain &&
      editForm.dataDomain &&
      editForm.customIdentifier &&
      editForm.updateType
    )
)

// 排序选项
const sortField = ref('createdAt')
const sortOrder = ref('desc')

// 加载数据库列表
const loadDatabases = async () => {
  loading.value = true
  try {
    databases.value = await tableApi.listDatabases()
    if (databases.value.length > 0) {
      activeDatabase.value = databases.value[0]
      await loadTablesForDatabase(databases.value[0])
    }
  } catch (error) {
    console.error('加载数据库列表失败:', error)
    ElMessage.error('加载数据库列表失败')
  } finally {
    loading.value = false
  }
}

// 加载指定数据库的表列表
const loadTablesForDatabase = async (database) => {
  if (!database) return
  try {
    const tables = await tableApi.listByDatabase(database, sortField.value, sortOrder.value)
    tablesByDatabase.value[database] = tables

    // 加载血缘关系信息
    for (const table of tables) {
      loadLineageForTable(table.id)
    }
  } catch (error) {
    console.error('加载表列表失败:', error)
  }
}

// 加载表的血缘关系
const loadLineageForTable = async (tableId) => {
  if (lineageCache.value[tableId]) return
  try {
    const lineageData = await tableApi.getLineage(tableId)
    lineageCache.value[tableId] = lineageData
  } catch (error) {
    console.error('加载血缘关系失败:', error)
  }
}

// 获取指定数据库的表列表
const getTablesForDatabase = (database) => {
  const tables = tablesByDatabase.value[database] || []
  if (!searchKeyword.value) return tables
  return tables.filter(
    (table) =>
      table.tableName.toLowerCase().includes(searchKeyword.value.toLowerCase()) ||
      (table.tableComment &&
        table.tableComment.toLowerCase().includes(searchKeyword.value.toLowerCase()))
  )
}

// 获取表数量
const getTableCount = (database) => {
  return getTablesForDatabase(database).length
}

const normalizeSegment = (value) =>
  value ? value.trim().toLowerCase().replace(/\s+/g, '_').replace(/-+/g, '_') : ''

const handleNormalizeSegment = (field) => {
  editForm[field] = normalizeSegment(editForm[field])
}

const loadBusinessDomainOptions = async () => {
  try {
    businessDomainOptions.value = await businessDomainApi.list()
  } catch (error) {
    console.error('加载业务域失败:', error)
    businessDomainOptions.value = []
  }
}

const loadDataDomainOptions = async (businessDomain) => {
  if (!businessDomain) {
    dataDomainOptions.value = []
    return
  }
  try {
    dataDomainOptions.value = await dataDomainApi.list({ businessDomain })
  } catch (error) {
    console.error('加载数据域失败:', error)
    dataDomainOptions.value = []
  }
}

const ensureBusinessDomainOption = (code) => {
  if (
    code &&
    !businessDomainOptions.value.some((item) => item.domainCode === code)
  ) {
    businessDomainOptions.value.push({
      domainCode: code,
      domainName: code
    })
  }
}

const ensureDataDomainOption = (code, businessDomain) => {
  if (
    code &&
    !dataDomainOptions.value.some((item) => item.domainCode === code)
  ) {
    dataDomainOptions.value.push({
      domainCode: code,
      domainName: code,
      businessDomain
    })
  }
}

const applyEditForm = async (data) => {
  if (!data) return
  editForm.tableName = data.tableName || ''
  editForm.tableComment = data.tableComment || ''
  editForm.layer = data.layer || ''
  editForm.businessDomain = data.businessDomain || ''
  await loadDataDomainOptions(editForm.businessDomain)
  ensureBusinessDomainOption(editForm.businessDomain)
  editForm.dataDomain = data.dataDomain || ''
  ensureDataDomainOption(editForm.dataDomain, editForm.businessDomain)
  editForm.customIdentifier = data.customIdentifier || ''
  editForm.statisticsCycle = data.statisticsCycle || ''
  editForm.updateType = data.updateType || ''
  editForm.owner = data.owner || ''
}

const handleEditBusinessDomainChange = async () => {
  await loadDataDomainOptions(editForm.businessDomain)
  if (
    editForm.dataDomain &&
    !dataDomainOptions.value.some((item) => item.domainCode === editForm.dataDomain)
  ) {
    editForm.dataDomain = ''
  }
}

const handleGenerateTableName = async () => {
  if (!canGenerateEditName.value) return
  try {
    const payload = {
      layer: editForm.layer,
      businessDomain: normalizeSegment(editForm.businessDomain),
      dataDomain: normalizeSegment(editForm.dataDomain),
      customIdentifier: normalizeSegment(editForm.customIdentifier),
      statisticsCycle: editForm.statisticsCycle || null,
      updateType: editForm.updateType
    }
    const name = await tableDesignerApi.generateTableName(payload)
    editForm.tableName = name
    ElMessage.success('已生成推荐表名')
  } catch (error) {
    console.error('生成表名失败:', error)
    ElMessage.error('生成表名失败')
  }
}

const startEditing = async () => {
  if (!selectedTable.value) return
  await loadBusinessDomainOptions()
  await applyEditForm(selectedTable.value)
  isEditing.value = true
  await nextTick()
  if (editFormRef.value) {
    editFormRef.value.clearValidate()
  }
}

const handleEditCancel = async () => {
  if (editSubmitting.value) return
  await applyEditForm(selectedTable.value)
  isEditing.value = false
  await nextTick()
  if (editFormRef.value) {
    editFormRef.value.clearValidate()
  }
}

const updateCachedTable = (updatedTable) => {
  if (!activeDatabase.value) return
  const list = tablesByDatabase.value[activeDatabase.value] || []
  const index = list.findIndex((item) => item.id === updatedTable.id)
  if (index === -1) return
  const updatedList = [...list]
  updatedList[index] = { ...updatedList[index], ...updatedTable }
  tablesByDatabase.value = {
    ...tablesByDatabase.value,
    [activeDatabase.value]: updatedList
  }
}

const handleEditSubmit = async () => {
  if (!selectedTable.value) return
  if (!editFormRef.value) return
  handleNormalizeSegment('customIdentifier')
  try {
    await editFormRef.value.validate()
  } catch (error) {
    return
  }

  editSubmitting.value = true
  try {
    const payload = {
      ...selectedTable.value,
      tableName: editForm.tableName.trim(),
      tableComment: editForm.tableComment,
      owner: editForm.owner,
      layer: editForm.layer,
      businessDomain: editForm.businessDomain,
      dataDomain: editForm.dataDomain,
      customIdentifier: editForm.customIdentifier,
      statisticsCycle: editForm.statisticsCycle || null,
      updateType: editForm.updateType
    }
    const updated = await tableApi.update(selectedTable.value.id, payload)
    await loadTableDetail(updated.id)
    updateCachedTable(updated)
    ElMessage.success('更新成功')
    await applyEditForm(selectedTable.value)
    isEditing.value = false
  } catch (error) {
    console.error('更新失败:', error)
    ElMessage.error('更新失败')
  } finally {
    editSubmitting.value = false
  }
}

// 获取上游表数量
const getUpstreamCount = (tableId) => {
  const lineageData = lineageCache.value[tableId]
  return lineageData?.upstreamTables?.length || 0
}

// 获取下游表数量
const getDownstreamCount = (tableId) => {
  const lineageData = lineageCache.value[tableId]
  return lineageData?.downstreamTables?.length || 0
}

// 处理数据库点击
const handleDatabaseClick = (database) => {
  if (!tablesByDatabase.value[database]) {
    loadTablesForDatabase(database)
  }
}

// 处理表点击
const handleTableClick = async (table) => {
  // 如果是点击的不同数据库的表，需要先找到对应的表ID
  let targetTable = table
  if (typeof table.id === 'number') {
    targetTable = table
  } else {
    // 如果是从血缘关系点击过来的，需要重新获取完整信息
    const fullTable = await tableApi.getById(table.id)
    targetTable = fullTable
  }

  selectedTable.value = targetTable
  activeTab.value = 'basic'
  isEditing.value = false
  if (editFormRef.value) {
    editFormRef.value.clearValidate()
  }
  // 切换表时清空DDL和预览数据
  tableDdl.value = ''
  previewData.value = []
  await loadTableDetail(targetTable.id)
}

// 加载表详情
const loadTableDetail = async (tableId) => {
  detailLoading.value = true
  try {
    const [tableInfo, fieldList, lineageData] = await Promise.all([
      tableApi.getById(tableId),
      tableApi.getFields(tableId),
      tableApi.getLineage(tableId)
    ])
    selectedTable.value = tableInfo
    fields.value = fieldList
    lineage.value = lineageData
    await applyEditForm(tableInfo)
  } catch (error) {
    console.error('加载表详情失败:', error)
    ElMessage.error('加载表详情失败')
  } finally {
    detailLoading.value = false
  }
}

// 刷新统计信息
const refreshStatistics = async () => {
  if (!selectedTable.value) return
  statisticsLoading.value = true
  try {
    const [stat, history] = await Promise.all([
      tableApi.getStatistics(selectedTable.value.id, null, true),
      tableApi.getLast7DaysHistory(selectedTable.value.id)
    ])
    statistics.value = stat
    statisticsHistory.value = history

    // 绘制趋势图
    await nextTick()
    renderTrendChart()
  } catch (error) {
    console.error('刷新统计信息失败:', error)
    ElMessage.error('刷新统计信息失败')
  } finally {
    statisticsLoading.value = false
  }
}

// 绘制数据量趋势图
const renderTrendChart = () => {
  const chartDom = document.getElementById('trendChart')
  if (!chartDom) return

  if (chartInstance) {
    chartInstance.dispose()
  }

  chartInstance = echarts.init(chartDom)

  const dates = statisticsHistory.value.map((item) =>
    new Date(item.recordTime).toLocaleDateString()
  )
  const rowCounts = statisticsHistory.value.map((item) => item.rowCount)

  const option = {
    title: {
      text: ''
    },
    tooltip: {
      trigger: 'axis'
    },
    xAxis: {
      type: 'category',
      data: dates
    },
    yAxis: {
      type: 'value',
      name: '数据行数'
    },
    series: [
      {
        data: rowCounts,
        type: 'line',
        smooth: true,
        areaStyle: {
          color: '#409EFF',
          opacity: 0.3
        },
        itemStyle: {
          color: '#409EFF'
        }
      }
    ]
  }

  chartInstance.setOption(option)
}

// 搜索处理
const handleSearch = () => {
  // 触发重新计算
}

// 处理排序变化
const handleSortChange = () => {
  // 重新加载所有已加载的数据库的表
  Object.keys(tablesByDatabase.value).forEach(database => {
    loadTablesForDatabase(database)
  })
}

// 复制DDL
const copyDdl = async () => {
  if (!tableDdl.value) return
  try {
    await navigator.clipboard.writeText(tableDdl.value)
    ElMessage.success('已复制到剪贴板')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

// 加载表DDL
const loadTableDdl = async () => {
  if (!selectedTable.value) return
  ddlLoading.value = true
  try {
    const ddl = await tableApi.getTableDdl(selectedTable.value.id)
    tableDdl.value = ddl
    ElMessage.success('DDL加载成功')
  } catch (error) {
    console.error('加载DDL失败:', error)
    ElMessage.error('加载DDL失败: ' + (error.message || '未知错误'))
  } finally {
    ddlLoading.value = false
  }
}

// 加载预览数据
const loadPreviewData = async () => {
  if (!selectedTable.value) return
  previewLoading.value = true
  try {
    const data = await tableApi.previewTableData(selectedTable.value.id, null, previewLimit.value)
    previewData.value = data
    ElMessage.success(`加载了 ${data.length} 条记录`)
  } catch (error) {
    console.error('加载预览数据失败:', error)
    ElMessage.error('加载预览数据失败: ' + (error.message || '未知错误'))
  } finally {
    previewLoading.value = false
  }
}

// 格式化单元格值
const formatCellValue = (value) => {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

// 跳转到创建页面
const goCreate = () => {
  router.push('/tables/create')
}

// 比对 Doris 元数据
const auditDorisMetadata = async () => {
  auditLoading.value = true
  try {
    const response = await tableApi.auditMetadata()

    auditResult.value = {
      hasDifferences: response.hasDifferences,
      totalDifferences: response.totalDifferences,
      differences: response.differences,
      errors: response.errors,
      statisticsSynced: response.statisticsSynced || 0,
      time: new Date().toLocaleString()
    }

    // 构建提示消息
    let message = ''
    if (response.statisticsSynced > 0) {
      message += `已自动同步 ${response.statisticsSynced} 张表的统计信息。`
    }

    if (response.hasDifferences) {
      message += `发现 ${response.totalDifferences} 处结构差异，请查看详情并确认是否同步。`
      ElMessage.warning({
        message: message,
        duration: 5000
      })
    } else {
      message += '结构一致，无需同步。'
      ElMessage.success({
        message: message,
        duration: 3000
      })
    }
  } catch (error) {
    console.error('比对元数据失败:', error)
    ElMessage.error('比对元数据失败: ' + (error.message || '未知错误'))
  } finally {
    auditLoading.value = false
  }
}

// 显示差异详情弹窗
const showDifferenceDialog = () => {
  differenceDialogVisible.value = true
}

// 确认同步
const confirmSync = async () => {
  try {
    await ElMessageBox.confirm(
      `确认要同步 ${auditResult.value.totalDifferences} 处差异吗？`,
      '确认同步',
      {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    await performSync()
  } catch {
    // 用户取消
  }
}

// 从对话框确认同步
const confirmSyncFromDialog = async () => {
  differenceDialogVisible.value = false
  await performSync()
}

// 执行同步
const performSync = async () => {
  syncLoading.value = true
  try {
    const response = await tableApi.syncMetadata()

    if (response.success) {
      ElMessage.success('元数据同步成功！')
    } else {
      ElMessage.warning('元数据同步完成，但存在部分错误')
    }

    // 清空稽核结果
    auditResult.value = null

    // 刷新表列表
    await loadDatabases()

    // 如果当前有选中的表，刷新表详情
    if (selectedTable.value) {
      await loadTableDetail(selectedTable.value.id)
    }
  } catch (error) {
    console.error('同步元数据失败:', error)
    ElMessage.error('同步元数据失败: ' + (error.message || '未知错误'))
  } finally {
    syncLoading.value = false
  }
}

// 跳转到血缘关系页面
const goLineage = () => {
  if (selectedTable.value) {
    router.push({
      path: '/lineage',
      query: { focus: selectedTable.value.tableName }
    })
  }
}

// 删除表
const handleDelete = async (id) => {
  try {
    await tableApi.delete(id)
    ElMessage.success('删除成功')
    selectedTable.value = null
    // 重新加载当前数据库的表列表
    if (activeDatabase.value) {
      await loadTablesForDatabase(activeDatabase.value)
    }
  } catch (error) {
    console.error('删除失败:', error)
    ElMessage.error('删除失败')
  }
}

// 字段管理相关方法
const handleAddField = () => {
  if (isAddingField.value) return

  const newField = {
    id: null,
    fieldName: '',
    fieldType: '',
    fieldComment: '',
    isNullable: 1,
    isPrimary: 0,
    defaultValue: '',
    fieldOrder: fields.value.length,
    _isNew: true,
    _isEditing: false
  }

  fields.value.unshift(newField)
  isAddingField.value = true
}

const handleEditFieldInline = (row) => {
  // 保存原始数据用于取消时恢复
  editingFieldBackup.value = { ...row }
  row._isEditing = true
}

const handleCancelFieldEdit = (row, index) => {
  if (row._isNew) {
    // 如果是新增的行，直接删除
    fields.value.splice(index, 1)
    isAddingField.value = false
  } else {
    // 如果是编辑的行，恢复原始数据
    if (editingFieldBackup.value) {
      Object.assign(row, editingFieldBackup.value)
      delete row._isEditing
      editingFieldBackup.value = null
    }
  }
}

const handleSaveField = async (row, index) => {
  // 验证必填字段
  if (!row.fieldName || !row.fieldName.trim()) {
    ElMessage.warning('请输入字段名')
    return
  }
  if (!row.fieldType || !row.fieldType.trim()) {
    ElMessage.warning('请输入字段类型')
    return
  }

  fieldSubmitting.value = true
  try {
    if (row._isNew) {
      // 新增字段
      const payload = {
        fieldName: row.fieldName.trim(),
        fieldType: row.fieldType.trim(),
        fieldComment: row.fieldComment || '',
        isNullable: row.isNullable,
        isPrimary: row.isPrimary,
        defaultValue: row.defaultValue || '',
        fieldOrder: row.fieldOrder
      }
      await tableApi.createField(selectedTable.value.id, payload)
      ElMessage.success('新增字段成功')
      isAddingField.value = false
    } else {
      // 更新字段
      const payload = {
        fieldName: row.fieldName.trim(),
        fieldType: row.fieldType.trim(),
        fieldComment: row.fieldComment || '',
        isNullable: row.isNullable,
        isPrimary: row.isPrimary,
        defaultValue: row.defaultValue || '',
        fieldOrder: row.fieldOrder
      }
      await tableApi.updateField(selectedTable.value.id, row.id, payload)
      ElMessage.success('更新字段成功')
      delete row._isEditing
      editingFieldBackup.value = null
    }

    // 重新加载字段列表
    await loadTableDetail(selectedTable.value.id)
  } catch (error) {
    console.error('字段操作失败:', error)
    ElMessage.error(error.message || '操作失败')
  } finally {
    fieldSubmitting.value = false
  }
}

const handleDeleteField = async (row) => {
  try {
    await tableApi.deleteField(selectedTable.value.id, row.id)
    ElMessage.success('删除字段成功')
    // 重新加载字段列表
    await loadTableDetail(selectedTable.value.id)
  } catch (error) {
    console.error('删除字段失败:', error)
    ElMessage.error('删除字段失败')
  }
}

// 获取层级标签类型
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

// 格式化数字
const formatNumber = (num) => {
  if (num === null || num === undefined) return '-'
  return num.toLocaleString('zh-CN')
}

// 格式化行数（简化显示）
const formatRowCount = (rowCount) => {
  if (rowCount === null || rowCount === undefined) return '-'
  if (rowCount === 0) return '0'
  if (rowCount < 1000) return rowCount.toString()
  if (rowCount < 1000000) return (rowCount / 1000).toFixed(1) + 'K'
  if (rowCount < 1000000000) return (rowCount / 1000000).toFixed(1) + 'M'
  return (rowCount / 1000000000).toFixed(1) + 'B'
}
// 获取表的数据量（真实数据）
const getTableRowCount = (table) => {
  if (!table || table.rowCount === null || table.rowCount === undefined) {
    return 0
  }
  return Number(table.rowCount) || 0
}

// 计算表的数据量进度条宽度
const getTableProgressWidth = (database, table) => {
  const tables = tablesByDatabase.value[database] || []
  if (!tables.length) return '0%'

  // 获取当前表的数据量
  const currentRowCount = getTableRowCount(table)

  // 找出该数据库中最大的数据量
  const maxRowCount = Math.max(...tables.map(t => getTableRowCount(t)))
  if (!Number.isFinite(maxRowCount) || maxRowCount <= 0) {
    return '0%'
  }

  // 计算百分比，最小10%以保证有基本可见度
  const percentage = Math.max(10, (currentRowCount / maxRowCount) * 100)
  return percentage.toFixed(1) + '%'
}

// 格式化日期时间
const formatDateTime = (dateTime) => {
  if (!dateTime) return '-'
  try {
    const date = new Date(dateTime)
    return date.toLocaleString('zh-CN')
  } catch (error) {
    return dateTime
  }
}

onMounted(() => {
  loadDatabases()
  loadBusinessDomainOptions()
})
</script>

<style scoped>
.table-management {
  height: 100%;
  padding: 6px;
  background-color: #f8fafc;
}

.management-container {
  display: flex;
  gap: 8px;
  height: calc(100vh - 92px);
}

/* 左侧面板 */
.left-panel {
  width: 420px;
  flex-shrink: 0;
}

.database-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  transition: box-shadow 0.3s ease;
}

.database-card:hover {
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.12);
}

.search-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.search-bar .search-input {
  flex: 1;
}

.sync-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background-color: #f0f9ff;
  border-radius: 6px;
  font-size: 13px;
  color: #555;
}

.sync-info .sync-text {
  flex: 1;
}

.audit-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background-color: #fef0e6;
  border-radius: 6px;
  font-size: 13px;
  color: #555;
}

.audit-info .audit-text {
  flex: 1;
}

.database-list {
  flex: 1;
  overflow-y: auto;
  padding-right: 6px;
}

.database-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

.db-name {
  font-weight: 600;
  flex: 1;
}

.table-count-badge {
  margin-left: auto;
}

.sort-options {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px;
  background-color: #f8fafc;
  border-radius: 8px;
}

.table-list {
  padding: 4px 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.table-item {
  padding: 6px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s ease;
  background-color: #fff;
  display: flex;
  align-items: center;
  gap: 8px;
  position: relative;
  overflow: hidden;
}

.table-item:hover {
  border-color: #667eea;
  background-color: #f0f4ff;
  transform: translateX(4px);
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.12);
}

.table-item.active {
  border-color: #667eea;
  background-color: #f0f4ff;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.15);
}

/* 数据量进度条背景 */
.table-progress-bg {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.08) 0%, rgba(102, 126, 234, 0.02) 100%);
  transition: width 0.3s ease;
  pointer-events: none;
  z-index: 0;
}

.table-item:hover .table-progress-bg {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.12) 0%, rgba(102, 126, 234, 0.04) 100%);
}

.table-item.active .table-progress-bg {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.18) 0%, rgba(102, 126, 234, 0.06) 100%);
}

/* 表内容容器 */
.table-content {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  position: relative;
  z-index: 1;
}

.table-icon {
  color: #667eea;
  flex-shrink: 0;
}

.table-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  max-width: 200px; /* 限制表信息区域的最大宽度，确保右侧标签可见 */
}

.table-name {
  font-weight: 600;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
  max-width: 100px; /* 减小表名最大宽度，给注释更多空间 */
}

.table-comment {
  color: #909399;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.table-meta-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.row-count {
  font-size: 11px;
  color: #475569;
  font-weight: 500;
  padding: 2px 6px;
  background-color: rgba(102, 126, 234, 0.1);
  border-radius: 4px;
  min-width: 35px;
  text-align: center;
}

.lineage-count {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 5px;
  border-radius: 4px;
  min-width: 28px;
  text-align: center;
}

.lineage-count.upstream {
  color: #10b981;
  background-color: rgba(16, 185, 129, 0.1);
}

.lineage-count.downstream {
  color: #f59e0b;
  background-color: rgba(245, 158, 11, 0.1);
}

.layer-tag {
  flex-shrink: 0;
}

/* 右侧面板 */
.right-panel {
  flex: 1;
  overflow: hidden;
}

.detail-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  transition: box-shadow 0.3s ease;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.detail-tabs {
  flex: 1;
  overflow-y: auto;
}

/* 基本信息 */
.basic-section,
.fields-section,
.statistics-section,
.ddl-section,
.preview-section,
.export-section {
  padding: 16px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-header h3 {
  margin: 0;
  font-size: 18px;
}

.action-buttons {
  display: flex;
  gap: 10px;
}

.info-cards {
  margin-bottom: 24px;
}

/* 血缘关系 */
.lineage-section {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #ebeef5;
}

.lineage-section h3 {
  margin: 0 0 16px 0;
  font-size: 18px;
}

.lineage-card {
  max-height: 400px;
  overflow-y: auto;
}

.lineage-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.lineage-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lineage-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s ease;
}

.lineage-item:hover {
  border-color: #667eea;
  background-color: #f0f4ff;
  transform: translateY(-2px);
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.12);
}

.table-info-mini {
  flex: 1;
  overflow: hidden;
}

.table-info-mini .table-name {
  font-weight: 600;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-info-mini .table-desc {
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 编辑区域 */
.inline-edit-wrapper {
  padding: 20px;
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  margin-bottom: 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.table-name-input-group {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.table-name-input-group :deep(.el-input) {
  flex: 1;
}

/* 统计信息 */
.stat-cards {
  margin-bottom: 24px;
}

.stat-card {
  padding: 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 8px;
  color: white;
  text-align: center;
}

.stat-label {
  font-size: 14px;
  opacity: 0.9;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
}

.trend-chart {
  background-color: #fff;
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.trend-chart h4 {
  margin: 0 0 16px 0;
  font-size: 16px;
}

.ddl-textarea {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.preview-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}

.preview-table-wrapper {
  margin-top: 16px;
}

.preview-footer {
  margin-top: 12px;
  text-align: center;
  padding: 12px;
  background-color: #f8fafc;
  border-radius: 8px;
}

:deep(.el-card__body) {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

:deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

:deep(.el-tabs__content) {
  overflow-y: auto;
}

/* 差异详情对话框样式 */
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
  margin-right: 4px;
}
</style>
