# 配置说明

## 后端配置 (application.yml)

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
  url: http://localhost:12345/dolphinscheduler   # OpenAPI 地址
  token: your_dolphinscheduler_token             # 安全中心 Token
  project-name: opendataworks                    # 项目名称 (自动查询 project-code)
  project-code: 0                                # 可选：已知的项目编码
  tenant-code: default                           # 租户代码
  worker-group: default                          # Worker 组
  execution-type: PARALLEL                       # 执行类型

# 日志配置
logging:
  level:
    com.onedata.portal: debug
    org.springframework.web: info
```

## 前端配置 (vite.config.js)

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
