-- OpenDataWorks 数据库初始化脚本
-- 此脚本会在 MySQL 容器首次启动时自动执行

-- 创建数据库
CREATE DATABASE IF NOT EXISTS onedata_portal DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE onedata_portal;

-- 导入主表结构和数据
SOURCE /docker-entrypoint-initdb.d/schema.sql;
SOURCE /docker-entrypoint-initdb.d/inspection_schema.sql;
SOURCE /docker-entrypoint-initdb.d/sample_data.sql;

-- 创建应用用户
CREATE USER IF NOT EXISTS 'onedata'@'%' IDENTIFIED BY 'onedata123';
GRANT ALL PRIVILEGES ON onedata_portal.* TO 'onedata'@'%';
FLUSH PRIVILEGES;

SELECT '✅ OpenDataWorks 数据库初始化完成！' AS Status;
