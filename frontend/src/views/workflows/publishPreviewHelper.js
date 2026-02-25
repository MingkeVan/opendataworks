const DIFF_SECTIONS = [
  { key: 'workflowFieldChanges', label: 'Workflow字段变更' },
  { key: 'taskAdded', label: '任务新增' },
  { key: 'taskRemoved', label: '任务删除' },
  { key: 'taskModified', label: '任务修改' },
  { key: 'edgeAdded', label: '边新增' },
  { key: 'edgeRemoved', label: '边删除' },
  { key: 'scheduleChanges', label: '调度变更' }
]

const MAX_RENDER_COUNT = 20

const escapeHtml = (text) => {
  const raw = String(text ?? '')
  return raw
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

const renderIssue = (issue) => {
  if (!issue) return ''
  const parts = []
  if (issue.code) parts.push(`<strong>${escapeHtml(issue.code)}</strong>`)
  if (issue.taskName) parts.push(`任务: ${escapeHtml(issue.taskName)}`)
  if (issue.message) parts.push(escapeHtml(issue.message))
  return `<li>${parts.join(' | ')}</li>`
}

const renderDiffList = (title, items = []) => {
  if (!Array.isArray(items) || !items.length) {
    return ''
  }
  const rendered = items
    .slice(0, MAX_RENDER_COUNT)
    .map((item) => `<li>${escapeHtml(item)}</li>`)
    .join('')
  const remain = items.length - MAX_RENDER_COUNT
  const more = remain > 0 ? `<li>... 另有 ${remain} 项</li>` : ''
  return `
    <div style="margin-top: 10px;">
      <div style="font-weight: 600; margin-bottom: 4px;">${escapeHtml(title)}（${items.length}）</div>
      <ul style="margin: 0 0 0 16px; max-height: 120px; overflow: auto; line-height: 1.5;">${rendered}${more}</ul>
    </div>
  `
}

export const buildPublishPreviewHtml = (preview) => {
  const summary = preview?.diffSummary || {}
  const sections = DIFF_SECTIONS
    .map(({ key, label }) => renderDiffList(label, summary[key] || []))
    .join('')

  const warnings = Array.isArray(preview?.warnings) && preview.warnings.length
    ? `
      <div style="margin-top: 8px; color: #e6a23c;">
        <div style="font-weight: 600; margin-bottom: 4px;">预检告警</div>
        <ul style="margin: 0 0 0 16px; line-height: 1.5;">${preview.warnings.map(renderIssue).join('')}</ul>
      </div>
    `
    : ''

  return `
    <div style="max-height: 65vh; overflow: auto; padding-right: 8px;">
      <div>检测到平台定义与 Dolphin 运行态存在差异，确认后将按平台定义发布。</div>
      ${warnings}
      ${sections}
    </div>
  `
}

export const firstPreviewErrorMessage = (preview) => {
  const first = Array.isArray(preview?.errors) ? preview.errors[0] : null
  return first?.message || '发布预检未通过'
}

export const isDialogCancel = (error) => {
  return error === 'cancel' || error === 'close'
}
