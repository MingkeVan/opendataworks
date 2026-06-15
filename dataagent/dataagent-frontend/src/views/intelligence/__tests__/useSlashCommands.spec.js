import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

import {
  parseSlashQuery,
  filterCommands,
  buildSkillCommand,
  buildSkillCommands,
  useSlashCommands,
} from '../useSlashCommands'

describe('parseSlashQuery', () => {
  it('returns the token after a leading slash', () => {
    expect(parseSlashQuery('/')).toBe('')
    expect(parseSlashQuery('/clear')).toBe('clear')
    expect(parseSlashQuery('/opendataworks-platform-tools')).toBe('opendataworks-platform-tools')
  })

  it('stays out of command mode for prose and mid-text slashes', () => {
    expect(parseSlashQuery('')).toBeNull()
    expect(parseSlashQuery('hello')).toBeNull()
    expect(parseSlashQuery('/clear table')).toBeNull() // contains whitespace
    expect(parseSlashQuery('请使用 a/b 路径')).toBeNull()
  })
})

describe('filterCommands', () => {
  const commands = [
    { id: '/clear', label: '清空输入' },
    { id: '/new', label: '新建话题' },
    { id: '/sales-skill', label: 'sales-skill' },
  ]

  it('returns all commands for an empty query', () => {
    expect(filterCommands(commands, '')).toHaveLength(3)
  })

  it('matches case-insensitively on id and label', () => {
    expect(filterCommands(commands, 'CLE').map((c) => c.id)).toEqual(['/clear'])
    expect(filterCommands(commands, 'sales').map((c) => c.id)).toEqual(['/sales-skill'])
  })
})

describe('buildSkillCommands', () => {
  it('builds a skill command per folder, displays the name, and de-dupes', () => {
    const cmds = buildSkillCommands(['my-skill', 'my-skill', ''])
    expect(cmds).toHaveLength(1)
    expect(cmds[0]).toMatchObject({ id: '/my-skill', type: 'skill' })
    // Selecting autocompletes the skill name as the command token.
    expect(cmds[0].insertText).toBe('/my-skill ')
  })

  it('ignores blank input', () => {
    expect(buildSkillCommand('')).toBeNull()
    expect(buildSkillCommands(null)).toEqual([])
  })
})

describe('useSlashCommands', () => {
  function setup(commands) {
    const inputText = ref('')
    const focusInput = vi.fn()
    const slash = useSlashCommands({ getCommands: () => commands, inputText, focusInput })
    return { slash, inputText, focusInput }
  }

  const cmds = [
    { id: '/clear', type: 'builtin', label: '清空输入', run: vi.fn() },
    { id: '/my-skill', type: 'skill', label: '', insertText: '/my-skill ' },
  ]

  it('opens and filters from the current input', () => {
    const { slash, inputText } = setup(cmds)
    inputText.value = '/'
    slash.syncFromInput()
    expect(slash.visible.value).toBe(true)
    expect(slash.filtered.value).toHaveLength(2)

    inputText.value = '/cle'
    slash.syncFromInput()
    expect(slash.filtered.value.map((c) => c.id)).toEqual(['/clear'])

    inputText.value = 'normal text'
    slash.syncFromInput()
    expect(slash.visible.value).toBe(false)
  })

  it('runs a built-in command and clears the token', () => {
    const run = vi.fn()
    const { slash, inputText } = setup([{ id: '/clear', type: 'builtin', label: 'x', run }])
    inputText.value = '/clear'
    slash.syncFromInput()
    slash.select(slash.filtered.value[0])
    expect(run).toHaveBeenCalledOnce()
    expect(inputText.value).toBe('')
    expect(slash.visible.value).toBe(false)
  })

  it('autocompletes the skill name and focuses for a skill command', () => {
    const { slash, inputText, focusInput } = setup(cmds)
    inputText.value = '/my'
    slash.syncFromInput()
    slash.select(slash.filtered.value[0])
    expect(inputText.value).toBe('/my-skill ')
    expect(focusInput).toHaveBeenCalledOnce()
  })

  it('navigates with arrows and selects with Enter, consuming the event', () => {
    const run = vi.fn()
    const { slash, inputText } = setup([
      { id: '/a', type: 'builtin', label: 'a', run: vi.fn() },
      { id: '/b', type: 'builtin', label: 'b', run },
    ])
    inputText.value = '/'
    slash.syncFromInput()

    const down = { key: 'ArrowDown', preventDefault: vi.fn() }
    expect(slash.handleKeydown(down)).toBe(true)
    expect(down.preventDefault).toHaveBeenCalled()
    expect(slash.activeIndex.value).toBe(1)

    const enter = { key: 'Enter', preventDefault: vi.fn() }
    expect(slash.handleKeydown(enter)).toBe(true)
    expect(run).toHaveBeenCalledOnce()
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
