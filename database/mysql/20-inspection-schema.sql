SET NAMES utf8mb4;
USE onedata_portal;

-- 巡检记录表
CREATE TABLE IF NOT EXISTS inspection_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    inspection_type VARCHAR(50) NOT NULL COMMENT '巡检类型: table_naming, replica_count, tablet_count, task_failure, best_practice',
    inspection_time DATETIME NOT NULL COMMENT '巡检时间',
    trigger_type VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT '触发类型: manual, schedule',
    total_items INT DEFAULT 0 COMMENT '检查项总数',
    issue_count INT DEFAULT 0 COMMENT '问题数量',
    status VARCHAR(20) DEFAULT 'running' COMMENT '状态: running, completed, failed',
    duration_seconds INT COMMENT '执行时长(秒)',
    created_by VARCHAR(50) COMMENT '创建人',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_inspection_time (inspection_time),
    INDEX idx_inspection_type (inspection_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡检记录表';

-- 巡检问题表
CREATE TABLE IF NOT EXISTS inspection_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    record_id BIGINT NOT NULL COMMENT '巡检记录ID',
    issue_type VARCHAR(50) NOT NULL COMMENT '问题类型',
    severity VARCHAR(20) NOT NULL COMMENT '严重程度: critical, high, medium, low',
    resource_type VARCHAR(50) NOT NULL COMMENT '资源类型: table, task',
    resource_id BIGINT COMMENT '资源ID',
    resource_name VARCHAR(200) COMMENT '资源名称',
    issue_description TEXT COMMENT '问题描述',
    current_value VARCHAR(500) COMMENT '当前值',
    expected_value VARCHAR(500) COMMENT '期望值',
    suggestion TEXT COMMENT '建议',
    status VARCHAR(20) DEFAULT 'open' COMMENT '状态: open, acknowledged, resolved, ignored',
    resolved_by VARCHAR(50) COMMENT '解决人',
    resolved_time DATETIME COMMENT '解决时间',
    resolution_note TEXT COMMENT '解决说明',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_record_id (record_id),
    INDEX idx_issue_type (issue_type),
    INDEX idx_severity (severity),
    INDEX idx_status (status),
    INDEX idx_resource (resource_type, resource_id),
    FOREIGN KEY (record_id) REFERENCES inspection_record(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡检问题表';

-- 巡检规则配置表
CREATE TABLE IF NOT EXISTS inspection_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    rule_code VARCHAR(50) NOT NULL UNIQUE COMMENT '规则代码',
    rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(50) NOT NULL COMMENT '规则类型',
    severity VARCHAR(20) NOT NULL COMMENT '严重程度',
    description TEXT COMMENT '规则描述',
    rule_config JSON COMMENT '规则配置(JSON格式)',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_by VARCHAR(50) COMMENT '创建人',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_rule_type (rule_type),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡检规则配置表';

-- 插入默认巡检规则
INSERT INTO inspection_rule (rule_code, rule_name, rule_type, severity, description, rule_config, enabled) VALUES
-- 表命名规范
('TABLE_NAMING_CONVENTION', '表命名规范检查', 'table_naming', 'medium', '检查表名是否符合命名规范',
 '{"pattern": "^(ods|dwd|dim|dws|ads)_[a-z][a-z0-9_]*$", "errorMessage": "表名应遵循 {layer}_xxx_xxx 格式"}', 1),

-- 副本数检查
('REPLICA_COUNT_CHECK', '副本数检查', 'replica_count', 'high', '检查表的副本数是否符合最佳实践',
 '{"minReplicas": 1, "maxReplicas": 3, "recommendedReplicas": 3}', 1),

-- Tablet 数量检查
('TABLET_COUNT_CHECK', 'Tablet数量检查', 'tablet_count', 'high', '检查表的tablet数量是否过多',
 '{"maxTablets": 200, "warningTablets": 100}', 1),

-- 表无owner检查
('TABLE_OWNER_CHECK', '表负责人检查', 'table_owner', 'medium', '检查表是否配置了负责人',
 '{}', 1),

-- 表无注释检查
('TABLE_COMMENT_CHECK', '表注释检查', 'table_comment', 'low', '检查表是否有注释说明',
 '{}', 1),

-- 任务失败检查
('TASK_FAILURE_CHECK', '任务失败检查', 'task_failure', 'critical', '检查最近失败的任务',
 '{"checkDays": 1, "maxFailures": 3}', 1),

-- 任务无调度检查
('TASK_SCHEDULE_CHECK', '任务调度检查', 'task_schedule', 'medium', '检查已发布但长期未执行的任务',
 '{"checkDays": 7}', 1),

-- 数据层级检查
('TABLE_LAYER_CHECK', '数据层级检查', 'table_layer', 'medium', '检查表的数据层级是否正确配置',
 '{"validLayers": ["ODS", "DWD", "DIM", "DWS", "ADS"]}', 1);
