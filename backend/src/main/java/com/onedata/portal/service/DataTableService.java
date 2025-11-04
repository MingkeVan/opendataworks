package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.TableLineageItem;
import com.onedata.portal.dto.TableOption;
import com.onedata.portal.dto.TableRelatedLineageResponse;
import com.onedata.portal.dto.TableRelatedTasksResponse;
import com.onedata.portal.dto.TableTaskInfo;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据表服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTableService {

    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DataTaskMapper dataTaskMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final DataLineageMapper dataLineageMapper;

    /**
     * 分页查询表列表
     */
    public Page<DataTable> list(int pageNum, int pageSize, String layer, String keyword, String sortField, String sortOrder) {
        Page<DataTable> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<>();

        if (layer != null && !layer.isEmpty()) {
            wrapper.eq(DataTable::getLayer, layer);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(DataTable::getTableName, keyword)
                    .or().like(DataTable::getTableComment, keyword));
        }

        // 应用排序
        applySorting(wrapper, sortField, sortOrder);

        return dataTableMapper.selectPage(page, wrapper);
    }

    /**
     * 获取所有数据库列表
     */
    public List<String> listDatabases() {
        List<DataTable> allTables = dataTableMapper.selectList(null);
        return allTables.stream()
            .map(DataTable::getDbName)
            .filter(dbName -> dbName != null && !dbName.isEmpty())
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * 根据数据库获取表列表
     */
    public List<DataTable> listByDatabase(String database, String sortField, String sortOrder) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataTable::getDbName, database);

        // 应用排序
        applySorting(wrapper, sortField, sortOrder);

        return dataTableMapper.selectList(wrapper);
    }

    /**
     * 应用排序逻辑
     */
    private void applySorting(LambdaQueryWrapper<DataTable> wrapper, String sortField, String sortOrder) {
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);

        if (sortField == null || sortField.isEmpty()) {
            wrapper.orderByDesc(DataTable::getCreatedAt);
            return;
        }

        switch (sortField) {
            case "tableName":
                if (isAsc) wrapper.orderByAsc(DataTable::getTableName);
                else wrapper.orderByDesc(DataTable::getTableName);
                break;
            case "createdAt":
                if (isAsc) wrapper.orderByAsc(DataTable::getCreatedAt);
                else wrapper.orderByDesc(DataTable::getCreatedAt);
                break;
            case "updatedAt":
                if (isAsc) wrapper.orderByAsc(DataTable::getUpdatedAt);
                else wrapper.orderByDesc(DataTable::getUpdatedAt);
                break;
            case "lastUpdated":
                if (isAsc) wrapper.orderByAsc(DataTable::getLastUpdated);
                else wrapper.orderByDesc(DataTable::getLastUpdated);
                break;
            case "rowCount":
                if (isAsc) wrapper.orderByAsc(DataTable::getRowCount);
                else wrapper.orderByDesc(DataTable::getRowCount);
                break;
            case "storageSize":
                if (isAsc) wrapper.orderByAsc(DataTable::getStorageSize);
                else wrapper.orderByDesc(DataTable::getStorageSize);
                break;
            default:
                wrapper.orderByDesc(DataTable::getCreatedAt);
        }
    }

    /**
     * 根据ID获取表信息
     */
    public DataTable getById(Long id) {
        return dataTableMapper.selectById(id);
    }

    /**
     * 根据表名获取表信息
     */
    public DataTable getByTableName(String tableName) {
        return dataTableMapper.selectOne(
            new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getTableName, tableName)
        );
    }

    /**
     * 创建表
     */
    @Transactional
    public DataTable create(DataTable dataTable) {
        // 检查表名是否已存在
        DataTable exists = getByTableName(dataTable.getTableName());
        if (exists != null) {
            throw new RuntimeException("表名已存在: " + dataTable.getTableName());
        }

        dataTableMapper.insert(dataTable);
        log.info("Created data table: {}", dataTable.getTableName());
        return dataTable;
    }

    /**
     * 更新表
     */
    @Transactional
    public DataTable update(DataTable dataTable) {
        DataTable exists = dataTableMapper.selectById(dataTable.getId());
        if (exists == null) {
            throw new RuntimeException("表不存在");
        }

        // 检查表名是否发生变化且是否重复
        String newTableName = dataTable.getTableName();
        if (StringUtils.hasText(newTableName) && !newTableName.equals(exists.getTableName())) {
            DataTable duplicate = dataTableMapper.selectOne(
                new LambdaQueryWrapper<DataTable>()
                    .eq(DataTable::getTableName, newTableName)
                    .ne(DataTable::getId, dataTable.getId())
            );
            if (duplicate != null) {
                throw new RuntimeException("表名已存在: " + newTableName);
            }
        }

        dataTableMapper.updateById(dataTable);
        log.info("Updated data table: {}", dataTable.getTableName());
        return dataTable;
    }

    /**
     * 删除表
     */
    @Transactional
    public void delete(Long id) {
        dataTableMapper.deleteById(id);
        log.info("Deleted data table: {}", id);
    }

    /**
     * 获取所有表（用于任务配置）
     */
    public List<DataTable> listAll() {
        return dataTableMapper.selectList(
            new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getStatus, "active")
                .orderByAsc(DataTable::getLayer, DataTable::getTableName)
        );
    }

    /**
     * 远程搜索表选项
     */
    public List<TableOption> searchTableOptions(String keyword, Integer limit, String layer, String dbName) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        String trimmed = keyword.trim();
        int pageSize = (limit != null && limit > 0) ? Math.min(limit, 100) : 50;

        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(DataTable::getTableName, trimmed)
            .or().like(DataTable::getTableComment, trimmed));

        if (StringUtils.hasText(layer)) {
            wrapper.eq(DataTable::getLayer, layer.trim());
        }

        if (StringUtils.hasText(dbName)) {
            wrapper.eq(DataTable::getDbName, dbName.trim());
        }

        wrapper.orderByAsc(DataTable::getTableName);

        Page<DataTable> page = new Page<>(1, pageSize);
        Page<DataTable> result = dataTableMapper.selectPage(page, wrapper);

        return result.getRecords().stream()
            .map(this::toTableOption)
            .collect(Collectors.toList());
    }

    private TableOption toTableOption(DataTable table) {
        TableOption option = new TableOption();
        option.setId(table.getId());
        option.setTableName(table.getTableName());
        option.setTableComment(table.getTableComment());
        option.setLayer(table.getLayer());
        option.setDbName(table.getDbName());
        return option;
    }

    /**
     * 获取表字段列表
     */
    public List<DataField> listFields(Long tableId) {
        return dataFieldMapper.selectList(
            new LambdaQueryWrapper<DataField>()
                .eq(DataField::getTableId, tableId)
                .orderByAsc(DataField::getFieldOrder, DataField::getId)
        );
    }

    /**
     * 创建字段
     */
    @Transactional
    public DataField createField(DataField field) {
        // 检查字段名是否已存在
        DataField exists = dataFieldMapper.selectOne(
            new LambdaQueryWrapper<DataField>()
                .eq(DataField::getTableId, field.getTableId())
                .eq(DataField::getFieldName, field.getFieldName())
        );
        if (exists != null) {
            throw new RuntimeException("字段名已存在: " + field.getFieldName());
        }

        dataFieldMapper.insert(field);
        log.info("Created field: {} for table: {}", field.getFieldName(), field.getTableId());
        return field;
    }

    /**
     * 更新字段
     */
    @Transactional
    public DataField updateField(DataField field) {
        DataField exists = dataFieldMapper.selectById(field.getId());
        if (exists == null) {
            throw new RuntimeException("字段不存在");
        }

        // 检查字段名是否重复
        String newFieldName = field.getFieldName();
        if (StringUtils.hasText(newFieldName) && !newFieldName.equals(exists.getFieldName())) {
            DataField duplicate = dataFieldMapper.selectOne(
                new LambdaQueryWrapper<DataField>()
                    .eq(DataField::getTableId, field.getTableId())
                    .eq(DataField::getFieldName, newFieldName)
                    .ne(DataField::getId, field.getId())
            );
            if (duplicate != null) {
                throw new RuntimeException("字段名已存在: " + newFieldName);
            }
        }

        dataFieldMapper.updateById(field);
        log.info("Updated field: {}", field.getFieldName());
        return field;
    }

    /**
     * 删除字段
     */
    @Transactional
    public void deleteField(Long fieldId) {
        dataFieldMapper.deleteById(fieldId);
        log.info("Deleted field: {}", fieldId);
    }

    /**
     * 获取表的关联任务
     */
    public TableRelatedTasksResponse getRelatedTasks(Long tableId) {
        TableRelatedTasksResponse response = new TableRelatedTasksResponse();
        List<TableTaskRelation> relations = tableTaskRelationMapper.selectList(
            new LambdaQueryWrapper<TableTaskRelation>()
                .eq(TableTaskRelation::getTableId, tableId)
        );
        if (relations.isEmpty()) {
            return response;
        }

        Set<Long> taskIds = relations.stream()
            .map(TableTaskRelation::getTaskId)
            .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return response;
        }

        List<DataTask> tasks = dataTaskMapper.selectBatchIds(taskIds);
        Map<Long, DataTask> taskMap = tasks.stream()
            .collect(Collectors.toMap(DataTask::getId, t -> t));

        for (TableTaskRelation relation : relations) {
            DataTask task = taskMap.get(relation.getTaskId());
            if (task == null) {
                continue;
            }
            TableTaskInfo info = buildTaskInfo(task, relation.getRelationType());
            if ("write".equalsIgnoreCase(relation.getRelationType())) {
                response.getWriteTasks().add(info);
            } else {
                response.getReadTasks().add(info);
            }
        }

        sortTasks(response.getWriteTasks());
        sortTasks(response.getReadTasks());
        return response;
    }

    /**
     * 获取表上下游
     */
    public TableRelatedLineageResponse getRelatedLineage(Long tableId) {
        TableRelatedLineageResponse response = new TableRelatedLineageResponse();

        // 1. 找到所有写入当前表的任务（这些任务读取的表是上游表）
        List<TableTaskRelation> writeRelations = tableTaskRelationMapper.selectList(
            new LambdaQueryWrapper<TableTaskRelation>()
                .eq(TableTaskRelation::getTableId, tableId)
                .eq(TableTaskRelation::getRelationType, "write")
        );

        Set<Long> writeTasks = writeRelations.stream()
            .map(TableTaskRelation::getTaskId)
            .collect(Collectors.toSet());

        // 2. 找到所有从当前表读取的任务（这些任务写入的表是下游表）
        List<TableTaskRelation> readRelations = tableTaskRelationMapper.selectList(
            new LambdaQueryWrapper<TableTaskRelation>()
                .eq(TableTaskRelation::getTableId, tableId)
                .eq(TableTaskRelation::getRelationType, "read")
        );

        Set<Long> readTasks = readRelations.stream()
            .map(TableTaskRelation::getTaskId)
            .collect(Collectors.toSet());

        // 3. 获取上游表ID（写入任务读取的表）
        Set<Long> upstreamIds = new LinkedHashSet<>();
        if (!writeTasks.isEmpty()) {
            List<TableTaskRelation> upstreamRelations = tableTaskRelationMapper.selectList(
                new LambdaQueryWrapper<TableTaskRelation>()
                    .in(TableTaskRelation::getTaskId, writeTasks)
                    .eq(TableTaskRelation::getRelationType, "read")
            );
            upstreamIds = upstreamRelations.stream()
                .map(TableTaskRelation::getTableId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 4. 获取下游表ID（读取任务写入的表）
        Set<Long> downstreamIds = new LinkedHashSet<>();
        if (!readTasks.isEmpty()) {
            List<TableTaskRelation> downstreamRelations = tableTaskRelationMapper.selectList(
                new LambdaQueryWrapper<TableTaskRelation>()
                    .in(TableTaskRelation::getTaskId, readTasks)
                    .eq(TableTaskRelation::getRelationType, "write")
            );
            downstreamIds = downstreamRelations.stream()
                .map(TableTaskRelation::getTableId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 5. 查询表详情
        Set<Long> allIds = new HashSet<>();
        allIds.addAll(upstreamIds);
        allIds.addAll(downstreamIds);

        if (allIds.isEmpty()) {
            return response;
        }

        List<DataTable> tables = dataTableMapper.selectBatchIds(allIds);
        Map<Long, DataTable> tableMap = tables.stream()
            .collect(Collectors.toMap(DataTable::getId, t -> t));

        // 6. 构建响应
        for (Long id : upstreamIds) {
            DataTable table = tableMap.get(id);
            if (table != null) {
                response.getUpstreamTables().add(buildLineageItem(table));
            }
        }
        for (Long id : downstreamIds) {
            DataTable table = tableMap.get(id);
            if (table != null) {
                response.getDownstreamTables().add(buildLineageItem(table));
            }
        }
        return response;
    }

    private TableTaskInfo buildTaskInfo(DataTask task, String relationType) {
        TableTaskInfo info = new TableTaskInfo();
        info.setId(task.getId());
        info.setTaskName(task.getTaskName());
        info.setTaskCode(task.getTaskCode());
        info.setRelationType(relationType);
        info.setStatus(task.getStatus());
        info.setEngine(task.getEngine());
        info.setScheduleCron(task.getScheduleCron());

        TaskExecutionLog lastLog = taskExecutionLogMapper.selectOne(
            new LambdaQueryWrapper<TaskExecutionLog>()
                .eq(TaskExecutionLog::getTaskId, task.getId())
                .orderByDesc(TaskExecutionLog::getStartTime)
                .last("LIMIT 1")
        );
        if (lastLog != null) {
            LocalDateTime executedAt = lastLog.getEndTime() != null ? lastLog.getEndTime() : lastLog.getStartTime();
            info.setLastExecuted(executedAt);
            info.setLastExecutionStatus(lastLog.getStatus());
        }
        return info;
    }

    private void sortTasks(List<TableTaskInfo> tasks) {
        tasks.sort((a, b) -> {
            LocalDateTime timeA = a.getLastExecuted();
            LocalDateTime timeB = b.getLastExecuted();
            if (timeA == null && timeB == null) {
                return 0;
            }
            if (timeA == null) {
                return 1;
            }
            if (timeB == null) {
                return -1;
            }
            return timeB.compareTo(timeA);
        });
    }

    private TableLineageItem buildLineageItem(DataTable table) {
        TableLineageItem item = new TableLineageItem();
        item.setId(table.getId());
        item.setTableName(table.getTableName());
        item.setTableComment(table.getTableComment());
        item.setLayer(table.getLayer());
        item.setBusinessDomain(table.getBusinessDomain());
        item.setDataDomain(table.getDataDomain());
        return item;
    }
}
