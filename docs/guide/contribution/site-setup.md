# GitHub Pages 项目主页设置指南

本文档说明如何为 OpenDataWorks 项目启用 GitHub Pages 官方主页。

## 📋 概述

项目主页位于 `docs/index.html`，通过 GitHub Pages 托管，提供专业的项目展示和宣传页面。

## 🚀 启用 GitHub Pages

### 1. 在 GitHub 仓库中启用 Pages

1. 访问 GitHub 仓库: https://github.com/MingkeVan/opendataworks
2. 点击 **Settings** (设置)
3. 在左侧菜单找到 **Pages**
4. 在 **Source** (源) 部分:
   - Branch: 选择 `main` 分支
   - Folder: 选择 `/docs` 目录
   - 点击 **Save**

### 2. 等待部署

GitHub 会自动构建和部署网站，通常需要 1-2 分钟。

部署成功后，你会看到：
```
Your site is live at https://mingkevan.github.io/opendataworks/
```

### 3. 访问项目主页

- **主页 URL**: https://mingkevan.github.io/opendataworks/
- **完整 URL**: https://mingkevan.github.io/opendataworks/index.html

## 📁 项目主页文件结构

```
docs/
├── index.html              # 项目主页
├── assets/                 # 静态资源 (图标、图片)
├── _config.yml             # GitHub Pages 配置
├── guide/                  # 模块化文档 (Start, Architecture, Config, Manual, Contribution, FAQ)
├── handbook/               # 详细手册
└── guide/contribution/site-setup.md   # 本文档
```

## 🎨 主页特性

### 已实现功能

- ✅ **响应式设计**: 适配桌面、平板、手机
- ✅ **现代化 UI**: 渐变背景、卡片式布局、阴影效果
- ✅ **流畅动画**: 滚动动画、悬浮效果
- ✅ **完整内容**:
  - Hero 区域 (项目标题、描述、徽章、CTA 按钮)
  - 核心功能展示 (6 个功能卡片)
  - 统计数据 (4 个关键指标)
  - 快速开始指南 (代码示例)
  - 系统架构图 (ASCII 图形)
  - 技术栈展示 (前端、后端、中间服务)
  - CTA 行动号召区域
  - Footer 导航 (文档、社区、关于、相关项目)

### 设计风格

- **配色方案**: 蓝色-紫色渐变主题
- **字体**: 系统字体栈 (PingFang SC, Microsoft YaHei 等)
- **布局**: 居中容器 (最大宽度 1200px)
- **交互**: 平滑滚动、悬浮动画、响应式导航

## 🔧 自定义主页

### 修改内容

编辑 `docs/index.html` 文件：

```html
<!-- 修改项目标题 -->
<h1>OpenDataWorks</h1>

<!-- 修改项目描述 -->
<p>一站式数据任务管理与数据血缘可视化平台</p>

<!-- 修改功能卡片 -->
<div class="feature-card">
    <div class="feature-icon">📊</div>
    <h3>功能标题</h3>
    <p>功能描述...</p>
</div>
```

### 修改样式

在 `<style>` 标签中修改 CSS 变量：

```css
:root {
    --primary-color: #3b82f6;      /* 主色调 */
    --primary-dark: #2563eb;        /* 主色调（深色） */
    --secondary-color: #8b5cf6;     /* 次要色调 */
    --success-color: #10b981;       /* 成功色 */
    /* ... */
}
```

### 添加新区域

在现有 `<section>` 标签之间插入新的区域：

```html
<section id="new-section">
    <div class="container">
        <h2 class="section-title">新区域标题</h2>
        <p class="section-subtitle">副标题</p>
        <!-- 内容 -->
    </div>
</section>
```

## 📊 主页统计

### 集成 Google Analytics (可选)

1. 获取 Google Analytics ID (如 `G-XXXXXXXXXX`)
2. 在 `docs/_config.yml` 中添加:
   ```yaml
   google_analytics: G-XXXXXXXXXX
   ```
3. 在 `index.html` 的 `<head>` 标签中添加 GA 代码

### 添加访问统计徽章

在 README.md 中添加：

