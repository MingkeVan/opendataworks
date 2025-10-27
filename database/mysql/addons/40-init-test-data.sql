-- ================================================================================
-- MySQL 测试数据初始化脚本（模拟 Doris）
-- 用于测试表统计信息、DDL、数据预览、数据导出、数据查询等功能
-- ================================================================================

USE opendataworks;

-- 1. 更新 Doris 集群配置，指向本地 MySQL（作为测试用）
UPDATE doris_cluster
SET
    cluster_name = 'MySQL测试集群',
    fe_host = 'opendataworks-mysql',  -- Docker 容器名
    fe_port = 3306,                  -- MySQL 端口
    username = 'root',
    password = 'root',
    is_default = 1,
    status = 'active'
WHERE id = 1;

-- 2. 创建测试数据库（模拟 Doris 中的数据库）
CREATE DATABASE IF NOT EXISTS doris_ods CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS doris_dwd CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS doris_dws CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 3. 在 doris_ods 中创建测试表并插入数据
USE doris_ods;

-- 3.1 ods_user 表（用户原始数据）
DROP TABLE IF EXISTS ods_user;
CREATE TABLE ods_user (
    user_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(100) NOT NULL COMMENT '用户名',
    email VARCHAR(255) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    gender TINYINT COMMENT '性别：0-未知，1-男，2-女',
    birthday DATE COMMENT '生日',
    register_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    last_login_time DATETIME COMMENT '最后登录时间',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_register_time (register_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户原始数据表';

-- 插入测试数据（100条）
INSERT INTO ods_user (username, email, phone, gender, birthday, register_time, last_login_time, status) VALUES
('张三', 'zhangsan@example.com', '13800138001', 1, '1990-05-15', '2023-01-10 10:00:00', '2024-01-15 14:30:00', 1),
('李四', 'lisi@example.com', '13800138002', 2, '1992-08-20', '2023-02-15 11:00:00', '2024-01-14 09:20:00', 1),
('王五', 'wangwu@example.com', '13800138003', 1, '1988-03-12', '2023-03-20 09:30:00', '2024-01-13 16:45:00', 1),
('赵六', 'zhaoliu@example.com', '13800138004', 2, '1995-11-08', '2023-04-05 14:20:00', '2024-01-12 11:10:00', 1),
('孙七', 'sunqi@example.com', '13800138005', 1, '1987-07-25', '2023-05-12 16:45:00', '2024-01-11 13:25:00', 1),
('周八', 'zhouba@example.com', '13800138006', 2, '1993-02-18', '2023-06-08 10:15:00', '2024-01-10 15:40:00', 1),
('吴九', 'wujiu@example.com', '13800138007', 1, '1991-09-30', '2023-07-14 13:30:00', '2024-01-09 10:55:00', 1),
('郑十', 'zhengshi@example.com', '13800138008', 2, '1989-12-05', '2023-08-20 15:00:00', '2024-01-08 12:30:00', 1),
('钱十一', 'qianshiyi@example.com', '13800138009', 1, '1994-04-22', '2023-09-05 11:45:00', '2024-01-07 14:15:00', 1),
('陈十二', 'chenshier@example.com', '13800138010', 2, '1986-06-17', '2023-10-10 09:20:00', '2024-01-06 16:50:00', 1);

-- 再插入90条数据
INSERT INTO ods_user (username, email, phone, gender, birthday, register_time, last_login_time, status)
SELECT
    CONCAT('用户', LPAD(user_id + 10, 4, '0')) as username,
    CONCAT('user', user_id + 10, '@example.com') as email,
    CONCAT('138', LPAD(user_id + 10, 8, '0')) as phone,
    (user_id % 2) + 1 as gender,
    DATE_ADD('1980-01-01', INTERVAL FLOOR(RAND() * 15000) DAY) as birthday,
    DATE_ADD('2023-01-01', INTERVAL FLOOR(RAND() * 365) DAY) as register_time,
    DATE_ADD('2024-01-01', INTERVAL FLOOR(RAND() * 20) DAY) as last_login_time,
    1 as status
FROM ods_user LIMIT 90;

-- 3.2 ods_order 表（订单原始数据）
DROP TABLE IF EXISTS ods_order;
CREATE TABLE ods_order (
    order_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    order_no VARCHAR(64) NOT NULL COMMENT '订单号',
    product_id BIGINT COMMENT '商品ID',
    product_name VARCHAR(255) COMMENT '商品名称',
    quantity INT DEFAULT 1 COMMENT '购买数量',
    price DECIMAL(10,2) COMMENT '单价',
    total_amount DECIMAL(12,2) COMMENT '订单总金额',
    order_status TINYINT DEFAULT 0 COMMENT '订单状态：0-待支付，1-已支付，2-已发货，3-已完成，4-已取消',
    payment_type TINYINT COMMENT '支付方式：1-支付宝，2-微信，3-银行卡',
    order_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    payment_time DATETIME COMMENT '支付时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_order_no (order_no),
    INDEX idx_order_time (order_time),
    INDEX idx_order_status (order_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单原始数据表';

-- 插入订单测试数据（200条）
INSERT INTO ods_order (user_id, order_no, product_id, product_name, quantity, price, total_amount, order_status, payment_type, order_time, payment_time) VALUES
(1, 'ORD202401001', 1001, 'iPhone 15 Pro', 1, 7999.00, 7999.00, 3, 1, '2024-01-01 10:30:00', '2024-01-01 10:35:00'),
(2, 'ORD202401002', 1002, 'MacBook Air M2', 1, 8999.00, 8999.00, 3, 2, '2024-01-01 14:20:00', '2024-01-01 14:25:00'),
(3, 'ORD202401003', 1003, 'AirPods Pro', 2, 1999.00, 3998.00, 3, 1, '2024-01-02 09:15:00', '2024-01-02 09:20:00'),
(1, 'ORD202401004', 1004, 'iPad Air', 1, 4799.00, 4799.00, 2, 2, '2024-01-02 16:40:00', '2024-01-02 16:45:00'),
(4, 'ORD202401005', 1005, 'Apple Watch S9', 1, 3199.00, 3199.00, 1, 1, '2024-01-03 11:25:00', '2024-01-03 11:30:00'),
(5, 'ORD202401006', 1001, 'iPhone 15 Pro', 1, 7999.00, 7999.00, 3, 2, '2024-01-03 15:50:00', '2024-01-03 15:55:00'),
(2, 'ORD202401007', 1006, 'Magic Keyboard', 1, 2399.00, 2399.00, 3, 1, '2024-01-04 10:10:00', '2024-01-04 10:15:00'),
(6, 'ORD202401008', 1007, 'HomePod mini', 2, 749.00, 1498.00, 0, NULL, '2024-01-04 13:30:00', NULL),
(7, 'ORD202401009', 1002, 'MacBook Air M2', 1, 8999.00, 8999.00, 3, 2, '2024-01-05 09:45:00', '2024-01-05 09:50:00'),
(8, 'ORD202401010', 1003, 'AirPods Pro', 1, 1999.00, 1999.00, 4, NULL, '2024-01-05 14:20:00', NULL);

-- 批量生成更多订单数据
INSERT INTO ods_order (user_id, order_no, product_id, product_name, quantity, price, total_amount, order_status, payment_type, order_time, payment_time)
SELECT
    (order_id % 10) + 1 as user_id,
    CONCAT('ORD2024', LPAD(order_id + 10, 6, '0')) as order_no,
    1000 + (order_id % 7) + 1 as product_id,
    CONCAT('商品', 1000 + (order_id % 7) + 1) as product_name,
    FLOOR(1 + RAND() * 3) as quantity,
    ROUND(100 + RAND() * 9900, 2) as price,
    ROUND((100 + RAND() * 9900) * (1 + RAND() * 2), 2) as total_amount,
    FLOOR(RAND() * 5) as order_status,
    FLOOR(1 + RAND() * 3) as payment_type,
    DATE_ADD('2024-01-01', INTERVAL FLOOR(RAND() * 20) DAY) as order_time,
    CASE WHEN RAND() > 0.2 THEN DATE_ADD('2024-01-01', INTERVAL FLOOR(RAND() * 20) DAY) ELSE NULL END as payment_time
FROM ods_order LIMIT 190;

-- 4. 在 doris_dwd 中创建测试表
USE doris_dwd;

-- 4.1 dwd_user 表（用户明细数据）
DROP TABLE IF EXISTS dwd_user;
CREATE TABLE dwd_user (
    user_id BIGINT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(100) NOT NULL COMMENT '用户名',
    email VARCHAR(255) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    gender VARCHAR(10) COMMENT '性别',
    age INT COMMENT '年龄',
    register_date DATE COMMENT '注册日期',
    last_login_date DATE COMMENT '最后登录日期',
    user_status VARCHAR(20) COMMENT '用户状态',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_register_date (register_date),
    INDEX idx_user_status (user_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户明细数据表';

-- 从 ODS 层转换数据到 DWD 层
INSERT INTO dwd_user (user_id, username, email, phone, gender, age, register_date, last_login_date, user_status, created_at, updated_at)
SELECT
    user_id,
    username,
    email,
    phone,
    CASE gender WHEN 1 THEN '男' WHEN 2 THEN '女' ELSE '未知' END as gender,
    TIMESTAMPDIFF(YEAR, birthday, CURDATE()) as age,
    DATE(register_time) as register_date,
    DATE(last_login_time) as last_login_date,
    CASE status WHEN 1 THEN '正常' ELSE '禁用' END as user_status,
    created_at,
    updated_at
FROM doris_ods.ods_user;

-- 4.2 dwd_order 表（订单明细数据）
DROP TABLE IF EXISTS dwd_order;
CREATE TABLE dwd_order (
    order_id BIGINT PRIMARY KEY COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    order_no VARCHAR(64) NOT NULL COMMENT '订单号',
    product_id BIGINT COMMENT '商品ID',
    product_name VARCHAR(255) COMMENT '商品名称',
    quantity INT COMMENT '购买数量',
    price DECIMAL(10,2) COMMENT '单价',
    total_amount DECIMAL(12,2) COMMENT '订单总金额',
    order_status_name VARCHAR(20) COMMENT '订单状态名称',
    payment_type_name VARCHAR(20) COMMENT '支付方式名称',
    order_date DATE COMMENT '下单日期',
    payment_date DATE COMMENT '支付日期',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_order_date (order_date),
    INDEX idx_order_status_name (order_status_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细数据表';

-- 从 ODS 层转换数据到 DWD 层
INSERT INTO dwd_order (order_id, user_id, order_no, product_id, product_name, quantity, price, total_amount, order_status_name, payment_type_name, order_date, payment_date, created_at, updated_at)
SELECT
    order_id,
    user_id,
    order_no,
    product_id,
    product_name,
    quantity,
    price,
    total_amount,
    CASE order_status
        WHEN 0 THEN '待支付'
        WHEN 1 THEN '已支付'
        WHEN 2 THEN '已发货'
        WHEN 3 THEN '已完成'
        WHEN 4 THEN '已取消'
    END as order_status_name,
    CASE payment_type
        WHEN 1 THEN '支付宝'
        WHEN 2 THEN '微信'
        WHEN 3 THEN '银行卡'
    END as payment_type_name,
    DATE(order_time) as order_date,
    DATE(payment_time) as payment_date,
    created_at,
    updated_at
FROM doris_ods.ods_order;

-- 5. 在 doris_dws 中创建测试表
USE doris_dws;

-- 5.1 dws_user_daily 表（用户日统计）
DROP TABLE IF EXISTS dws_user_daily;
CREATE TABLE dws_user_daily (
    stat_date DATE PRIMARY KEY COMMENT '统计日期',
    total_users BIGINT DEFAULT 0 COMMENT '总用户数',
    new_users BIGINT DEFAULT 0 COMMENT '新增用户数',
    active_users BIGINT DEFAULT 0 COMMENT '活跃用户数',
    male_users BIGINT DEFAULT 0 COMMENT '男性用户数',
    female_users BIGINT DEFAULT 0 COMMENT '女性用户数',
    avg_age DECIMAL(5,2) COMMENT '平均年龄',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户日统计表';

-- 插入统计数据
INSERT INTO dws_user_daily (stat_date, total_users, new_users, active_users, male_users, female_users, avg_age)
SELECT
    DATE('2024-01-15') as stat_date,
    COUNT(*) as total_users,
    COUNT(CASE WHEN DATE(register_date) = '2024-01-15' THEN 1 END) as new_users,
    COUNT(CASE WHEN DATE(last_login_date) = '2024-01-15' THEN 1 END) as active_users,
    COUNT(CASE WHEN gender = '男' THEN 1 END) as male_users,
    COUNT(CASE WHEN gender = '女' THEN 1 END) as female_users,
    AVG(age) as avg_age
FROM doris_dwd.dwd_user;

-- 5.2 dws_order_daily 表（订单日统计）
DROP TABLE IF EXISTS dws_order_daily;
CREATE TABLE dws_order_daily (
    stat_date DATE PRIMARY KEY COMMENT '统计日期',
    total_orders BIGINT DEFAULT 0 COMMENT '总订单数',
    paid_orders BIGINT DEFAULT 0 COMMENT '已支付订单数',
    total_amount DECIMAL(15,2) DEFAULT 0 COMMENT '总金额',
    paid_amount DECIMAL(15,2) DEFAULT 0 COMMENT '已支付金额',
    avg_order_amount DECIMAL(10,2) COMMENT '平均订单金额',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单日统计表';

-- 插入订单统计数据
INSERT INTO dws_order_daily (stat_date, total_orders, paid_orders, total_amount, paid_amount, avg_order_amount)
SELECT
    order_date as stat_date,
    COUNT(*) as total_orders,
    COUNT(CASE WHEN order_status_name IN ('已支付', '已发货', '已完成') THEN 1 END) as paid_orders,
    SUM(total_amount) as total_amount,
    SUM(CASE WHEN order_status_name IN ('已支付', '已发货', '已完成') THEN total_amount ELSE 0 END) as paid_amount,
    AVG(total_amount) as avg_order_amount
FROM doris_dwd.dwd_order
WHERE order_date IS NOT NULL
GROUP BY order_date;

-- 6. 更新 opendataworks 库中的表信息，关联到测试数据库
USE opendataworks;

-- 更新已有表的数据库名
UPDATE data_table SET db_name = 'doris_ods' WHERE table_name LIKE 'ods_%';
UPDATE data_table SET db_name = 'doris_dwd' WHERE table_name LIKE 'dwd_%';
UPDATE data_table SET db_name = 'doris_dws' WHERE table_name LIKE 'dws_%';

-- 插入新表记录（如果不存在）
INSERT IGNORE INTO data_table (table_name, table_comment, db_name, layer, status, created_at)
VALUES
('ods_user', '用户原始数据表', 'doris_ods', 'ODS', 'active', NOW()),
('ods_order', '订单原始数据表', 'doris_ods', 'ODS', 'active', NOW());

INSERT IGNORE INTO data_table (table_name, table_comment, db_name, layer, status, created_at)
VALUES
('dws_user_daily', '用户日统计表', 'doris_dws', 'DWS', 'active', NOW()),
('dws_order_daily', '订单日统计表', 'doris_dws', 'DWS', 'active', NOW());

-- 7. 显示初始化结果
SELECT '=== Doris 集群配置 ===' as info;
SELECT id, cluster_name, fe_host, fe_port, username, is_default, status FROM doris_cluster WHERE deleted = 0;

SELECT '=== doris_ods 数据库表统计 ===' as info;
SELECT
    'ods_user' as table_name,
    COUNT(*) as row_count,
    ROUND(DATA_LENGTH / 1024, 2) as data_size_kb
FROM doris_ods.ods_user
UNION ALL
SELECT
    'ods_order' as table_name,
    COUNT(*) as row_count,
    ROUND(DATA_LENGTH / 1024, 2) as data_size_kb
FROM doris_ods.ods_order;

SELECT '=== doris_dwd 数据库表统计 ===' as info;
SELECT
    'dwd_user' as table_name,
    COUNT(*) as row_count,
    ROUND(DATA_LENGTH / 1024, 2) as data_size_kb
FROM doris_dwd.dwd_user
UNION ALL
SELECT
    'dwd_order' as table_name,
    COUNT(*) as row_count,
    ROUND(DATA_LENGTH / 1024, 2) as data_size_kb
FROM doris_dwd.dwd_order;

SELECT '=== doris_dws 数据库表统计 ===' as info;
SELECT
    'dws_user_daily' as table_name,
    COUNT(*) as row_count,
    ROUND(DATA_LENGTH / 1024, 2) as data_size_kb
FROM doris_dws.dws_user_daily
UNION ALL
SELECT
    'dws_order_daily' as table_name,
    COUNT(*) as row_count,
    ROUND(DATA_LENGTH / 1024, 2) as data_size_kb
FROM doris_dws.dws_order_daily;

SELECT '初始化完成！' as status;
