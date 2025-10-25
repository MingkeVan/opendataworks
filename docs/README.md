# 文档索引

OpenDataWorks 的所有文档均收敛到三个目录：

1. `docs/handbook` —— 产品/架构/开发/运维/测试/专题指南
2. `docs/reports` —— 修复报告、浏览器测试、完成情况速记
3. `docs/site` —— GitHub Pages 站点源码与部署说明

## 📚 手册 (Handbook)

| 分类 | 说明 |
| --- | --- |
| [overview.md](handbook/overview.md) | 品牌、价值、术语、路线图
| [architecture.md](handbook/architecture.md) | 系统组件、数据流、运行态
| [data-model-and-sql.md](handbook/data-model-and-sql.md) | 命名规范、核心表、脚本位置、SQL 策略
| [development-guide.md](handbook/development-guide.md) | 本地开发环境、数据库初始化、服务启动
| [operations-guide.md](handbook/operations-guide.md) | Docker Compose、离线包、systemd、镜像构建
| [testing-guide.md](handbook/testing-guide.md) | 手工/自动测试、巡检脚本、缺陷记录
| [features/](handbook/features) | Doris 统计增强、图表实现、任务状态、表单优化等专题文档

## 🧪 报告 (Reports)

| 文档 | 内容 |
| --- | --- |
| [COMPLETION_REPORT.md](reports/COMPLETION_REPORT.md) | 里程碑交付清单 |
| [FIX_SUMMARY.md](reports/FIX_SUMMARY.md) | 关键缺陷修复记录 |
| [WORKFLOW_CODE_MISMATCH_FIX.md](reports/WORKFLOW_CODE_MISMATCH_FIX.md) | Dolphin 工作流编码修复说明 |
| [BROWSER_TEST_RESULTS.md](reports/BROWSER_TEST_RESULTS.md) | 前端多浏览器测试结果 |
| [TEST_REPORT.md](reports/TEST_REPORT.md) | 综合测试报告 |

## 🌐 站点 (Site)

- [site/index.html](site/index.html) —— GitHub Pages 主页源文件
- [site/README.md](site/README.md) —— 本地预览、部署建议
- [site/GITHUB_PAGES_SETUP.md](site/GITHUB_PAGES_SETUP.md) —— Pages 配置、SEO、域名绑定
- `_config.yml` —— Pages 构建配置 (Cayman 主题)

> 历史目录 `design/`、`guides/`、`deployment/` 已被合并并在 Git 历史中保留，如需追溯可通过 `git show <commit>:docs/...` 查看。
