-- 创建数据库
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS data_portal DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE data_portal;

-- 数据表管理表
CREATE TABLE IF NOT EXISTS `data_table` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `table_name` VARCHAR(100) NOT NULL COMMENT '表名',
    `table_comment` VARCHAR(500) DEFAULT NULL COMMENT '表描述',
    `layer` ENUM('ODS', 'DWD', 'DIM', 'DWS', 'ADS') NOT NULL COMMENT '数据层级',
    `db_name` VARCHAR(100) DEFAULT NULL COMMENT '数据库名',
    `owner` VARCHAR(50) DEFAULT NULL COMMENT '负责人',
    `status` ENUM('active', 'inactive', 'deprecated') DEFAULT 'active' COMMENT '状态',
    `lifecycle_days` INT DEFAULT NULL COMMENT '生命周期(天)',
    `partition_field` VARCHAR(50) DEFAULT NULL COMMENT '分区字段',
    `storage_size` BIGINT DEFAULT 0 COMMENT '存储大小(字节)',
    `row_count` BIGINT DEFAULT 0 COMMENT '行数',
    `last_updated` DATETIME DEFAULT NULL COMMENT '最后更新时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_name` (`table_name`),
    KEY `idx_layer` (`layer`),
    KEY `idx_owner` (`owner`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据表元信息';

-- 字段定义表
CREATE TABLE IF NOT EXISTS `data_field` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `table_id` BIGINT NOT NULL COMMENT '所属表ID',
    `field_name` VARCHAR(100) NOT NULL COMMENT '字段名',
    `field_type` VARCHAR(50) NOT NULL COMMENT '字段类型',
    `field_comment` VARCHAR(500) DEFAULT NULL COMMENT '字段描述',
    `is_nullable` TINYINT DEFAULT 1 COMMENT '是否可为空',
    `is_partition` TINYINT DEFAULT 0 COMMENT '是否分区字段',
    `is_primary` TINYINT DEFAULT 0 COMMENT '是否主键',
    `default_value` VARCHAR(200) DEFAULT NULL COMMENT '默认值',
    `field_order` INT DEFAULT 0 COMMENT '字段排序',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_table_id` (`table_id`),
    UNIQUE KEY `uk_table_field` (`table_id`, `field_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段定义';

-- 任务定义表
CREATE TABLE IF NOT EXISTS `data_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    `task_code` VARCHAR(100) NOT NULL COMMENT '任务编码',
    `task_type` ENUM('batch', 'stream') DEFAULT 'batch' COMMENT '任务类型',
    `engine` ENUM('dolphin', 'dinky') DEFAULT 'dolphin' COMMENT '执行引擎',
    `task_sql` TEXT NOT NULL COMMENT '任务SQL',
    `task_desc` VARCHAR(1000) DEFAULT NULL COMMENT '任务描述',
    `schedule_cron` VARCHAR(100) DEFAULT NULL COMMENT '调度CRON表达式',
    `priority` INT DEFAULT 5 COMMENT '优先级(1-10)',
    `timeout_seconds` INT DEFAULT 3600 COMMENT '超时时间(秒)',
    `retry_times` INT DEFAULT 0 COMMENT '重试次数',
    `retry_interval` INT DEFAULT 60 COMMENT '重试间隔(秒)',
    `owner` VARCHAR(50) DEFAULT NULL COMMENT '负责人',
    `status` ENUM('draft', 'published', 'running', 'paused', 'failed') DEFAULT 'draft' COMMENT '状态',
    `dolphin_process_code` BIGINT DEFAULT NULL COMMENT 'DolphinScheduler流程编码',
    `dolphin_schedule_id` BIGINT DEFAULT NULL COMMENT 'DolphinScheduler调度ID',
    `dolphin_task_code` BIGINT DEFAULT NULL COMMENT 'DolphinScheduler任务编码',
    `dolphin_task_version` INT DEFAULT 1 COMMENT 'DolphinScheduler任务版本',
    `dinky_job_id` BIGINT DEFAULT NULL COMMENT 'Dinky作业ID',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_code` (`task_code`),
    KEY `idx_task_type` (`task_type`),
    KEY `idx_engine` (`engine`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务定义';

-- 血缘关系表
CREATE TABLE IF NOT EXISTS `data_lineage` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `upstream_table_id` BIGINT DEFAULT NULL COMMENT '上游表ID',
    `downstream_table_id` BIGINT DEFAULT NULL COMMENT '下游表ID',
    `lineage_type` ENUM('input', 'output') NOT NULL COMMENT '血缘类型',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_upstream` (`upstream_table_id`),
    KEY `idx_downstream` (`downstream_table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='血缘关系';

-- 任务执行日志表
CREATE TABLE IF NOT EXISTS `task_execution_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `execution_id` VARCHAR(100) DEFAULT NULL COMMENT '外部执行ID',
    `status` ENUM('pending', 'running', 'success', 'failed', 'killed') DEFAULT 'pending' COMMENT '执行状态',
    `start_time` DATETIME DEFAULT NULL COMMENT '开始时间',
    `end_time` DATETIME DEFAULT NULL COMMENT '结束时间',
    `duration_seconds` INT DEFAULT NULL COMMENT '执行时长(秒)',
    `rows_output` BIGINT DEFAULT 0 COMMENT '输出行数',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `log_url` VARCHAR(500) DEFAULT NULL COMMENT '日志链接',
    `trigger_type` ENUM('manual', 'schedule', 'api') DEFAULT 'schedule' COMMENT '触发方式',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_status` (`status`),
    KEY `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务执行日志';

-- 插入测试数据
-- ODS层示例表
INSERT INTO `data_table` (`table_name`, `table_comment`, `layer`, `db_name`, `owner`, `status`)
VALUES ('ods_user', '用户原始数据表', 'ODS', 'doris_ods', 'admin', 'active')
ON DUPLICATE KEY UPDATE
    `table_comment` = VALUES(`table_comment`),
    `layer` = VALUES(`layer`),
    `db_name` = VALUES(`db_name`),
    `owner` = VALUES(`owner`),
    `status` = VALUES(`status`);

INSERT INTO `data_table` (`table_name`, `table_comment`, `layer`, `db_name`, `owner`, `status`)
VALUES ('ods_order', '订单原始数据表', 'ODS', 'doris_ods', 'admin', 'active')
ON DUPLICATE KEY UPDATE
    `table_comment` = VALUES(`table_comment`),
    `layer` = VALUES(`layer`),
    `db_name` = VALUES(`db_name`),
    `owner` = VALUES(`owner`),
    `status` = VALUES(`status`);

-- DWD层示例表
INSERT INTO `data_table` (`table_name`, `table_comment`, `layer`, `db_name`, `owner`, `status`)
VALUES ('dwd_user', '用户明细数据表', 'DWD', 'doris_dwd', 'admin', 'active')
ON DUPLICATE KEY UPDATE
    `table_comment` = VALUES(`table_comment`),
    `layer` = VALUES(`layer`),
    `db_name` = VALUES(`db_name`),
    `owner` = VALUES(`owner`),
    `status` = VALUES(`status`);

INSERT INTO `data_table` (`table_name`, `table_comment`, `layer`, `db_name`, `owner`, `status`)
VALUES ('dwd_order', '订单明细数据表', 'DWD', 'doris_dwd', 'admin', 'active')
ON DUPLICATE KEY UPDATE
    `table_comment` = VALUES(`table_comment`),
    `layer` = VALUES(`layer`),
    `db_name` = VALUES(`db_name`),
    `owner` = VALUES(`owner`),
    `status` = VALUES(`status`);

-- DWS层示例表
INSERT INTO `data_table` (`table_name`, `table_comment`, `layer`, `db_name`, `owner`, `status`)
VALUES ('dws_user_daily', '用户日汇总表', 'DWS', 'doris_dws', 'admin', 'active')
ON DUPLICATE KEY UPDATE
    `table_comment` = VALUES(`table_comment`),
    `layer` = VALUES(`layer`),
    `db_name` = VALUES(`db_name`),
    `owner` = VALUES(`owner`),
    `status` = VALUES(`status`);
