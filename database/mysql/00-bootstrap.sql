-- OpenDataWorks 数据库引导脚本
-- 与 docker-entrypoint-initdb.d 内的其他脚本配合执行

-- 创建数据库
CREATE DATABASE IF NOT EXISTS opendataworks DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE opendataworks;

-- 创建应用用户
CREATE USER IF NOT EXISTS 'opendataworks'@'%' IDENTIFIED BY 'opendataworks123';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'%';
FLUSH PRIVILEGES;

SELECT '✅ OpenDataWorks 数据库初始化完成！' AS Status;
