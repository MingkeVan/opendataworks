-- OpenDataWorks 数据库引导脚本
-- 与 docker-entrypoint-initdb.d 内的其他脚本配合执行

-- 创建数据库
CREATE DATABASE IF NOT EXISTS onedata_portal DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE onedata_portal;

-- 创建应用用户
CREATE USER IF NOT EXISTS 'onedata'@'%' IDENTIFIED BY 'onedata123';
GRANT ALL PRIVILEGES ON onedata_portal.* TO 'onedata'@'%';
FLUSH PRIVILEGES;

SELECT '✅ OpenDataWorks 数据库初始化完成！' AS Status;
