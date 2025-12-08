# 快速开始

## 环境要求

- **操作系统**: Linux / macOS / Windows
- **JDK**: 8 或更高版本
- **Maven**: 3.6+
- **Node.js**: 16+ (推荐 18 LTS)
- **MySQL**: 8.0+
- **DolphinScheduler**: 3.2.0+ (可选,用于任务调度)

## 安装步骤

### 1. 克隆项目

```bash
git clone https://github.com/MingkeVan/opendataworks.git
cd opendataworks
```

### 2. 准备数据库

由于项目集成了 Flyway，您只需创建数据库和用户，无需手动导入表结构。

```bash
# 登录 MySQL
mysql -u root -p

# 执行以下 SQL 创建数据库和用户
CREATE DATABASE opendataworks DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'opendataworks'@'%' IDENTIFIED BY 'opendataworks123';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'%';
FLUSH PRIVILEGES;

EXIT;
```

> **注意**: 具体的表结构和初始化数据将在后端服务首次启动时自动通过 Flyway 迁移。

### 3. 启动 DolphinScheduler (可选)

如果需要任务调度功能,请先安装并启动 DolphinScheduler。
参考官方文档: https://dolphinscheduler.apache.org/zh-cn/docs/3.2.0/guide/installation/standalone

### 4. 启动后端服务

```bash
cd backend

# 修改配置文件 (如需修改数据库密码或 DolphinScheduler 地址)
# vim src/main/resources/application.yml

# 编译并启动
mvn clean install
mvn spring-boot:run
```

- 服务启动后，Flyway 会自动执行迁移脚本 (`src/main/resources/db/migration/`)。
- 服务默认运行在 `http://localhost:8080`。

### 5. 启动前端应用

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

- 应用将运行在 `http://localhost:5173`。
- 默认账户：不需要登录（目前版本）或根据后续更新。

### 6. 访问应用

打开浏览器访问: `http://localhost:5173`

## Docker 部署

### 开发环境快速启动

如果希望一次性在本机拉起完整环境（前端 + 后端 + MySQL），可使用开发环境 Compose：

```bash
# 1. 准备配置
cp scripts/deploy/.env.example scripts/deploy/.env

# 2. 启动服务
docker compose -f scripts/deploy/docker-compose.dev.yml up -d
```

### 生产环境/离线部署

请参考 [部署文档](../../../scripts/deploy/README.md) 获取详细的生产环境部署和离线包制作指南。
