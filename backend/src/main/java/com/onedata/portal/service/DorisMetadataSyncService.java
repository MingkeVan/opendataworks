package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Doris 元数据同步服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisMetadataSyncService {

    private final DorisConnectionService dorisConnectionService;
    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;

    /**
     * 同步结果
     */
    public static class SyncResult {
        private int newTables = 0;
        private int updatedTables = 0;
        private int newFields = 0;
        private int updatedFields = 0;
        private int deletedFields = 0;
        private List<String> errors = new ArrayList<>();

        public void addNewTable() { newTables++; }
        public void addUpdatedTable() { updatedTables++; }
        public void addNewField() { newFields++; }
        public void addUpdatedField() { updatedFields++; }
        public void addDeletedField() { deletedFields++; }
        public void addError(String error) { errors.add(error); }

        public int getNewTables() { return newTables; }
        public int getUpdatedTables() { return updatedTables; }
        public int getNewFields() { return newFields; }
        public int getUpdatedFields() { return updatedFields; }
        public int getDeletedFields() { return deletedFields; }
        public List<String> getErrors() { return errors; }

        @Override
        public String toString() {
            return String.format(
                "SyncResult{newTables=%d, updatedTables=%d, newFields=%d, updatedFields=%d, deletedFields=%d, errors=%d}",
                newTables, updatedTables, newFields, updatedFields, deletedFields, errors.size()
            );
        }
    }

    /**
     * 同步指定集群的所有元数据
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncAllMetadata(Long clusterId) {
        SyncResult result = new SyncResult();
        log.info("Starting metadata sync for cluster: {}", clusterId);

        try {
            // 获取所有数据库
            List<String> databases = dorisConnectionService.getAllDatabases(clusterId);
            log.info("Found {} databases to sync", databases.size());

            for (String database : databases) {
                try {
                    syncDatabase(clusterId, database, result);
                } catch (Exception e) {
                    log.error("Failed to sync database: {}", database, e);
                    result.addError("同步数据库 " + database + " 失败: " + e.getMessage());
                }
            }

            log.info("Metadata sync completed: {}", result);
        } catch (Exception e) {
            log.error("Failed to sync metadata", e);
            result.addError("同步元数据失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 同步指定数据库的元数据
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncDatabase(Long clusterId, String database, SyncResult result) {
        if (result == null) {
            result = new SyncResult();
        }

        log.info("Syncing database: {}", database);

        // 获取 Doris 中的所有表
        List<Map<String, Object>> dorisTables = dorisConnectionService.getTablesInDatabase(clusterId, database);
        log.info("Found {} tables in database {}", dorisTables.size(), database);

        // 获取本地已存在的表
        List<DataTable> localTables = dataTableMapper.selectList(
            new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getDbName, database)
        );

        Map<String, DataTable> localTableMap = localTables.stream()
            .collect(Collectors.toMap(DataTable::getTableName, t -> t));

        // 遍历 Doris 中的表
        for (Map<String, Object> dorisTable : dorisTables) {
            String tableName = (String) dorisTable.get("tableName");

            try {
                DataTable localTable = localTableMap.get(tableName);

                if (localTable == null) {
                    // 新表：插入元数据
                    syncNewTable(clusterId, database, tableName, dorisTable, result);
                } else {
                    // 已存在表：更新元数据
                    syncExistingTable(clusterId, database, tableName, dorisTable, localTable, result);
                }
            } catch (Exception e) {
                log.error("Failed to sync table {}.{}", database, tableName, e);
                result.addError("同步表 " + database + "." + tableName + " 失败: " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * 同步新表
     */
    private void syncNewTable(Long clusterId, String database, String tableName,
                              Map<String, Object> dorisTable, SyncResult result) {
        log.info("Syncing new table: {}.{}", database, tableName);

        // 获取表的详细信息
        Map<String, Object> tableCreateInfo = dorisConnectionService.getTableCreateInfo(clusterId, database, tableName);
        List<Map<String, Object>> columns = dorisConnectionService.getColumnsInTable(clusterId, database, tableName);

        // 创建表记录
        DataTable newTable = new DataTable();
        newTable.setTableName(tableName);
        newTable.setDbName(database);

        String tableComment = (String) dorisTable.get("tableComment");
        newTable.setTableComment(tableComment != null ? tableComment : "");

        newTable.setStatus("active");
        newTable.setIsSynced(1);
        newTable.setSyncTime(LocalDateTime.now());

        // 从建表语句中解析的信息
        if (tableCreateInfo.containsKey("bucketNum")) {
            newTable.setBucketNum((Integer) tableCreateInfo.get("bucketNum"));
        }
        if (tableCreateInfo.containsKey("replicationNum")) {
            newTable.setReplicaNum((Integer) tableCreateInfo.get("replicationNum"));
        }
        if (tableCreateInfo.containsKey("partitionField")) {
            newTable.setPartitionField((String) tableCreateInfo.get("partitionField"));
        }
        if (tableCreateInfo.containsKey("distributionColumn")) {
            newTable.setDistributionColumn((String) tableCreateInfo.get("distributionColumn"));
        }
        if (tableCreateInfo.containsKey("keyColumns")) {
            newTable.setKeyColumns((String) tableCreateInfo.get("keyColumns"));
        }
        if (tableCreateInfo.containsKey("tableModel")) {
            newTable.setTableModel((String) tableCreateInfo.get("tableModel"));
        }
        if (tableCreateInfo.containsKey("createTableSql")) {
            newTable.setDorisDdl((String) tableCreateInfo.get("createTableSql"));
        }

        // 从统计信息中获取的信息
        Long tableRows = (Long) dorisTable.get("tableRows");
        if (tableRows != null) {
            newTable.setRowCount(tableRows);
        }

        Long dataLength = (Long) dorisTable.get("dataLength");
        if (dataLength != null) {
            newTable.setStorageSize(dataLength);
        }

        Timestamp updateTime = (Timestamp) dorisTable.get("updateTime");
        if (updateTime != null) {
            newTable.setLastUpdated(updateTime.toLocalDateTime());
        }

        dataTableMapper.insert(newTable);
        result.addNewTable();

        // 同步字段
        syncTableFields(newTable.getId(), columns, result);
    }

    /**
     * 同步已存在的表
     */
    private void syncExistingTable(Long clusterId, String database, String tableName,
                                   Map<String, Object> dorisTable, DataTable localTable, SyncResult result) {
        log.debug("Syncing existing table: {}.{}", database, tableName);

        // 获取表的详细信息
        Map<String, Object> tableCreateInfo = dorisConnectionService.getTableCreateInfo(clusterId, database, tableName);
        List<Map<String, Object>> columns = dorisConnectionService.getColumnsInTable(clusterId, database, tableName);

        boolean updated = false;

        // 更新表注释
        String tableComment = (String) dorisTable.get("tableComment");
        if (tableComment != null && !tableComment.equals(localTable.getTableComment())) {
            localTable.setTableComment(tableComment);
            updated = true;
        }

        // 更新分桶数
        if (tableCreateInfo.containsKey("bucketNum")) {
            Integer bucketNum = (Integer) tableCreateInfo.get("bucketNum");
            if (!Objects.equals(bucketNum, localTable.getBucketNum())) {
                localTable.setBucketNum(bucketNum);
                updated = true;
            }
        }

        // 更新副本数
        if (tableCreateInfo.containsKey("replicationNum")) {
            Integer replicationNum = (Integer) tableCreateInfo.get("replicationNum");
            if (!Objects.equals(replicationNum, localTable.getReplicaNum())) {
                localTable.setReplicaNum(replicationNum);
                updated = true;
            }
        }

        // 更新分区字段
        if (tableCreateInfo.containsKey("partitionField")) {
            String partitionField = (String) tableCreateInfo.get("partitionField");
            if (!Objects.equals(partitionField, localTable.getPartitionField())) {
                localTable.setPartitionField(partitionField);
                updated = true;
            }
        }

        // 更新分桶字段
        if (tableCreateInfo.containsKey("distributionColumn")) {
            String distributionColumn = (String) tableCreateInfo.get("distributionColumn");
            if (!Objects.equals(distributionColumn, localTable.getDistributionColumn())) {
                localTable.setDistributionColumn(distributionColumn);
                updated = true;
            }
        }

        // 更新 Key 列
        if (tableCreateInfo.containsKey("keyColumns")) {
            String keyColumns = (String) tableCreateInfo.get("keyColumns");
            if (!Objects.equals(keyColumns, localTable.getKeyColumns())) {
                localTable.setKeyColumns(keyColumns);
                updated = true;
            }
        }

        // 更新表模型
        if (tableCreateInfo.containsKey("tableModel")) {
            String tableModel = (String) tableCreateInfo.get("tableModel");
            if (!Objects.equals(tableModel, localTable.getTableModel())) {
                localTable.setTableModel(tableModel);
                updated = true;
            }
        }

        // 更新 DDL
        if (tableCreateInfo.containsKey("createTableSql")) {
            String createTableSql = (String) tableCreateInfo.get("createTableSql");
            if (!Objects.equals(createTableSql, localTable.getDorisDdl())) {
                localTable.setDorisDdl(createTableSql);
                updated = true;
            }
        }

        // 更新统计信息
        Long tableRows = (Long) dorisTable.get("tableRows");
        if (tableRows != null && !Objects.equals(tableRows, localTable.getRowCount())) {
            localTable.setRowCount(tableRows);
            updated = true;
        }

        Long dataLength = (Long) dorisTable.get("dataLength");
        if (dataLength != null && !Objects.equals(dataLength, localTable.getStorageSize())) {
            localTable.setStorageSize(dataLength);
            updated = true;
        }

        Timestamp updateTime = (Timestamp) dorisTable.get("updateTime");
        if (updateTime != null) {
            LocalDateTime updateDateTime = updateTime.toLocalDateTime();
            if (!Objects.equals(updateDateTime, localTable.getLastUpdated())) {
                localTable.setLastUpdated(updateDateTime);
                updated = true;
            }
        }

        // 更新同步状态
        localTable.setIsSynced(1);
        localTable.setSyncTime(LocalDateTime.now());

        if (updated) {
            dataTableMapper.updateById(localTable);
            result.addUpdatedTable();
        }

        // 同步字段（增量更新）
        syncTableFieldsIncremental(localTable.getId(), columns, result);
    }

    /**
     * 同步表字段（全量插入，用于新表）
     */
    private void syncTableFields(Long tableId, List<Map<String, Object>> columns, SyncResult result) {
        for (Map<String, Object> column : columns) {
            DataField field = new DataField();
            field.setTableId(tableId);
            field.setFieldName((String) column.get("columnName"));
            field.setFieldType((String) column.get("dataType"));
            field.setFieldComment((String) column.get("columnComment"));
            field.setIsNullable((Integer) column.get("isNullable"));
            field.setIsPrimary((Integer) column.get("isPrimary"));
            field.setDefaultValue((String) column.get("defaultValue"));
            field.setFieldOrder((Integer) column.get("ordinalPosition"));

            dataFieldMapper.insert(field);
            result.addNewField();
        }
    }

    /**
     * 同步表字段（增量更新，用于已存在的表）
     */
    private void syncTableFieldsIncremental(Long tableId, List<Map<String, Object>> dorisColumns, SyncResult result) {
        // 获取本地已存在的字段
        List<DataField> localFields = dataFieldMapper.selectList(
            new LambdaQueryWrapper<DataField>()
                .eq(DataField::getTableId, tableId)
        );

        Map<String, DataField> localFieldMap = localFields.stream()
            .collect(Collectors.toMap(DataField::getFieldName, f -> f));

        Set<String> dorisFieldNames = new HashSet<>();

        // 遍历 Doris 中的字段
        for (Map<String, Object> dorisColumn : dorisColumns) {
            String fieldName = (String) dorisColumn.get("columnName");
            dorisFieldNames.add(fieldName);

            DataField localField = localFieldMap.get(fieldName);

            if (localField == null) {
                // 新字段：插入
                DataField newField = new DataField();
                newField.setTableId(tableId);
                newField.setFieldName(fieldName);
                newField.setFieldType((String) dorisColumn.get("dataType"));
                newField.setFieldComment((String) dorisColumn.get("columnComment"));
                newField.setIsNullable((Integer) dorisColumn.get("isNullable"));
                newField.setIsPrimary((Integer) dorisColumn.get("isPrimary"));
                newField.setDefaultValue((String) dorisColumn.get("defaultValue"));
                newField.setFieldOrder((Integer) dorisColumn.get("ordinalPosition"));

                dataFieldMapper.insert(newField);
                result.addNewField();
            } else {
                // 已存在字段：更新
                boolean updated = false;

                String dataType = (String) dorisColumn.get("dataType");
                if (!Objects.equals(dataType, localField.getFieldType())) {
                    localField.setFieldType(dataType);
                    updated = true;
                }

                String columnComment = (String) dorisColumn.get("columnComment");
                if (!Objects.equals(columnComment, localField.getFieldComment())) {
                    localField.setFieldComment(columnComment);
                    updated = true;
                }

                Integer isNullable = (Integer) dorisColumn.get("isNullable");
                if (!Objects.equals(isNullable, localField.getIsNullable())) {
                    localField.setIsNullable(isNullable);
                    updated = true;
                }

                Integer isPrimary = (Integer) dorisColumn.get("isPrimary");
                if (!Objects.equals(isPrimary, localField.getIsPrimary())) {
                    localField.setIsPrimary(isPrimary);
                    updated = true;
                }

                String defaultValue = (String) dorisColumn.get("defaultValue");
                if (!Objects.equals(defaultValue, localField.getDefaultValue())) {
                    localField.setDefaultValue(defaultValue);
                    updated = true;
                }

                Integer ordinalPosition = (Integer) dorisColumn.get("ordinalPosition");
                if (!Objects.equals(ordinalPosition, localField.getFieldOrder())) {
                    localField.setFieldOrder(ordinalPosition);
                    updated = true;
                }

                if (updated) {
                    dataFieldMapper.updateById(localField);
                    result.addUpdatedField();
                }
            }
        }

        // 处理冗余字段（在本地存在但在 Doris 中不存在的字段）
        for (DataField localField : localFields) {
            if (!dorisFieldNames.contains(localField.getFieldName())) {
                // 逻辑删除
                dataFieldMapper.deleteById(localField.getId());
                result.addDeletedField();
                log.info("Logically deleted field: {} from table {}", localField.getFieldName(), tableId);
            }
        }
    }

    /**
     * 同步指定表的元数据
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncTable(Long clusterId, String database, String tableName) {
        SyncResult result = new SyncResult();
        log.info("Syncing table: {}.{}", database, tableName);

        try {
            // 获取 Doris 中的表信息
            List<Map<String, Object>> tables = dorisConnectionService.getTablesInDatabase(clusterId, database);
            Map<String, Object> dorisTable = tables.stream()
                .filter(t -> tableName.equals(t.get("tableName")))
                .findFirst()
                .orElse(null);

            if (dorisTable == null) {
                result.addError("表 " + database + "." + tableName + " 在 Doris 中不存在");
                return result;
            }

            // 获取本地表
            DataTable localTable = dataTableMapper.selectOne(
                new LambdaQueryWrapper<DataTable>()
                    .eq(DataTable::getDbName, database)
                    .eq(DataTable::getTableName, tableName)
            );

            if (localTable == null) {
                syncNewTable(clusterId, database, tableName, dorisTable, result);
            } else {
                syncExistingTable(clusterId, database, tableName, dorisTable, localTable, result);
            }

            log.info("Table sync completed: {}", result);
        } catch (Exception e) {
            log.error("Failed to sync table {}.{}", database, tableName, e);
            result.addError("同步表失败: " + e.getMessage());
        }

        return result;
    }
}
