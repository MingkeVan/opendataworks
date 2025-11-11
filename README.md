# opendataworks

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.4+-green.svg)](https://vuejs.org/)
[![DolphinScheduler](https://img.shields.io/badge/DolphinScheduler-3.2.0-blue.svg)](https://dolphinscheduler.apache.org/)

**一站式数据任务管理与数据血缘可视化平台**

[🌐 项目主页](https://mingkevan.github.io/opendataworks/) | [English](README_EN.md) | 简体中文

[快速开始](#快速开始) · [功能特性](#功能特性) · [架构设计](#架构设计) · [开发文档](#开发文档) · [贡献指南](#贡献指南)

</div>

---

## 📖 项目简介

opendataworks 是一个面向大数据平台的统一数据门户系统,旨在为企业提供一站式的数据资产管理、任务调度编排和血缘关系追踪解决方案。

## 📁 仓库结构

- `deploy/`：集中存放 Docker Compose 与环境变量模板
- `deploy/offline/`：离线部署包模板（被 `scripts/offline/create-offline-package-from-dockerhub.sh` 使用）
- `scripts/`：本地开发、部署、测试相关脚本（含 Docker 构建、离线打包、启动/停止等）
- `database/mysql/`：数据库引导、核心 schema、巡检、示例与测试数据脚本
- `docs/handbook/`：产品/架构/开发/运维/测试手册及专题
- `docs/reports/`：历史修复与测试报告
- `docs/site/`：GitHub Pages 站点源码与部署说明
- `artifacts/archives/`：下载或生成的归档文件归置目录

### 🎯 项目目标

- **统一管理**: 集中管理数据表元信息、数据域、业务域等数据资产
- **任务编排**: 可视化配置批处理和流处理任务,支持 DolphinScheduler 深度集成
- **血缘追踪**: 自动生成数据血缘关系图,实现数据链路全链路可视化
- **执行监控**: 实时监控任务执行状态,支持日志查看和故障排查
- **开箱即用**: 提供完整的前后端实现,快速部署即可使用

### 🌟 核心价值

- 降低数据开发门槛,提升开发效率 50%+
- 统一数据资产视图,避免重复建设
- 自动化任务调度,减少人工干预
- 可视化血缘关系,快速定位数据问题
- 开源免费,支持定制化扩展

---

## ✨ 功能特性

### 已实现功能 (v1.0)

#### 📊 数据资产管理
- ✅ 数据表元信息管理 (ODS/DWD/DIM/DWS/ADS 分层)
- ✅ 字段级别管理 (数据类型、注释、是否主键等)
- ✅ 数据域和业务域分类管理
- ✅ 数据表生命周期管理
- ✅ 表级别权限控制

#### 🔄 任务调度管理
- ✅ 批处理任务创建和配置
- ✅ SQL 和 Shell 任务支持
- ✅ 多数据源支持 (Doris、MySQL 等)
- ✅ 任务优先级和超时配置
- ✅ 任务失败重试机制
- ✅ 两种执行模式:
  - **单任务执行**: 快速测试单个任务
  - **工作流执行**: 按依赖关系执行完整工作流

#### 🔗 DolphinScheduler 集成
- ✅ 自动创建和同步工作流
- ✅ 动态查询项目信息 (无需硬编码 project-code)
- ✅ 支持任务依赖关系配置
- ✅ 工作流上线/下线管理
- ✅ 任务执行状态同步
- ✅ Python 中间服务层 (dolphinscheduler-service)

#### 📈 数据血缘可视化
- ✅ 基于任务输入输出自动生成血缘关系
- ✅ ECharts 力导向图展示
- ✅ 按数据层级筛选 (ODS/DWD/DWS 等)
- ✅ 节点点击查看详情
- ✅ 支持血缘链路追踪

#### 🎛️ 执行监控
- ✅ 任务执行历史记录
- ✅ 实时执行状态查询
- ✅ 执行日志查看
- ✅ 任务执行统计 (成功率、耗时等)
- ✅ 失败任务告警

#### 🖥️ 用户界面
- ✅ 响应式设计,支持多种分辨率
- ✅ 顶部水平菜单布局
- ✅ 表单验证和错误提示
- ✅ 分页和排序
- ✅ 搜索和筛选

### 🚀 规划中功能

#### Phase 2 (Q1 2026)
- [ ] Dinky/Flink 流任务集成
- [ ] SQL 解析器集成 (自动提取血缘)
- [ ] 任务调度策略增强 (Cron 表达式可视化)
- [ ] 数据质量监控集成
- [ ] 多租户支持

#### Phase 3 (Q2 2026)
- [ ] 用户权限管理 (RBAC)
- [ ] 任务审批流程
- [ ] 数据字典管理
- [ ] 告警规则配置
- [ ] 指标统计看板
- [ ] 数据资产报表

#### Phase 4 (Q3 2026)
- [ ] AI 辅助 SQL 生成
- [ ] 智能任务推荐
- [ ] 数据质量自动检测
- [ ] 成本分析和优化建议
- [ ] 多云支持 (AWS、阿里云等)

---

## 🏗️ 架构设计

### 系统架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户浏览器                                │
└─────────────────────────────────────────────────────────────────┘
                                │
                                │ HTTP/HTTPS
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│                    前端应用 (Vue 3 + Vite)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ 表管理   │  │ 任务管理  │  │ 血缘关系  │  │ 执行监控  │       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
└─────────────────────────────────────────────────────────────────┘
                                │
                                │ REST API
                                ↓
┌─────────────────────────────────────────────────────────────────┐
│              后端应用 (Spring Boot 2.7.18)                       │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Controller Layer                                        │  │
│  │  (DataTableController, DataTaskController, etc.)        │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                │                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Service Layer                                           │  │
│  │  (DataTableService, DataTaskService, Lineage Service)   │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                │                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Mapper Layer (MyBatis-Plus)                            │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                    │                           │
                    │                           │ HTTP Client
                    ↓                           ↓
    ┌──────────────────────┐    ┌────────────────────────────────┐
    │   MySQL 8.0+         │    │  DolphinScheduler Service      │
    │                      │    │  (Python FastAPI)              │
    │  - data_table        │    │                                │
    │  - data_task         │    │  ┌──────────────────────────┐ │
    │  - data_lineage      │    │  │ pydolphinscheduler SDK  │ │
    │  - task_execution    │    │  └──────────────────────────┘ │
    │    _log              │    └────────────────────────────────┘
    └──────────────────────┘                    │
                                                │ Java Gateway
                                                ↓
                            ┌────────────────────────────────────┐
                            │   Apache DolphinScheduler          │
                            │   (Workflow Orchestration)         │
                            │                                    │
                            │  - Master Server                   │
                            │  - Worker Server                   │
                            │  - API Server                      │
                            └────────────────────────────────────┘
```

### 技术选型

#### 后端技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 8+ | 开发语言 |
| Spring Boot | 2.7.18 | 应用框架 |
| MyBatis-Plus | 3.5.5 | ORM 框架 |
| MySQL | 8.0+ | 关系数据库 |
| WebFlux | 5.3.31 | 响应式 HTTP 客户端 |
| Lombok | - | 代码简化工具 |
| Jackson | 2.13.5 | JSON 序列化 |
| HikariCP | 4.0.3 | 数据库连接池 |

#### 前端技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Vue | 3.4+ | 前端框架 |
| Vite | 5.0+ | 构建工具 |
| Element Plus | 2.5+ | UI 组件库 |
| ECharts | 5.4+ | 图表库 |
| Vue Router | 4.x | 路由管理 |
| Axios | 1.6+ | HTTP 客户端 |

#### 中间服务

| 技术 | 版本 | 说明 |
|------|------|------|
| Python | 3.9+ | 开发语言 |
| FastAPI | 0.109+ | Web 框架 |
| pydolphinscheduler | 3.2.0 | DolphinScheduler SDK |
| Uvicorn | 0.27+ | ASGI 服务器 |
| Pydantic | 2.x | 数据验证 |

#### 外部依赖

| 组件 | 版本 | 说明 |
|------|------|------|
| Apache DolphinScheduler | 3.2.0 | 工作流调度引擎 |
| Apache Doris | - | 分析型数据库 (可选) |

### 项目结构

```
opendataworks/
├── backend/                          # Java 后端服务
│   ├── src/main/
│   │   ├── java/com/onedata/portal/
│   │   │   ├── config/              # 配置类
│   │   │   │   ├── DolphinSchedulerProperties.java
│   │   │   │   ├── MyBatisPlusConfig.java
│   │   │   │   └── WebConfig.java
│   │   │   ├── controller/          # REST 控制器
│   │   │   │   ├── DataTableController.java
│   │   │   │   ├── DataTaskController.java
│   │   │   │   ├── DataLineageController.java
│   │   │   │   └── DataDomainController.java
│   │   │   ├── entity/              # 实体类
│   │   │   │   ├── DataTable.java
│   │   │   │   ├── DataTask.java
│   │   │   │   ├── DataLineage.java
│   │   │   │   └── TaskExecutionLog.java
│   │   │   ├── mapper/              # MyBatis Mapper
│   │   │   │   ├── DataTableMapper.java
│   │   │   │   ├── DataTaskMapper.java
│   │   │   │   └── DataLineageMapper.java
│   │   │   ├── service/             # 业务逻辑
│   │   │   │   ├── DataTableService.java
│   │   │   │   ├── DataTaskService.java
│   │   │   │   ├── DolphinSchedulerService.java
│   │   │   │   └── LineageService.java
│   │   │   ├── dto/                 # 数据传输对象
│   │   │   └── DataPortalApplication.java
│   │   └── resources/
│   │       ├── application.yml      # 应用配置
│   │       └── mapper/              # MyBatis XML
│   └── pom.xml
│
├── database/
│   └── mysql/                       # 数据库脚本 (bootstrap/schema/sample/addons)
│       ├── 00-bootstrap.sql
│       ├── 10-core-schema.sql
│       ├── 20-inspection-schema.sql
│       ├── 30-sample-data.sql
│       └── addons/40-init-test-data.sql
│
├── frontend/                         # Vue 前端应用
│   ├── src/
│   │   ├── api/                     # API 封装
│   │   │   ├── table.js
│   │   │   ├── task.js
│   │   │   ├── lineage.js
│   │   │   └── domain.js
│   │   ├── views/                   # 页面组件
│   │   │   ├── Layout.vue           # 主布局
│   │   │   ├── tables/              # 表管理页面
│   │   │   ├── tasks/               # 任务管理页面
│   │   │   ├── lineage/             # 血缘关系页面
│   │   │   └── domains/             # 域管理页面
│   │   ├── router/                  # 路由配置
│   │   ├── utils/                   # 工具函数
│   │   ├── App.vue
│   │   └── main.js
│   ├── package.json
│   └── vite.config.js
│
├── dolphinscheduler-service/         # Python 中间服务
│   ├── dolphinscheduler_service/
│   │   ├── main.py                  # FastAPI 应用
│   │   ├── scheduler.py             # 调度器核心逻辑
│   │   ├── models.py                # Pydantic 模型
│   │   └── config.py                # 配置管理
│   ├── requirements.txt
│   └── README.md
│
├── docs/
│   ├── README.md                    # 文档索引
│   ├── handbook/                    # 产品/架构/开发/运维/测试手册
│   ├── reports/                     # 测试与修复报告
│   └── site/                        # GitHub Pages 站点源码
│
├── README.md                         # 本文档
├── LICENSE                           # 开源协议
└── .gitignore
```

---

## 🚀 快速开始

### 环境要求

- **操作系统**: Linux / macOS / Windows
- **JDK**: 8 或更高版本
- **Maven**: 3.6+
- **Node.js**: 16+ (推荐 18 LTS)
- **Python**: 3.9+
- **MySQL**: 8.0+
- **DolphinScheduler**: 3.2.0+ (可选,用于任务调度)

### 安装步骤

#### 1. 克隆项目

```bash
git clone https://github.com/MingkeVan/opendataworks.git
cd opendataworks
```

#### 2. 数据库初始化

##### 方法一：使用自动化脚本（推荐）

使用提供的初始化脚本自动完成数据库创建、用户配置和表结构初始化：

```bash
# 基本用法
scripts/dev/init-database.sh -r root密码 -p 应用密码

# 包含示例数据
scripts/dev/init-database.sh -r root密码 -p 应用密码 -s

# 自定义配置
scripts/dev/init-database.sh \
  -h localhost \
  -P 3306 \
  -d opendataworks \
  -u opendataworks \
  -p 应用密码 \
  -r root密码 \
  -s

# 查看所有选项
scripts/dev/init-database.sh --help
```

**脚本功能**:
- ✅ 自动创建数据库（UTF-8MB4 字符集）
- ✅ 创建应用用户并授权
- ✅ 执行建表脚本
- ✅ 加载巡检模块表结构
- ✅ 可选加载示例数据
- ✅ 验证初始化结果
- ✅ 显示连接信息

##### 方法二：手动初始化

如果需要手动控制每个步骤：

```bash
# 1. 创建数据库
mysql -u root -p << EOF
CREATE DATABASE opendataworks
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
EOF

# 2. 创建应用用户（推荐，避免使用 root）
mysql -u root -p << EOF
CREATE USER 'opendataworks'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'localhost';

-- 如需远程访问，添加远程用户
CREATE USER 'opendataworks'@'%' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'%';

FLUSH PRIVILEGES;
EOF

# 3. 执行建表脚本（核心表结构）
mysql -u root -p opendataworks < database/mysql/10-core-schema.sql

# 4. 执行巡检模块脚本（可选）
mysql -u root -p opendataworks < database/mysql/20-inspection-schema.sql

# 5. 加载示例数据（可选，用于测试）
mysql -u root -p opendataworks < database/mysql/30-sample-data.sql
```

##### 验证数据库初始化

```bash
# 检查数据库是否创建成功
mysql -u opendataworks -p opendataworks -e "SHOW TABLES;"

# 预期输出应包含以下表：
# - data_table（数据表元信息）
# - data_task（任务定义）
# - data_lineage（血缘关系）
# - task_execution_log（执行日志）
# - data_domain（数据域）
# - business_domain（业务域）
# - inspection_task（巡检任务，可选）
# - inspection_rule（巡检规则，可选）

# 查看表数量
mysql -u opendataworks -p opendataworks -e "SELECT COUNT(*) as table_count FROM information_schema.tables WHERE table_schema = 'opendataworks';"
```

##### 数据库迁移（如果需要）

如果数据库已存在且需要升级：

```bash
# 查看当前数据库版本
mysql -u opendataworks -p opendataworks -e "SELECT * FROM schema_version LIMIT 1;"

# 执行增量迁移脚本
mysql -u opendataworks -p opendataworks < backend/src/main/resources/db/migration/V2__add_table_features.sql
mysql -u opendataworks -p opendataworks < backend/src/main/resources/db/migration/V3__add_statistics_history.sql
```

##### 常见问题排查

**问题1：字符集错误**
```bash
# 检查数据库字符集
mysql -u root -p -e "SELECT default_character_set_name, default_collation_name FROM information_schema.schemata WHERE schema_name = 'opendataworks';"

# 如果字符集不正确，修改
mysql -u root -p -e "ALTER DATABASE opendataworks CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

**问题2：权限不足**
```bash
# 检查用户权限
mysql -u root -p -e "SHOW GRANTS FOR 'opendataworks'@'localhost';"

# 重新授权
mysql -u root -p -e "GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'localhost'; FLUSH PRIVILEGES;"
```

**问题3：表已存在**
```bash
# 备份现有数据
mysqldump -u root -p opendataworks > opendataworks_backup_$(date +%Y%m%d).sql

# 删除数据库重建
mysql -u root -p -e "DROP DATABASE opendataworks;"
scripts/dev/init-database.sh -r root密码 -p 应用密码
```

#### 3. 启动 DolphinScheduler (可选)

如果需要任务调度功能,请先安装并启动 DolphinScheduler。

参考官方文档: https://dolphinscheduler.apache.org/zh-cn/docs/3.2.0/guide/installation/standalone

#### 4. 启动 Python 中间服务

```bash
cd dolphinscheduler-service

# 创建虚拟环境
python3 -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# 安装依赖
pip install -r requirements.txt

# 启动服务
uvicorn dolphinscheduler_service.main:app --host 0.0.0.0 --port 5001
```

服务将运行在 `http://localhost:5001`

#### 5. 启动后端服务

```bash
cd backend

# 修改配置文件
vim src/main/resources/application.yml

# 配置数据库连接
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/opendataworks
    username: root
    password: your_password

# 配置 DolphinScheduler 服务地址（注意：这是 Python 中间服务的地址，不是 DolphinScheduler 的 API 地址）
dolphin:
  service-url: http://localhost:5001  # Python 中间服务地址
  project-name: test-project

# 编译并启动
mvn clean install
mvn spring-boot:run
```

服务将运行在 `http://localhost:8080`

#### 6. 启动前端应用

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

应用将运行在 `http://localhost:5173`

#### 7. 访问应用

打开浏览器访问: `http://localhost:5173`

### 开发环境一键启动（Docker Compose）

如果希望一次性在本机拉起完整的前端、后端、Python 服务和 MySQL，可直接使用 `deploy/docker-compose.dev.yml`：

```bash
# 1. 准备环境变量
cp deploy/.env.example deploy/.env        # 按需修改数据库与 DolphinScheduler 配置

# 2. 启动完整开发栈
docker compose -f deploy/docker-compose.dev.yml up -d

# 3. 查看运行状态
docker compose -f deploy/docker-compose.dev.yml ps

# 4. 停止并清理
docker compose -f deploy/docker-compose.dev.yml down
```

> 提示  
> - 该 Compose 文件使用 `mikefan2019/opendataworks-*` 镜像，若需自建镜像，请先执行 `scripts/build/build-images.sh` 并按需修改 `image` 字段。  
> - 如果遇到 `proxy already running`，说明之前的 Compose 实例未完全退出，先执行 `docker compose -f deploy/docker-compose.dev.yml down` 或重启 Docker 再尝试。

### Docker 快速部署 (推荐)

```bash
# 1. 构建或下载镜像
scripts/build/build-images.sh

# 2. 内网主机加载镜像
scripts/deploy/load-images.sh

# 3. 启动所有服务
scripts/deploy/start.sh
```

---

## 📚 使用说明

### 1. 数据表管理

1. 进入"表管理"页面
2. 点击"新建表"按钮
3. 填写表名、数据层级、业务域等信息
4. 添加字段定义
5. 点击"保存"

### 2. 创建批处理任务

1. 进入"任务管理"页面
2. 点击"新建任务"按钮
3. 填写任务基本信息:
   - 任务名称
   - 任务类型 (批任务/流任务)
   - 执行引擎 (DolphinScheduler/Dinky)
4. 配置任务参数:
   - 节点类型 (SQL/Shell/Python)
   - 数据源 (如果是 SQL 任务)
   - SQL 语句或 Shell 脚本
5. 配置调度参数:
   - 优先级
   - 超时时间
   - 重试次数
6. 选择输入表和输出表 (用于血缘关系)
7. 点击"保存"

### 3. 发布任务到 DolphinScheduler

1. 在任务列表中找到已创建的任务
2. 点击"发布"按钮
3. 系统会自动:
   - 调用 Python 服务创建工作流
   - 同步任务定义到 DolphinScheduler
   - 配置任务依赖关系
4. 发布成功后,任务状态变为"已发布"

### 4. 执行任务

有两种执行方式:

#### 单任务执行 (快速测试)
- 点击"执行任务"按钮
- 系统创建临时工作流并立即执行
- 适合快速测试单个任务

#### 工作流执行 (生产环境)
- 点击"执行工作流"按钮
- 系统按依赖关系执行完整工作流
- 适合生产环境正式运行

### 5. 查看血缘关系

1. 进入"血缘关系"页面
2. 选择数据层级筛选 (可选)
3. 查看数据血缘图:
   - 表节点显示为圆形
   - 任务节点显示为方形
   - 连线表示数据流向
4. 点击节点查看详细信息

### 6. 执行监控

1. 在任务列表中查看"最近执行"列
2. 点击任务名称进入详情页
3. 查看:
   - 执行历史记录
   - 执行状态 (pending/running/success/failed)
   - 执行日志
   - 执行时长统计

---

## 🔧 配置说明

### 后端配置 (application.yml)

```yaml
server:
  port: 8080
  servlet:
    context-path: /api

spring:
  application:
    name: opendataworks

  # 数据库配置
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/opendataworks?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: opendataworks
    password: opendataworks123

  # Jackson 配置
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: GMT+8

# MyBatis Plus 配置
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  type-aliases-package: com.onedata.portal.entity
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

# DolphinScheduler 配置
dolphin:
  service-url: http://localhost:5001      # Python 服务地址
  project-name: opendataworks              # 项目名称 (自动查询 project-code)
  tenant-code: default                     # 租户代码
  worker-group: default                    # Worker 组
  execution-type: PARALLEL                 # 执行类型

# 日志配置
logging:
  level:
    com.onedata.portal: debug
    org.springframework.web: info
```

### 前端配置 (vite.config.js)

```javascript
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
```

### Python 服务配置

参考 `dolphinscheduler-service/README.md`

---

## 📊 数据模型

### 核心表结构

#### 1. data_table - 数据表元信息

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| table_name | VARCHAR(100) | 表名 |
| table_comment | VARCHAR(500) | 表注释 |
| layer | VARCHAR(20) | 数据层级 (ODS/DWD/DIM/DWS/ADS) |
| business_domain | VARCHAR(50) | 业务域 |
| data_domain | VARCHAR(50) | 数据域 |
| owner | VARCHAR(50) | 负责人 |
| status | VARCHAR(20) | 状态 |
| lifecycle_days | INT | 生命周期(天) |

#### 2. data_task - 任务定义

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| task_name | VARCHAR(100) | 任务名称 |
| task_code | VARCHAR(100) | 任务编码 |
| task_type | VARCHAR(20) | 任务类型 (batch/stream) |
| engine | VARCHAR(50) | 执行引擎 (dolphin/dinky) |
| dolphin_node_type | VARCHAR(50) | 节点类型 (SQL/SHELL/PYTHON) |
| task_sql | TEXT | SQL 语句 |
| priority | INT | 优先级 (1-10) |
| timeout_seconds | INT | 超时时间(秒) |
| retry_times | INT | 重试次数 |
| status | VARCHAR(20) | 状态 (draft/published/running) |

#### 3. data_lineage - 血缘关系

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| task_id | BIGINT | 任务ID |
| upstream_table_id | BIGINT | 上游表ID |
| downstream_table_id | BIGINT | 下游表ID |
| lineage_type | VARCHAR(20) | 血缘类型 (input/output) |

#### 4. task_execution_log - 任务执行日志

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| task_id | BIGINT | 任务ID |
| execution_id | VARCHAR(100) | 执行ID |
| status | VARCHAR(20) | 执行状态 |
| start_time | DATETIME | 开始时间 |
| end_time | DATETIME | 结束时间 |
| duration_seconds | INT | 执行时长(秒) |
| error_message | TEXT | 错误信息 |

详细建表脚本参见: `database/mysql/10-core-schema.sql`

---

## 🛠️ 开发文档

### 📚 文档导航

详细的技术文档请查看 [docs/](docs/) 目录:

- **[手册 (handbook)](docs/handbook/)** - 产品概览、架构设计、数据模型、开发/运维/测试指南与专题文档
- **[特性专题](docs/handbook/features/)** - Doris 统计增强、任务状态、图表实现等专项说明
- **[报告 (reports)](docs/reports/)** - 测试报告、修复记录、工作流问题复盘
- **[站点 (site)](docs/site/)** - GitHub Pages 主页源码与部署指南

### API 接口文档

#### 表管理 API

```
GET    /api/v1/tables              # 分页查询表列表
GET    /api/v1/tables/all          # 获取所有表
GET    /api/v1/tables/{id}         # 获取表详情
POST   /api/v1/tables              # 创建表
PUT    /api/v1/tables/{id}         # 更新表
DELETE /api/v1/tables/{id}         # 删除表
```

#### 任务管理 API

```
GET    /api/v1/tasks               # 分页查询任务列表
GET    /api/v1/tasks/{id}          # 获取任务详情
POST   /api/v1/tasks               # 创建任务
PUT    /api/v1/tasks/{id}          # 更新任务
DELETE /api/v1/tasks/{id}          # 删除任务
POST   /api/v1/tasks/{id}/execute  # 执行单任务
POST   /api/v1/tasks/{id}/execute-workflow  # 执行工作流
GET    /api/v1/tasks/{id}/execution-status  # 查询执行状态
```

> 注：任务发布已迁移至工作流管理接口，请使用 `/api/v1/workflows/{id}/publish` 触发部署/上线/下线操作。

#### 血缘关系 API

```
GET    /api/v1/lineage             # 获取血缘图数据
```

#### 域管理 API

```
GET    /api/v1/domains/business    # 获取业务域列表
GET    /api/v1/domains/data        # 获取数据域列表
```

### 添加新功能

#### 1. 添加新实体

**后端**:
1. 在 `entity/` 创建实体类
2. 在 `mapper/` 创建 Mapper 接口
3. 在 `service/` 实现业务逻辑
4. 在 `controller/` 创建 REST 接口

**前端**:
1. 在 `api/` 添加 API 封装
2. 在 `views/` 创建页面组件

#### 2. 代码规范

- 后端使用 Lombok 简化代码
- 统一使用 `Result<T>` 包装响应
- 前端使用 Composition API
- 组件命名采用 PascalCase
- API 接口采用 RESTful 风格
- 提交代码前运行测试

### 测试

```bash
# 后端测试
cd backend
mvn test

# 前端测试 (即将支持)
cd frontend
npm test
```

---

## 📦 部署指南

### 生产环境部署

#### 1. 后端打包

```bash
cd backend
mvn clean package -DskipTests

# 生成 JAR 文件: target/opendataworks-backend-1.0.0.jar

# 运行
java -jar target/opendataworks-backend-1.0.0.jar \
  --spring.datasource.url=jdbc:mysql://your-db-host:3306/opendataworks \
  --spring.datasource.username=your-username \
  --spring.datasource.password=your-password \
  --dolphin.service-url=http://your-dolphin-service:5001
```

#### 2. 前端打包

```bash
cd frontend
npm run build

# 生成静态文件: dist/
```

#### 3. Nginx 配置

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # 前端静态文件
    location / {
        root /path/to/frontend/dist;
        try_files $uri $uri/ /index.html;
    }

    # 后端 API 代理
    location /api {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

#### 4. Systemd 服务配置

**后端服务** (`/etc/systemd/system/opendataworks.service`):

```ini
[Unit]
Description=OpenDataWorks Backend
After=network.target

[Service]
Type=simple
User=opendataworks
ExecStart=/usr/bin/java -jar /opt/opendataworks/opendataworks-backend-1.0.0.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

**Python 服务** (`/etc/systemd/system/dolphinscheduler-service.service`):

```ini
[Unit]
Description=DolphinScheduler Service
After=network.target

[Service]
Type=simple
User=opendataworks
WorkingDirectory=/opt/dolphinscheduler-service
ExecStart=/opt/dolphinscheduler-service/venv/bin/uvicorn dolphinscheduler_service.main:app --host 0.0.0.0 --port 5001
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

启动服务:

```bash
sudo systemctl enable opendataworks
sudo systemctl start opendataworks

sudo systemctl enable dolphinscheduler-service
sudo systemctl start dolphinscheduler-service
```

### Docker 部署 (即将支持)

```yaml
# docker-compose.yml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: opendataworks
    volumes:
      - ./database/mysql:/docker-entrypoint-initdb.d:ro

  backend:
    build: ./backend
    ports:
      - "8080:8080"
    depends_on:
      - mysql

  frontend:
    build: ./frontend
    ports:
      - "80:80"
    depends_on:
      - backend
```

---

## 🤝 贡献指南

我们欢迎任何形式的贡献,包括但不限于:

- 🐛 报告 Bug
- 💡 提出新功能建议
- 📝 改进文档
- 🔧 提交代码修复
- ⚡ 性能优化

### 贡献流程

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

### 开发规范

- 遵循现有代码风格
- 添加必要的注释
- 编写单元测试
- 更新相关文档

---

## 📅 开发路线图

### v1.0 (已发布) - 2025 Q4
- ✅ 数据表元信息管理
- ✅ 批处理任务管理
- ✅ DolphinScheduler 集成
- ✅ 数据血缘可视化
- ✅ 任务执行监控

### v1.1 (2026 Q1)
- [ ] Dinky/Flink 流任务支持
- [ ] SQL 血缘自动解析
- [ ] 任务调度可视化编辑器
- [ ] 性能优化

### v2.0 (2026 Q2)
- [ ] 用户权限管理 (RBAC)
- [ ] 任务审批流程
- [ ] 数据质量监控
- [ ] 告警规则配置
- [ ] 多租户支持

### v3.0 (2026 Q3+)
- [ ] AI 辅助功能
- [ ] 数据资产报表
- [ ] 成本分析
- [ ] 多云支持

---

## ❓ 常见问题

### 1. 无法连接到 DolphinScheduler

**问题**: 发布任务时提示连接失败

**解决方案**:
- 检查 Python 服务是否启动: `curl http://localhost:5001/health`
- 检查 DolphinScheduler 是否运行
- 确认配置文件中的 `dolphin.service-url` 正确

### 2. 前端调用后端接口 CORS 错误

**问题**: 浏览器控制台显示 CORS 错误

**解决方案**:
- 确保后端 `WebConfig` 中配置了正确的前端地址
- 检查 Vite 配置中的 proxy 设置

### 3. 血缘图不显示

**问题**: 血缘关系页面显示空白

**解决方案**:
- 确保已创建任务并配置了输入输出表
- 检查浏览器控制台是否有 JavaScript 错误
- 确认 ECharts 库已正确加载

### 4. 任务执行状态无法同步

**问题**: 任务已执行但状态仍显示 pending

**解决方案**:
- 检查 DolphinScheduler 工作流是否实际执行
- 查看后端日志是否有错误信息
- 确认数据库连接正常

### 5. 项目编码查询失败

**问题**: 启动时提示无法查询 project-code

**解决方案**:
- 确保 DolphinScheduler 中已创建对应项目
- 检查 `dolphin.project-name` 配置是否正确
- 查看 Python 服务日志: `tail -f dolphinscheduler-service/service.log`

### 6. Python Dolphin 服务无法连接到 Java Gateway 25333 端口

**问题**: `dolphinscheduler-service` 日志出现 `GatewayError`/`ConnectRefusedError`，提示无法连接 `25333` 端口。

**解决方案**:
1. **确认 Python 服务配置**：检查 `.env` 或环境变量中的 `PYDS_JAVA_GATEWAY_ADDRESS`、`PYDS_JAVA_GATEWAY_PORT` 是否指向 DolphinScheduler API Server（默认 `25333`）。
2. **开启 Python Gateway**：在 DolphinScheduler API Server 节点的 `api-server/conf/application.yaml`（离线安装路径 `${DOLPHINSCHEDULER_HOME}/api-server/conf/application.yaml`）下，将 `python-gateway.enabled` 设为 `true`，并根据需要调整 `address`、`port`、`token`：

   ```yaml
   python-gateway:
     enabled: true
     address: 0.0.0.0
     port: 25333
     token: "<可选：如启用需要同步配置 PYDS_JAVA_GATEWAY_AUTH_TOKEN>"
   ```

   修改后重启 `dolphinscheduler-api` 服务（Docker 部署 `docker compose restart dolphinscheduler-api`，Systemd 部署 `systemctl restart dolphinscheduler-api`）。
3. **验证端口开放**：在 DolphinScheduler 服务器上执行 `ss -lntp | grep 25333`（或 `netstat -tunlp | grep 25333`），确认端口监听；在 `dolphinscheduler-service` 服务器上执行 `nc -zv <api-server-host> 25333`，验证网络连通性。
4. **启用 Token 保护（可选）**：若启用了 `python-gateway.token`，在 `dolphinscheduler-service` 的 `.env` 中追加 `PYDS_JAVA_GATEWAY_AUTH_TOKEN=<相同的 token>` 并重启服务。

---

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

---

## 🙏 致谢

感谢以下开源项目:

- [Apache DolphinScheduler](https://dolphinscheduler.apache.org/)
- [Spring Boot](https://spring.io/projects/spring-boot)
- [Vue.js](https://vuejs.org/)
- [Element Plus](https://element-plus.org/)
- [MyBatis-Plus](https://baomidou.com/)
- [ECharts](https://echarts.apache.org/)

---

## 📞 联系我们

- **🌐 项目主页**: https://mingkevan.github.io/opendataworks/
- **📦 GitHub**: https://github.com/MingkeVan/opendataworks
- **🐛 问题反馈**: https://github.com/MingkeVan/opendataworks/issues
- **💬 讨论区**: https://github.com/MingkeVan/opendataworks/discussions

---

<div align="center">

**如果这个项目对你有帮助,请给我们一个 ⭐️ Star!**

Made with ❤️ by opendataworks Team

</div>
