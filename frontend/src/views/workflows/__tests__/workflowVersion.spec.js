import { describe, it, expect } from 'vitest'
import {
  formatDolphinConfigOption,
  rollbackDisabledReason,
  versionDeleteDisabledReason,
} from '../workflowVersion'

describe('formatDolphinConfigOption', () => {
  it('builds a label with default/inactive tags', () => {
    expect(formatDolphinConfigOption(null)).toBe('-')
    expect(formatDolphinConfigOption({ id: 7, isActive: 1 })).toBe('Dolphin #7')
    expect(formatDolphinConfigOption({ id: 7 })).toBe('Dolphin #7 / 停用') // missing isActive → 停用
    expect(formatDolphinConfigOption({ configName: 'prod', isDefault: 1, isActive: 1 })).toBe('prod / 默认')
    expect(formatDolphinConfigOption({ configName: 'old', isActive: 0 })).toBe('old / 停用')
    expect(formatDolphinConfigOption({ configName: 'p', isDefault: 1, isActive: 0 })).toBe('p / 默认 / 停用')
  })
})

describe('rollbackDisabledReason', () => {
  it('blocks non-V3, current version, and allows otherwise', () => {
    expect(rollbackDisabledReason(null, 1)).toBe('无效版本')
    expect(rollbackDisabledReason({ id: 2, isV3: false }, 1)).toBe('仅支持 V3，请先保存生成 V3 基线')
    expect(rollbackDisabledReason({ id: 2, snapshotSchemaVersion: 2 }, 1)).toBe('仅支持 V3，请先保存生成 V3 基线')
    expect(rollbackDisabledReason({ id: 1, isV3: true }, 1)).toBe('当前版本无需恢复')
    expect(rollbackDisabledReason({ id: 2, isCurrent: true, isV3: true }, 1)).toBe('当前版本无需恢复')
    expect(rollbackDisabledReason({ id: 2, snapshotSchemaVersion: 3 }, 1)).toBe('')
  })
})

describe('versionDeleteDisabledReason', () => {
  it('blocks invalid, current, and last-published versions', () => {
    expect(versionDeleteDisabledReason({ id: 'x' }, 1, null)).toBe('无效版本')
    expect(versionDeleteDisabledReason({ id: 5 }, 5, null)).toBe('当前版本不可删除')
    expect(versionDeleteDisabledReason({ id: 5 }, 1, 5)).toBe('最后一次成功发布版本不可删除')
    expect(versionDeleteDisabledReason({ id: 5 }, 1, null)).toBe('')
    expect(versionDeleteDisabledReason({ id: 5 }, 1, 9)).toBe('')
  })
})
