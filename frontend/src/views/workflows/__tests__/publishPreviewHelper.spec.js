import {
  buildConsistencyIssueHtml,
  buildPublishPreviewHtml,
  buildPublishRepairHtml,
  resolvePublishVersionId,
  shouldPromptOnlineAfterDeploy,
  splitRepairIssues
} from '../publishPreviewHelper'
import { buildTaskFieldDiffRows } from '../publishPreviewDiffHelper'

describe('publishPreviewHelper', () => {
  it('renders explicit before and after values for task field changes', () => {
    const html = buildPublishPreviewHtml({
      diffSummary: {
        taskModified: [
          {
            taskCode: 1001,
            taskName: 'sql_task',
            fieldChanges: [
              {
                field: 'task.sql',
                before: 'select *\nfrom ods.user_old',
                after: 'select *\nfrom ods.user_new'
              }
            ]
          }
        ]
      }
    })

    expect(html).toContain('变更前（运行态）')
    expect(html).toContain('变更后（平台）')
    expect(html).toContain('变更前为 Dolphin 运行态当前值，变更后为平台本次发布目标值。')
    expect(html).toContain('sql_task (1001)')
    expect(html).toContain('from ods.user_old')
    expect(html).toContain('from ods.user_new')
  })

  it('classifies added removed and modified task diff rows', () => {
    const rows = buildTaskFieldDiffRows(
      'select id\nfrom ods.user_old\nwhere dt = ${bizdate}',
      'select user_id\nfrom ods.user_new\nwhere dt = ${bizdate}\nlimit 10'
    )

    expect(rows.map(row => row.type)).toEqual(['modified', 'modified', 'added'])
    expect(rows[0].left.lineNumber).toBe(1)
    expect(rows[0].right.lineNumber).toBe(1)
    expect(rows.some((row) => (
      row.type === 'modified'
      && (
        row.left.segments.some(segment => segment.changed)
        || row.right.segments.some(segment => segment.changed)
      )
    ))).toBe(true)
    expect(rows[2].right.text).toBe('limit 10')
  })

  it('prefers last published version when resolving publish version id', () => {
    expect(resolvePublishVersionId({
      currentVersionId: 101,
      lastPublishedVersionId: 88
    })).toBe(88)

    expect(resolvePublishVersionId({
      currentVersionId: 101,
      lastPublishedVersionId: null
    })).toBe(101)

    expect(resolvePublishVersionId({})).toBeUndefined()
  })

  it('prompts for online after successful deploy even when stale row was already online', () => {
    expect(shouldPromptOnlineAfterDeploy({ id: 1, status: 'online' }, { status: 'success' })).toBe(true)
    expect(shouldPromptOnlineAfterDeploy({ id: 1, status: 'offline' }, { status: 'success' })).toBe(true)
    expect(shouldPromptOnlineAfterDeploy({ id: 1, status: 'offline' }, { status: 'pending_approval' })).toBe(false)
    expect(shouldPromptOnlineAfterDeploy(null, { status: 'success' })).toBe(false)
  })
})

describe('splitRepairIssues', () => {
  it('separates repairable issues from advisory ones', () => {
    const { repairable, advisory } = splitRepairIssues({
      repairIssues: [
        { code: 'PUBLISH_METADATA_REPAIR_RECOMMENDED', repairable: true, field: 'task.datasourceId' },
        { code: 'LINEAGE_SQL_RELATION_MISSING', repairable: false, field: 'task.lineage.missing' },
        { code: 'LINEAGE_DEFINITION_DRIFT', repairable: true, field: 'workflow.definitionJson' }
      ]
    })

    expect(repairable.map((issue) => issue.code)).toEqual([
      'PUBLISH_METADATA_REPAIR_RECOMMENDED',
      'LINEAGE_DEFINITION_DRIFT'
    ])
    // repairable=false 的问题修复动作解决不了，不能进"修复元数据并重试"流程
    expect(advisory.map((issue) => issue.code)).toEqual(['LINEAGE_SQL_RELATION_MISSING'])
  })

  it('treats a missing repairable flag as repairable, matching the previous behavior', () => {
    const { repairable, advisory } = splitRepairIssues({
      repairIssues: [{ code: 'PUBLISH_METADATA_REPAIR_RECOMMENDED', field: 'task.taskGroupId' }]
    })

    expect(repairable).toHaveLength(1)
    expect(advisory).toHaveLength(0)
  })

  it('returns empty lists for a preview without repair issues', () => {
    expect(splitRepairIssues({})).toEqual({ repairable: [], advisory: [] })
    expect(splitRepairIssues(null)).toEqual({ repairable: [], advisory: [] })
  })
})

describe('buildConsistencyIssueHtml', () => {
  it('renders task name and message for each issue', () => {
    const html = buildConsistencyIssueHtml(
      [{ taskName: 'dwd_order_di', taskCode: 1001, message: '缺少输入表 ods.orders(id=3)' }],
      '检测到血缘一致性问题。'
    )

    expect(html).toContain('检测到血缘一致性问题。')
    expect(html).toContain('dwd_order_di (1001)')
    expect(html).toContain('缺少输入表 ods.orders(id=3)')
  })

  it('caps the rendered list and reports the remainder', () => {
    const issues = Array.from({ length: 23 }, (_, index) => ({
      taskName: `task_${index}`,
      message: `问题 ${index}`
    }))

    const html = buildConsistencyIssueHtml(issues, '提示')

    expect(html).toContain('task_19')
    expect(html).not.toContain('task_20')
    expect(html).toContain('另有 3 项')
  })

  it('escapes issue text so messages cannot inject markup', () => {
    const html = buildConsistencyIssueHtml([{ taskName: '<img src=x>', message: '<b>bad</b>' }], '')

    expect(html).not.toContain('<img src=x>')
    expect(html).toContain('&lt;img src=x&gt;')
  })

  it('returns an empty string when there is nothing to show', () => {
    expect(buildConsistencyIssueHtml([], '提示')).toBe('')
    expect(buildConsistencyIssueHtml(null, '提示')).toBe('')
  })
})

describe('buildPublishRepairHtml', () => {
  it('renders exactly the repairIssues it is given, so callers must pre-filter', () => {
    // 该 helper 不做任何过滤。调用方必须只传可修复的问题，
    // 否则血缘告警会在只读提示之外，又出现在"修复元数据并重试"弹窗里——而修复动作根本修不了它们。
    const preview = {
      repairIssues: [
        { code: 'PUBLISH_METADATA_REPAIR_RECOMMENDED', repairable: true, field: 'task.datasourceId', message: '缺少 datasourceId' },
        { code: 'LINEAGE_SQL_RELATION_MISSING', repairable: false, field: 'task.lineage.missing', message: '缺少输入表' }
      ]
    }
    const { repairable } = splitRepairIssues(preview)

    const filtered = buildPublishRepairHtml({ ...preview, repairIssues: repairable })

    expect(filtered).toContain('缺少 datasourceId')
    expect(filtered).not.toContain('缺少输入表')
  })
})
