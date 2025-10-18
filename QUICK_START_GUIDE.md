# 快速开始指南 - Doris 表统计信息增强功能

## 一、环境准备

### 1. 确认环境要求

- Java 8+
- Node.js 14+
- MySQL 8.0+
- Apache Doris 集群

### 2. 数据库迁移

```bash
# 启动后端服务，自动执行数据库迁移
cd backend
./mvnw spring-boot:run
```

迁移将自动创建 `table_statistics_history` 表。

---

## 二、后端配置

### 1. 配置 Doris 集群

通过管理界面或 API 添加 Doris 集群配置：

```bash
POST /api/v1/doris-clusters
{
  "clusterName": "生产集群",
  "feHost": "doris-fe.example.com",
  "fePort": 9030,
  "username": "admin",
  "password": "password",
  "isDefault": 1,
  "status": "active"
}
```

### 2. 配置表的数据库名

确保表的 `dbName` 字段已填写：

```sql
UPDATE data_table SET db_name = 'doris_ods' WHERE table_name = 'ods_user';
UPDATE data_table SET db_name = 'doris_dwd' WHERE table_name = 'dwd_user';
```

或通过管理界面编辑表信息。

---

## 三、前端配置

### 1. 安装 ECharts（如果未安装）

```bash
cd frontend
npm install echarts@5.4.3
```

### 2. 添加图表组件

按照 `CHART_IMPLEMENTATION_GUIDE.md` 文件中的步骤，在 `TableDetail.vue` 中添加图表代码。

### 3. 启动前端服务

```bash
cd frontend
npm install
npm run dev
```

---

## 四、使用流程

### 步骤 1：配置表

1. 登录系统
2. 进入"表管理"页面
3. 点击要查看统计的表
4. 确认以下配置：
   - **数据库名**：已填写
   - **同步状态**：已同步到 Doris

### 步骤 2：查看统计信息

1. 在表详情页面，找到"表统计信息"卡片
2. 点击"刷新"按钮
3. 查看数据行数、数据大小、分区数量等信息

### 步骤 3：查看趋势图表

1. 多次刷新统计信息（建议每天刷新一次）
2. 累积至少2条历史记录后
3. 在"数据增长趋势"卡片中查看图表
4. 使用单选按钮切换7天/30天视图

---

## 五、API 测试

### 测试获取统计信息

```bash
# 获取表统计信息（使用缓存）
curl http://localhost:8080/api/v1/tables/1/statistics

# 强制刷新（跳过缓存）
curl http://localhost:8080/api/v1/tables/1/statistics?forceRefresh=true

# 指定集群
curl http://localhost:8080/api/v1/tables/1/statistics?clusterId=2
```

### 测试历史记录

```bash
# 获取最近30条历史记录
curl http://localhost:8080/api/v1/tables/1/statistics/history?limit=30

# 获取最近7天历史
curl http://localhost:8080/api/v1/tables/1/statistics/history/last7days

# 获取最近30天历史
curl http://localhost:8080/api/v1/tables/1/statistics/history/last30days
```

---

## 六、常见问题

### Q1：刷新统计信息时提示"表未配置数据库名"

**解决方法**：

1. 方法一：在表详情中填写 `dbName` 字段
2. 方法二：使用格式 `database.table_name` 命名表

### Q2：趋势图表显示"暂无历史数据"

**原因**：历史记录少于2条

**解决方法**：

1. 多次点击"刷新"按钮（每次刷新会自动保存历史记录）
2. 或等待自动定时任务累积数据

### Q3：统计信息不准确或不是最新的

**原因**：缓存未过期（5分钟）

**解决方法**：

1. 等待缓存过期后重新刷新
2. 或使用强制刷新：

```javascript
// 前端代码
tableApi.getStatistics(id, null, true) // forceRefresh=true
```

### Q4：图表不显示或显示空白

**检查项**：

1. 确认已安装 ECharts
2. 确认已按照 `CHART_IMPLEMENTATION_GUIDE.md` 正确添加代码
3. 打开浏览器控制台检查是否有 JavaScript 错误
4. 确认历史数据已加载

---

## 七、性能建议

### 1. 缓存时间调整

如需调整缓存过期时间，修改 `TableStatisticsCacheService.java`：

```java
private static final int CACHE_EXPIRE_MINUTES = 5; // 改为需要的分钟数
```

### 2. 历史记录保留时间

建议定期清理历史记录：

```sql
-- 删除90天前的历史记录
DELETE FROM table_statistics_history
WHERE statistics_time < DATE_SUB(NOW(), INTERVAL 90 DAY);
```

### 3. 定时刷新

可以配置定时任务自动刷新所有表的统计信息：

```java
@Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点执行
public void refreshAllTableStatistics() {
    // 实现自动刷新逻辑
}
```

---

## 八、监控建议

### 1. 缓存命中率

定期检查缓存命中率：

```java
// 在 TableStatisticsCacheService 中添加统计
private final AtomicLong hits = new AtomicLong(0);
private final AtomicLong misses = new AtomicLong(0);

public double getHitRate() {
    long total = hits.get() + misses.get();
    return total == 0 ? 0 : (double) hits.get() / total;
}
```

### 2. 查询响应时间

监控 Doris 查询响应时间，及时发现性能问题。

### 3. 历史记录增长

监控 `table_statistics_history` 表大小，避免数据过多。

---

## 九、故障排除

### 问题：无法连接到 Doris

**检查项**：
1. Doris FE 地址和端口是否正确
2. 网络是否可达
3. 用户名密码是否正确
4. Doris 集群状态是否正常

### 问题：统计信息查询失败

**检查项**：
1. 表是否真实存在于 Doris 中
2. 数据库名是否正确
3. 表名是否正确
4. 是否有查询权限

### 问题：历史记录保存失败

**检查项**：
1. 数据库连接是否正常
2. `table_statistics_history` 表是否存在
3. 数据库用户是否有写入权限
4. 查看后端日志错误信息

---

## 十、下一步

1. 查看完整功能文档：`FEATURE_ENHANCEMENTS_REPORT.md`
2. 了解原始功能：`DORIS_STATISTICS_INTEGRATION.md`
3. 实现图表组件：`CHART_IMPLEMENTATION_GUIDE.md`

---

## 联系支持

遇到问题请查看日志文件或联系技术支持团队。
