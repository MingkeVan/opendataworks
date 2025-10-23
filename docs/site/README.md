# OneData Works 项目主页

本目录包含 OneData Works 项目的官方主页，通过 GitHub Pages 托管。

## 📁 文件说明

- `index.html` - 项目主页（单页 HTML 应用）

## 🌐 在线访问

- **主页 URL**: https://mingkevan.github.io/opendataworks/
- **备用 URL**: https://mingkevan.github.io/opendataworks/site/ (子目录访问)

## 🎨 主页特点

### 设计理念

借鉴主流开源项目（Apache 项目、Spring、Vue.js 等）的设计风格：

1. **清晰的信息层次**
   - Hero 区域：项目名称、口号、快速行动按钮
   - 功能展示：6 个核心功能卡片
   - 快速开始：代码示例和部署命令
   - 系统架构：技术栈和架构图
   - 行动号召：引导用户参与

2. **现代化视觉**
   - 渐变背景
   - 卡片式布局
   - 阴影和悬浮效果
   - 响应式设计（适配桌面、平板、手机）

3. **流畅交互**
   - 平滑滚动
   - 进入动画
   - 悬浮效果
   - 可点击元素的视觉反馈

### 技术实现

- **纯 HTML + CSS + JavaScript**: 无需构建工具，直接部署
- **零依赖**: 不依赖外部 CSS/JS 库
- **响应式**: 使用 CSS Grid 和 Flexbox
- **SEO 优化**: Meta 标签、Open Graph、Twitter Card
- **性能优化**: 内联样式和脚本，减少 HTTP 请求

## 🔧 本地预览

### 方法 1: 使用 Python HTTP 服务器

```bash
cd docs/site
python3 -m http.server 8000

# 访问 http://localhost:8000
```

### 方法 2: 使用 Node.js HTTP 服务器

```bash
cd docs/site
npx serve .

# 访问提示的 URL
```

### 方法 3: 直接打开文件

在浏览器中打开 `index.html` 文件（某些功能可能受限）

## 📝 修改主页

### 1. 编辑内容

直接编辑 `index.html` 文件：

```html
<!-- 修改标题 -->
<h1>OneData Works</h1>

<!-- 修改描述 -->
<p>一站式数据任务管理与数据血缘可视化平台</p>
```

### 2. 修改样式

在 `<style>` 标签中修改 CSS：

```css
:root {
    --primary-color: #3b82f6;  /* 修改主色调 */
}
```

### 3. 添加内容

在现有 section 之间插入新内容：

```html
<section id="new-section">
    <div class="container">
        <!-- 新内容 -->
    </div>
</section>
```

## 🚀 部署

主页通过 GitHub Pages 自动部署：

1. 提交更改到 `main` 分支
2. GitHub Actions 自动构建
3. 1-2 分钟后生效

查看部署状态：https://github.com/MingkeVan/opendataworks/actions

## 📚 相关文档

- [GitHub Pages 设置指南](../GITHUB_PAGES_SETUP.md)
- [项目文档](../../README.md)
- [部署指南](../../docs/deployment/DEPLOYMENT.md)

## 💡 设计灵感来源

- [Apache DolphinScheduler](https://dolphinscheduler.apache.org/)
- [Spring Framework](https://spring.io/)
- [Vue.js](https://vuejs.org/)
- [Tailwind CSS](https://tailwindcss.com/)
- [Vercel](https://vercel.com/)

## 🤝 贡献

欢迎改进主页设计和内容！

1. Fork 项目
2. 修改 `index.html`
3. 本地预览测试
4. 提交 Pull Request

## 📞 反馈

如有问题或建议，请提交 [Issue](https://github.com/MingkeVan/opendataworks/issues)。

---

**最后更新**: 2025-10-20
