# Opendataagent CI 拆分实施计划

## Goal

把 `opendataagent-server` 与 `opendataagent-web` 镜像构建从主 `Build and Release` workflow 拆到独立 GitHub Actions workflow，降低主 workflow 职责和 main push 触发面。

## Scope

本轮覆盖：

- 新增 `.github/workflows/opendataagent-build.yml`
- 从 `.github/workflows/docker-build.yml` 的主矩阵中移除 `opendataagent-server/web`
- 保留 `opendataagent-server` 构建前的 root skills 同步步骤，但移动到新 workflow
- 更新主 summary，使其只列出主 workflow 实际构建的镜像
- 更新相关设计与计划文档

本轮不覆盖：

- 更改离线包压缩等级
- 拆分 GitHub Release 附件发布
- 更改 Docker Hub 命名空间、镜像名或平台矩阵

## Execution Steps

1. 新增设计文档：
   - `docs/design/2026-07-16-opendataagent-ci-split-design.md`
2. 新增实施计划：
   - `docs/plans/2026-07-16-opendataagent-ci-split-plan.md`
3. 新增 `opendataagent-build.yml`：
   - 定义 `opendataagent-server/web` 两个矩阵项
   - 复用主 workflow 的 metadata、buildx、cache、push 规则
   - 在 server 镜像构建前执行 `opendataagent/scripts/sync-root-skills.sh`
4. 修改 `docker-build.yml`：
   - 删除 `opendataagent-server/web` 矩阵项
   - 删除主矩阵中的 root skills 同步步骤
   - 删除主 summary 中的 `opendataagent` 镜像条目
5. 做 workflow 结构校验：
   - YAML 解析
   - grep 确认主矩阵不再包含 `opendataagent-server/web`
   - grep 确认新 workflow 包含两个 `opendataagent` 镜像

## Verification

- `python - <<'PY' ... yaml.safe_load(...) ... PY`
- `rg "opendataagent-(server|web)" .github/workflows`
- 若本地具备 actionlint，可运行 `actionlint .github/workflows/docker-build.yml .github/workflows/opendataagent-build.yml`

## Rollout

- 合并后，普通 main push 若未改动 `opendataagent/**` 或 `skills/**`，只跑主 build/release workflow。
- 改动 `opendataagent/**` 或 `skills/**` 时，会额外跑独立 `Build Opendataagent Images` workflow。
- tag `v*` 仍会触发两个 workflow，主 release 继续产出完整 release 附件。

## Backout

- 回退 `.github/workflows/opendataagent-build.yml`。
- 将 `opendataagent-server/web` 矩阵项和 root skills 同步步骤放回 `.github/workflows/docker-build.yml`。
- 恢复主 summary 中的 `opendataagent` 镜像条目。
