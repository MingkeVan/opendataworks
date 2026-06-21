// WorkflowDetail 版本/Dolphin 展示的纯判定工具（W3）。
// 纯函数、无 Vue 依赖、无副作用，便于单测。逻辑从 WorkflowDetail.vue 逐字抽出（把原先读取
// 的组件状态改为入参注入），组件保留同名薄包装传入 ref 值，调用点（含模板）保持不变。

export const formatDolphinConfigOption = (item) => {
  if (!item) {
    return '-'
  }
  const parts = [item.configName || `Dolphin #${item.id}`]
  if (item.isDefault === 1) {
    parts.push('默认')
  }
  if (!item.isActive) {
    parts.push('停用')
  }
  return parts.join(' / ')
}

export const rollbackDisabledReason = (row, currentVersionId) => {
  if (!row) {
    return '无效版本'
  }
  const schemaVersion = Number(row?.snapshotSchemaVersion)
  const isV3 = row?.isV3 === true || (Number.isFinite(schemaVersion) ? schemaVersion === 3 : false)
  if (!isV3) {
    return '仅支持 V3，请先保存生成 V3 基线'
  }
  const rowVersionId = Number(row?.id)
  const current = Number(currentVersionId)
  if (row?.isCurrent || (Number.isFinite(rowVersionId) && rowVersionId === current)) {
    return '当前版本无需恢复'
  }
  return ''
}

export const versionDeleteDisabledReason = (row, currentVersionId, lastPublishedVersionId) => {
  const versionId = Number(row?.id)
  if (!Number.isFinite(versionId)) {
    return '无效版本'
  }
  const current = Number(currentVersionId)
  if (Number.isFinite(current) && current === versionId) {
    return '当前版本不可删除'
  }
  if (lastPublishedVersionId !== null && versionId === Number(lastPublishedVersionId)) {
    return '最后一次成功发布版本不可删除'
  }
  return ''
}
