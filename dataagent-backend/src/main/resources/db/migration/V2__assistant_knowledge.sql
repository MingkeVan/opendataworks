SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `aq_meta_table` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `snapshot_version` VARCHAR(64) NOT NULL,
    `table_id` BIGINT NOT NULL,
    `cluster_id` BIGINT DEFAULT NULL,
    `db_name` VARCHAR(128) DEFAULT NULL,
    `table_name` VARCHAR(255) NOT NULL,
    `table_comment` TEXT DEFAULT NULL,
    `payload_json` MEDIUMTEXT DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_aq_meta_table_version_table` (`snapshot_version`, `table_id`),
    KEY `idx_aq_meta_table_db_name` (`db_name`, `table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant metadata table snapshot';

CREATE TABLE IF NOT EXISTS `aq_meta_field` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `snapshot_version` VARCHAR(64) NOT NULL,
    `field_id` BIGINT NOT NULL,
    `table_id` BIGINT NOT NULL,
    `field_name` VARCHAR(255) NOT NULL,
    `field_type` VARCHAR(255) DEFAULT NULL,
    `field_comment` TEXT DEFAULT NULL,
    `payload_json` MEDIUMTEXT DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_aq_meta_field_version_table` (`snapshot_version`, `table_id`),
    KEY `idx_aq_meta_field_name` (`field_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant metadata field snapshot';

CREATE TABLE IF NOT EXISTS `aq_meta_lineage_edge` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `snapshot_version` VARCHAR(64) NOT NULL,
    `lineage_id` BIGINT DEFAULT NULL,
    `task_id` BIGINT DEFAULT NULL,
    `upstream_table_id` BIGINT NOT NULL,
    `downstream_table_id` BIGINT NOT NULL,
    `lineage_type` VARCHAR(32) DEFAULT NULL,
    `payload_json` MEDIUMTEXT DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_aq_meta_lineage_version` (`snapshot_version`),
    KEY `idx_aq_meta_lineage_edge` (`upstream_table_id`, `downstream_table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant metadata lineage snapshot';

CREATE TABLE IF NOT EXISTS `aq_knowledge_semantic` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `version_tag` VARCHAR(64) NOT NULL,
    `domain_name` VARCHAR(128) DEFAULT NULL,
    `table_name` VARCHAR(255) DEFAULT NULL,
    `field_name` VARCHAR(255) DEFAULT NULL,
    `business_name` VARCHAR(255) NOT NULL,
    `synonyms` TEXT DEFAULT NULL,
    `description` TEXT DEFAULT NULL,
    `enabled` TINYINT DEFAULT 1,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_aq_semantic_version_enabled` (`version_tag`, `enabled`),
    KEY `idx_aq_semantic_business_name` (`business_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant semantic knowledge';

CREATE TABLE IF NOT EXISTS `aq_knowledge_business` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `version_tag` VARCHAR(64) NOT NULL,
    `term` VARCHAR(255) NOT NULL,
    `synonyms` TEXT DEFAULT NULL,
    `definition` MEDIUMTEXT DEFAULT NULL,
    `enabled` TINYINT DEFAULT 1,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_aq_business_version_enabled` (`version_tag`, `enabled`),
    KEY `idx_aq_business_term` (`term`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant business glossary';

CREATE TABLE IF NOT EXISTS `aq_knowledge_qa` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `version_tag` VARCHAR(64) NOT NULL,
    `question` TEXT NOT NULL,
    `answer` MEDIUMTEXT NOT NULL,
    `tags` VARCHAR(512) DEFAULT NULL,
    `enabled` TINYINT DEFAULT 1,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_aq_qa_version_enabled` (`version_tag`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant QA knowledge';

CREATE TABLE IF NOT EXISTS `aq_knowledge_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `version_tag` VARCHAR(64) NOT NULL,
    `meta_hash` VARCHAR(128) DEFAULT NULL,
    `semantic_hash` VARCHAR(128) DEFAULT NULL,
    `business_hash` VARCHAR(128) DEFAULT NULL,
    `qa_hash` VARCHAR(128) DEFAULT NULL,
    `source` VARCHAR(64) DEFAULT 'assistantctl',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_aq_knowledge_version` (`version_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant knowledge version';
