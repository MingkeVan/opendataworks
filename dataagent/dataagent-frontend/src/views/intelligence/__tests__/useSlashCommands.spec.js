import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

import {
  parseSlashQuery,
  filterCommands,
  buildCommand,
  buildCommands,
  useSlashCommands,
} from '../useSlashCommands'

describe('parseSlashQuery', () => {
  it('returns the token after a leading slash', () => {
    expect(parseSlashQuery('/')).toBe('')
    expect(parseSlashQuery('/compact')).toBe('compact')
    expect(parseSlashQuery('/opendataworks-platform-tools')).toBe('opendataworks-platform-tools')
  })

  it('stays out of command mode for prose and mid-text slashes', () => {
    expect(parseSlashQuery('')).toBeNull()
    expect(parseSlashQuery('hello')).toBeNull()
    expect(parseSlashQuery('/compact now')).toBeNull() // contains whitespace
    expect(parseSlashQuery('请使用 a/b 路径')).toBeNull()
  })
})

describe('filterCommands', () => {
  const commands = [
    { id: '/compact', label: '压缩对话历史' },
    { id: '/context', label: '查看上下文用量' },
    { id: '/sales-skill', label: '' },
  ]

  it('returns all commands for an empty query', () => {
    expect(filterCommands(commands, '')).toHaveLength(3)
  })

  it('matches case-insensitively on id and label', () => {
    expect(filterCommands(commands, 'COMPA').map((c) => c.id)).toEqual(['/compact'])
    expect(filterCommands(commands, 'sales').map((c) => c.id)).toEqual(['/sales-skill'])
    expect(filterCommands(commands, '用量').map((c) => c.id)).toEqual(['/context'])
  })
})

describe('buildCommands', () => {
  it('builds a command per name, hints built-ins, and de-dupes', () => {
    const cmds = buildCommands(['compact', 'my-skill', 'my-skill', '', '/usage'])
    expect(cmds.map((c) => c.id)).toEqual(['/compact', '/my-skill', '/usage'])
    // Selecting autocompletes the command token.
    expect(cmds[0].insertText).toBe('/compact ')
    // Known SDK built-ins get a friendly hint; others fall back to a generic one.
    expect(cmds[0].hint).toBe('压缩对话历史')
    expect(cmds[1].hint).toBe('技能')
    // A leading slash in the source name is tolerated.
    expect(cmds[2].id).toBe('/usage')
  })

  it('ignores blank input', () => {
    expect(buildCommand('')).toBeNull()
    expect(buildCommands(null)).toEqual([])
  })
})

describe('useSlashCommands', () => {
  function setup(commands) {
    const inputText = ref('')
    const focusInput = vi.fn()
    const slash = useSlashCommands({ getCommands: () => commands, inputText, focusInput })
    return { slash, inputText, focusInput }
  }

  const cmds = buildCommands(['compact', 'my-skill'])

  it('opens and filters from the current input', () => {
    const { slash, inputText } = setup(cmds)
    inputText.value = '/'
    slash.syncFromInput()
    expect(slash.visible.value).toBe(true)
    expect(slash.filtered.value).toHaveLength(2)

    inputText.value = '/comp'
    slash.syncFromInput()
    expect(slash.filtered.value.map((c) => c.id)).toEqual(['/compact'])

    inputText.value = 'normal text'
    slash.syncFromInput()
    expect(slash.visible.value).toBe(false)
  })

  it('autocompletes the command token and focuses on select', () => {
    const { slash, inputText, focusInput } = setup(cmds)
    inputText.value = '/my'
    slash.syncFromInput()
    slash.select(slash.filtered.value[0])
    expect(inputText.value).toBe('/my-skill ')
    expect(focusInput).toHaveBeenCalledOnce()
    expect(slash.visible.value).toBe(false)
  })

  it('navigates with arrows and selects with Enter, consuming the event', () => {
    const { slash, inputText } = setup(cmds)
    inputText.value = '/'
    slash.syncFromInput()

    const down = { key: 'ArrowDown', preventDefault: vi.fn() }
    expect(slash.handleKeydown(down)).toBe(true)
    expect(down.preventDefault).toHaveBeenCalled()
    expect(slash.activeIndex.value).toBe(1)

    const enter = { key: 'Enter', preventDefault: vi.fn() }
    expect(slash.handleKeydown(enter)).toBe(true)
    expect(inputText.value).toBe('/my-skill ')
  })

  it('does not consume keys when the menu is closed', () => {
    const { slash } = setup(cmds)
    expect(slash.handleKeydown({ key: 'Enter', preventDefault: vi.fn() })).toBe(false)
  })

  it('closes on Escape', () => {
    const { slash, inputText } = setup(cmds)
    inputText.value = '/'
    slash.syncFromInput()
    expect(slash.visible.value).toBe(true)
    expect(slash.handleKeydown({ key: 'Escape', preventDefault: vi.fn() })).toBe(true)
    expect(slash.visible.value).toBe(false)
  })
})
