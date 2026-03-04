SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `assistant_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID(UUID)',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
    `title` VARCHAR(255) DEFAULT NULL COMMENT '会话标题',
    `source_id` BIGINT DEFAULT NULL COMMENT '上下文数据源ID',
    `database_name` VARCHAR(128) DEFAULT NULL COMMENT '上下文数据库',
    `limit_profile` VARCHAR(32) DEFAULT 'text_answer' COMMENT '限流配置档位',
    `manual_limit` INT DEFAULT NULL COMMENT '手动limit',
    `mode` VARCHAR(16) DEFAULT 'need-confirm' COMMENT '执行模式',
    `status` VARCHAR(20) DEFAULT 'active' COMMENT '会话状态',
    `last_message_at` DATETIME DEFAULT NULL COMMENT '最近消息时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_assistant_session_id` (`session_id`),
    KEY `idx_assistant_session_user_updated` (`user_id`, `deleted`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能助理会话表';

CREATE TABLE IF NOT EXISTS `assistant_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
    `run_id` VARCHAR(64) DEFAULT NULL COMMENT '关联运行ID',
    `role_type` VARCHAR(16) NOT NULL COMMENT '角色(user/assistant/system)',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `intent` VARCHAR(32) DEFAULT NULL COMMENT '识别意图',
    `metadata_json` MEDIUMTEXT DEFAULT NULL COMMENT '消息附加信息',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_assistant_message_session_created` (`session_id`, `deleted`, `created_at`),
    KEY `idx_assistant_message_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能助理消息表';

CREATE TABLE IF NOT EXISTS `assistant_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` VARCHAR(64) NOT NULL COMMENT '运行ID(UUID)',
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
    `status` VARCHAR(24) NOT NULL COMMENT '状态',
    `intent` VARCHAR(32) DEFAULT NULL COMMENT '意图',
    `policy_mode` VARCHAR(16) DEFAULT 'need-confirm' COMMENT '策略模式',
    `request_context_json` MEDIUMTEXT DEFAULT NULL COMMENT '请求上下文',
    `pending_payload_json` MEDIUMTEXT DEFAULT NULL COMMENT '待审批执行载荷',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `started_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '启动时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_assistant_run_id` (`run_id`),
    KEY `idx_assistant_run_session_started` (`session_id`, `deleted`, `started_at`),
    KEY `idx_assistant_run_user_started` (`user_id`, `deleted`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能助理运行表';

CREATE TABLE IF NOT EXISTS `assistant_run_step` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` VARCHAR(64) NOT NULL COMMENT '运行ID',
    `step_order` INT NOT NULL COMMENT '步骤序号',
    `step_key` VARCHAR(64) NOT NULL COMMENT '步骤编码',
    `step_name` VARCHAR(128) NOT NULL COMMENT '步骤名称',
    `status` VARCHAR(24) NOT NULL COMMENT '步骤状态',
    `summary` TEXT DEFAULT NULL COMMENT '摘要',
    `detail_json` MEDIUMTEXT DEFAULT NULL COMMENT '详情JSON',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_assistant_run_step_run_order` (`run_id`, `deleted`, `step_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能助理运行步骤表';

CREATE TABLE IF NOT EXISTS `assistant_artifact` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` VARCHAR(64) NOT NULL COMMENT '运行ID',
    `artifact_type` VARCHAR(32) NOT NULL COMMENT '产物类型(sql/query_result/chart/task_draft)',
    `title` VARCHAR(255) DEFAULT NULL COMMENT '产物标题',
    `content_json` MEDIUMTEXT DEFAULT NULL COMMENT '产物JSON',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_assistant_artifact_run` (`run_id`, `deleted`, `created_at`),
    KEY `idx_assistant_artifact_type` (`artifact_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能助理产物表';

CREATE TABLE IF NOT EXISTS `assistant_skill_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
    `skill_key` VARCHAR(64) NOT NULL COMMENT '技能Key',
    `enabled` TINYINT DEFAULT 1 COMMENT '是否启用',
    `threshold_json` TEXT DEFAULT NULL COMMENT '阈值配置JSON',
    `version` INT DEFAULT 1 COMMENT '版本号',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_assistant_skill_user_key` (`user_id`, `skill_key`, `deleted`),
    KEY `idx_assistant_skill_user` (`user_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能助理技能规则表';

CREATE TABLE IF NOT EXISTS `assistant_policy_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
    `mode` VARCHAR(16) NOT NULL DEFAULT 'need-confirm' COMMENT '策略模式',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_assistant_policy_user` (`user_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能助理策略配置表';