```markdown
[![GitHub Pages](https://img.shields.io/badge/docs-GitHub%20Pages-blue)](https://mingkevan.github.io/opendataworks/)
```

## 🌐 自定义域名 (可选)

如果你有自定义域名（如 `opendataworks.com`）：

### 1. 添加 CNAME 文件

创建 `docs/CNAME` 文件：
```
opendataworks.com
```

### 2. 配置 DNS

在域名提供商处添加 DNS 记录：

```
类型: CNAME
主机: www
值: mingkevan.github.io
```

或者使用 A 记录：
```
类型: A
主机: @
值: 185.199.108.153
值: 185.199.109.153
值: 185.199.110.153
值: 185.199.111.153
```

### 3. 在 GitHub Pages 设置中配置自定义域名

在 Settings → Pages → Custom domain 输入你的域名。

## 🔍 SEO 优化

主页已包含以下 SEO 优化：

- ✅ **Meta 标签**: 标题、描述、关键词
- ✅ **Open Graph**: Facebook 分享优化
- ✅ **Twitter Card**: Twitter 分享优化
- ✅ **语义化 HTML**: 正确使用 header, nav, section, footer 标签
- ✅ **响应式设计**: 移动端友好

### 进一步优化

1. **添加 sitemap.xml**:
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
     <url>
       <loc>https://mingkevan.github.io/opendataworks/</loc>
       <changefreq>weekly</changefreq>
       <priority>1.0</priority>
     </url>
   </urlset>
   ```

2. **添加 robots.txt**:
   ```
   User-agent: *
   Allow: /
   Sitemap: https://mingkevan.github.io/opendataworks/sitemap.xml
   ```

## 📱 社交媒体分享

### 添加分享图片

创建 `docs/og-image.png` (推荐尺寸: 1200x630px)

在 `index.html` 的 `<head>` 中添加:
```html
<meta property="og:image" content="https://mingkevan.github.io/opendataworks/og-image.png">
<meta property="twitter:image" content="https://mingkevan.github.io/opendataworks/og-image.png">
```

## 🚧 故障排查

### 问题1: 页面 404

**原因**: GitHub Pages 未正确启用或路径错误

**解决**:
1. 检查 Settings → Pages 是否启用
2. 确认源分支是 `main`，目录是 `/docs`
3. 检查文件路径是否正确

### 问题2: 样式未生效

**原因**: CSS 路径错误或文件未加载

**解决**:
1. 检查 `<style>` 标签是否在 `<head>` 中
2. 使用浏览器开发者工具检查 CSS 是否加载
3. 清除浏览器缓存

### 问题3: 链接跳转错误

**原因**: 相对路径配置错误

**解决**:
1. 使用相对路径 `../README.md` 而不是绝对路径
2. 检查 `_config.yml` 中的 `baseurl` 配置

## 📚 参考资源

- [GitHub Pages 官方文档](https://docs.github.com/en/pages)
- [Jekyll 主题](https://pages.github.com/themes/)
- [自定义域名指南](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site)
- [Open Graph 协议](https://ogp.me/)
- [Twitter Cards](https://developer.twitter.com/en/docs/twitter-for-websites/cards/overview/abouts-cards)

## ✅ 检查清单

部署前检查：

- [ ] 确认 `docs/index.html` 文件存在且内容完整
- [ ] 确认 `docs/_config.yml` 配置正确
- [ ] 在 GitHub Settings → Pages 启用 Pages (源: main /docs)
- [ ] 等待部署完成（查看 Actions 标签）
- [ ] 访问 https://mingkevan.github.io/opendataworks/ 验证
- [ ] 测试所有链接是否正常工作
- [ ] 在不同设备/浏览器测试响应式设计
- [ ] 检查 SEO meta 标签是否正确
- [ ] 更新 README.md 添加主页链接

## 🎉 完成

恭喜！你的项目主页已经上线。

访问 https://mingkevan.github.io/opendataworks/ 查看效果。

如有问题，请参考 [GitHub Pages 文档](https://docs.github.com/en/pages) 或提交 [Issue](https://github.com/MingkeVan/opendataworks/issues)。

---

**最后更新**: 2025-10-20
**维护者**: OpenDataWorks Team
