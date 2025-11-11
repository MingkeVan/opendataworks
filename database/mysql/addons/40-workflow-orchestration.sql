-- -----------------------------------------------------
-- Workflow orchestration core schema (Phase 1)
-- -----------------------------------------------------

CREATE TABLE IF NOT EXISTS `data_workflow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `workflow_code` BIGINT DEFAULT NULL COMMENT 'Dolphin workflow code',
    `project_code` BIGINT DEFAULT NULL COMMENT 'Dolphin project code',
    `workflow_name` VARCHAR(200) NOT NULL COMMENT '工作流名称',
    `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '平台状态 draft/ready/online/offline/archived',
    `publish_status` VARCHAR(32) NOT NULL DEFAULT 'never' COMMENT '最近一次发布状态 never/published/failed',
    `current_version_id` BIGINT DEFAULT NULL COMMENT '当前版本ID',
    `last_published_version_id` BIGINT DEFAULT NULL COMMENT '最近一次发布版本',
    `definition_json` JSON NULL COMMENT '当前 DAG 定义',
    `entry_task_ids` JSON NULL COMMENT '入口任务ID集合',
    `exit_task_ids` JSON NULL COMMENT '出口任务ID集合',
    `description` VARCHAR(500) DEFAULT NULL,
    `created_by` VARCHAR(64) DEFAULT NULL,
    `updated_by` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_name` (`workflow_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台工作流定义';

CREATE TABLE IF NOT EXISTS `workflow_task_relation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` BIGINT NOT NULL,
    `task_id` BIGINT NOT NULL,
    `node_attrs` JSON NULL COMMENT '节点属性（worker group、优先级等）',
    `is_entry` TINYINT(1) NOT NULL DEFAULT 0,
    `is_exit` TINYINT(1) NOT NULL DEFAULT 0,
    `version_id` BIGINT DEFAULT NULL,
    `upstream_task_count` INT DEFAULT 0,
    `downstream_task_count` INT DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task` (`task_id`),
    KEY `idx_workflow` (`workflow_id`),
    CONSTRAINT `fk_relation_workflow` FOREIGN KEY (`workflow_id`) REFERENCES `data_workflow` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务与工作流关联';

CREATE TABLE IF NOT EXISTS `workflow_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` BIGINT NOT NULL,
    `version_no` INT NOT NULL,
    `structure_snapshot` JSON NOT NULL COMMENT '完整结构快照',
    `change_summary` VARCHAR(1000) DEFAULT NULL,
    `trigger_source` VARCHAR(64) DEFAULT NULL,
    `created_by` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_version` (`workflow_id`, `version_no`),
    KEY `idx_workflow_version` (`workflow_id`),
    CONSTRAINT `fk_version_workflow` FOREIGN KEY (`workflow_id`) REFERENCES `data_workflow` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流版本记录';

CREATE TABLE IF NOT EXISTS `workflow_publish_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` BIGINT NOT NULL,
    `version_id` BIGINT NOT NULL,
    `target_engine` VARCHAR(32) NOT NULL DEFAULT 'dolphin',
    `operation` VARCHAR(32) NOT NULL COMMENT 'deploy/online/offline',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending',
    `engine_workflow_code` BIGINT DEFAULT NULL,
    `log` JSON NULL,
    `operator` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_publish_workflow` (`workflow_id`),
    CONSTRAINT `fk_publish_workflow` FOREIGN KEY (`workflow_id`) REFERENCES `data_workflow` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流发布记录';

CREATE TABLE IF NOT EXISTS `workflow_instance_cache` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` BIGINT NOT NULL,
    `instance_id` BIGINT NOT NULL,
    `state` VARCHAR(32) NOT NULL,
    `start_time` DATETIME DEFAULT NULL,
    `end_time` DATETIME DEFAULT NULL,
    `trigger_type` VARCHAR(32) DEFAULT NULL,
    `duration_ms` BIGINT DEFAULT NULL,
    `extra` JSON NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_instance_workflow` (`workflow_id`),
    CONSTRAINT `fk_instance_workflow` FOREIGN KEY (`workflow_id`) REFERENCES `data_workflow` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流执行历史缓存（最近N次）';
