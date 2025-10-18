# 快速启动指南

## 当前状态

✅ 后端编译成功
🔄 前端依赖安装中...

## 系统要求

- Java 8 (已检测到)
- Node.js 20.x (已检测到)
- MySQL 9.x (已检测到)
- Maven 3.9 (已检测到)

## 启动步骤

### 1. 数据库初始化

由于 MySQL 可能需要密码,你可以选择两种方式：

**方式 A: 手动执行 SQL (推荐)**
```bash
mysql -u root -p < backend/src/main/resources/schema.sql
```

**方式 B: 使用图形化工具**
- 使用 MySQL Workbench 或 Navicat
- 连接 MySQL
- 执行 `backend/src/main/resources/schema.sql` 文件

### 2. 配置后端数据库连接

编辑配置文件:
```bash
vim backend/src/main/resources/application.yml
```

修改数据库配置:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/data_portal
    username: root
    password: 你的MySQL密码
```

### 3. 启动后端服务

```bash
cd backend
mvn spring-boot:run
```

后端服务将在 http://localhost:8080/api 启动

### 4. 启动前端服务

等待 npm install 完成后:

```bash
cd frontend
npm run dev
```

前端应用将在 http://localhost:3000 启动

## 访问应用

打开浏览器访问: http://localhost:3000

默认功能:
- 表管理: 管理数据表元信息
- 任务管理: 创建和管理批/流任务
- 血缘关系: 可视化数据血缘 DAG
- 执行监控: 查看任务执行状态

## 常见问题

### Q: 后端启动失败 - 无法连接数据库
A: 检查 MySQL 是否运行,数据库 data_portal 是否存在,用户名密码是否正确

### Q: 前端页面空白
A: 确保后端服务已启动,检查浏览器控制台错误

### Q: DolphinScheduler 集成报错
A: 修改 application.yml 中的 dolphin 配置,填入正确的 API 地址和 Token

## 注意事项

1. **当前版本不需要 DolphinScheduler 即可运行基础功能**
   - 表管理、任务定义、血缘可视化不依赖 DolphinScheduler
   - 只有"发布任务"和"执行任务"功能需要配置 DolphinScheduler

2. **测试数据已内置**
   - schema.sql 包含了5个示例表
   - 可以直接创建任务测试

3. **端口占用**
   - 后端: 8080
   - 前端: 3000
   - 确保这些端口未被占用
