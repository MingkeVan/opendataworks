-- 创建表统计历史记录表
CREATE TABLE IF NOT EXISTS `table_statistics_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `table_id` BIGINT NOT NULL COMMENT '关联的表ID',
    `cluster_id` BIGINT DEFAULT NULL COMMENT 'Doris集群ID',
    `database_name` VARCHAR(100) NOT NULL COMMENT '数据库名',
    `table_name` VARCHAR(100) NOT NULL COMMENT '表名',
    `row_count` BIGINT DEFAULT 0 COMMENT '数据行数',
    `data_size` BIGINT DEFAULT 0 COMMENT '数据大小(字节)',
    `partition_count` INT DEFAULT 0 COMMENT '分区数量',
    `replication_num` INT DEFAULT NULL COMMENT '副本数量',
    `bucket_num` INT DEFAULT NULL COMMENT '分桶数量',
    `table_last_update_time` DATETIME DEFAULT NULL COMMENT '表最后更新时间(来自Doris)',
    `statistics_time` DATETIME NOT NULL COMMENT '统计时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_table_id` (`table_id`),
    KEY `idx_statistics_time` (`statistics_time`),
    KEY `idx_table_stats` (`table_id`, `statistics_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表统计历史记录';
