-- 导入时按 (project_code, workflow_code) 判定 Dolphin 运行态是否已被平台工作流占用。
-- 提交阶段该查询用 SELECT ... FOR UPDATE 把并发绑定同一运行态的导入串行化，
-- 没有这个索引就会退化成全表扫描并锁住整张表。
--
-- 刻意保持非唯一：data_workflow 是逻辑删除，被软删的行仍持有 workflow_code，
-- 唯一约束会让"删除后再重新关联同一运行态"直接失败。并发防护由 FOR UPDATE 的
-- 区间锁承担，跨环境重号则由查询里的 dolphin_config_id 过滤区分。
SET @has_runtime_index := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_workflow'
      AND INDEX_NAME = 'idx_data_workflow_runtime'
);

SET @sql_add_runtime_index := IF(
    @has_runtime_index = 0,
    'CREATE INDEX `idx_data_workflow_runtime` ON `data_workflow` (`project_code`, `workflow_code`)',
    'SELECT 1'
);

PREPARE stmt FROM @sql_add_runtime_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
