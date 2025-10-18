-- ================================================================
SET NAMES utf8mb4;

-- 1. 扩展 data_table 表 - 增加规范化命名和 Doris 配置字段
ALTER TABLE data_table
ADD COLUMN IF NOT EXISTS business_domain VARCHAR(50) COMMENT '业务域' AFTER layer,
ADD COLUMN IF NOT EXISTS data_domain VARCHAR(50) COMMENT '数据域' AFTER business_domain,
ADD COLUMN IF NOT EXISTS custom_identifier VARCHAR(100) COMMENT '自定义表标识' AFTER data_domain,
ADD COLUMN IF NOT EXISTS statistics_cycle VARCHAR(20) COMMENT '统计周期(如: 10m, 1h, 1d)' AFTER custom_identifier,
ADD COLUMN IF NOT EXISTS update_type VARCHAR(10) COMMENT '更新类型(di/df/hi/hf/ri)' AFTER statistics_cycle,
ADD COLUMN IF NOT EXISTS table_model VARCHAR(20) COMMENT 'Doris表模型(DUPLICATE/AGGREGATE/UNIQUE)' AFTER update_type,
ADD COLUMN IF NOT EXISTS bucket_num INT COMMENT '分桶数' AFTER table_model,
ADD COLUMN IF NOT EXISTS replica_num INT DEFAULT 1 COMMENT '副本数' AFTER bucket_num,
ADD COLUMN IF NOT EXISTS partition_column VARCHAR(100) COMMENT '分区字段' AFTER replica_num,
ADD COLUMN IF NOT EXISTS distribution_column VARCHAR(100) COMMENT '分桶字段' AFTER partition_column,
ADD COLUMN IF NOT EXISTS key_columns VARCHAR(500) COMMENT '主键列(逗号分隔)' AFTER distribution_column,
ADD COLUMN IF NOT EXISTS doris_ddl TEXT COMMENT '生成的Doris DDL' AFTER key_columns,
ADD COLUMN IF NOT EXISTS is_synced TINYINT DEFAULT 0 COMMENT '是否已同步到Doris(0-未同步,1-已同步)' AFTER doris_ddl,
ADD COLUMN IF NOT EXISTS sync_time DATETIME COMMENT 'Doris同步时间' AFTER is_synced;

-- 1.1 扩展 data_task 表 - 增加 Dolphin 节点信息
ALTER TABLE data_task
ADD COLUMN IF NOT EXISTS dolphin_task_code BIGINT COMMENT 'DolphinScheduler任务编码' AFTER dolphin_schedule_id,
ADD COLUMN IF NOT EXISTS dolphin_task_version INT DEFAULT 1 COMMENT 'DolphinScheduler任务版本' AFTER dolphin_task_code,
ADD COLUMN IF NOT EXISTS dolphin_node_type VARCHAR(20) COMMENT 'Dolphin节点类型(SHELL/SQL/PYTHON/SPARK/FLINK)' AFTER dolphin_task_version,
ADD COLUMN IF NOT EXISTS datasource_name VARCHAR(100) COMMENT 'SQL节点数据源名称' AFTER dolphin_node_type,
ADD COLUMN IF NOT EXISTS datasource_type VARCHAR(20) COMMENT '数据源类型(MYSQL/DORIS等)' AFTER datasource_name;

-- 2. 创建 business_domain 表 - 业务域配置
CREATE TABLE IF NOT EXISTS business_domain (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  domain_code VARCHAR(50) NOT NULL COMMENT '业务域代码',
  domain_name VARCHAR(100) NOT NULL COMMENT '业务域名称',
  description VARCHAR(500) COMMENT '描述',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_domain_code (domain_code)
) COMMENT '业务域配置表';

-- 3. 创建 data_domain 表 - 数据域配置
CREATE TABLE IF NOT EXISTS data_domain (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  domain_code VARCHAR(50) NOT NULL COMMENT '数据域代码',
  domain_name VARCHAR(100) NOT NULL COMMENT '数据域名称',
  business_domain VARCHAR(50) COMMENT '所属业务域',
  description VARCHAR(500) COMMENT '描述',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_domain_code (domain_code)
) COMMENT '数据域配置表';

-- 4. 创建 doris_cluster 表 - Doris集群配置
CREATE TABLE IF NOT EXISTS doris_cluster (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cluster_name VARCHAR(100) NOT NULL COMMENT '集群名称',
  fe_host VARCHAR(100) NOT NULL COMMENT 'FE Host',
  fe_port INT NOT NULL DEFAULT 9030 COMMENT 'FE MySQL端口',
  username VARCHAR(50) NOT NULL COMMENT '用户名',
  password VARCHAR(200) NOT NULL COMMENT '密码',
  is_default TINYINT DEFAULT 0 COMMENT '是否默认集群',
  status VARCHAR(20) DEFAULT 'active' COMMENT '状态',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
) COMMENT 'Doris集群配置表';

-- 5. data_lineage 表已包含必要字段,无需修改
-- (task_id, upstream_table_id, downstream_table_id, lineage_type 已存在)

-- 6. 创建 table_task_relation 表 - 表与任务关联关系
CREATE TABLE IF NOT EXISTS table_task_relation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  table_id BIGINT NOT NULL COMMENT '表ID',
  task_id BIGINT NOT NULL COMMENT '任务ID',
  relation_type VARCHAR(20) NOT NULL COMMENT '关联类型(read-读取/write-写入)',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_table_task (table_id, task_id, relation_type)
) COMMENT '表与任务关联关系表';

-- 7. 初始化业务域数据
INSERT INTO business_domain (domain_code, domain_name, description) VALUES
('tech', '技术域', '技术相关的业务域,包括研发、运维等')
ON DUPLICATE KEY UPDATE
  domain_name = VALUES(domain_name),
  description = VALUES(description);

-- 8. 初始化数据域数据
INSERT INTO data_domain (domain_code, domain_name, business_domain, description) VALUES
('dev', '研发域', 'tech', '研发相关数据,包括代码、项目、需求等'),
('ops', '运维域', 'tech', '运维相关数据,包括监控、告警、性能等'),
('public', '公共域', 'tech', '公共数据,如字典、配置等')
ON DUPLICATE KEY UPDATE
  domain_name = VALUES(domain_name),
  business_domain = VALUES(business_domain),
  description = VALUES(description);

-- 9. 初始化 Doris 集群配置 (本地 Docker)
INSERT INTO doris_cluster (cluster_name, fe_host, fe_port, username, password, is_default, status) VALUES
('本地开发集群', 'localhost', 9030, 'root', '', 1, 'active')
ON DUPLICATE KEY UPDATE fe_host = VALUES(fe_host);
