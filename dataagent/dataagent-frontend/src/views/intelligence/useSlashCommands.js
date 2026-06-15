// Slash-command support shared by the NL2SQL chat input surfaces (the portal
// NL2SqlChatV2.vue and the embeddable WidgetChat.vue).
//
// Skills have no native "command" protocol: the agent picks them from their
// SKILL.md description. A skill command therefore inserts a natural-language
// directive ("请使用「<folder>」技能：") that nudges the agent to use that skill,
// which the user then completes and sends. Built-in commands (e.g. /clear, /new)
// run a callback directly. The whole feature is frontend-only.

import { computed, ref } from 'vue'

// Command mode is entered only when the entire input is a single leading-slash
// token with no whitespace, e.g. "/", "/clear", "/opendataworks-platform-tools".
// This avoids clashing with slashes inside normal prose (paths, dates, fractions).
const SLASH_QUERY_RE = /^\/(\S*)$/

// Return the query after the leading "/" when the text is in command mode,
// otherwise null. "/" yields "" (show every command).
export function parseSlashQuery(text) {
  const match = SLASH_QUERY_RE.exec(String(text ?? ''))
  return match ? match[1] : null
}

// Case-insensitive substring match against the command id and label.
export function filterCommands(commands, query) {
  const list = Array.isArray(commands) ? commands : []
  const kw = String(query ?? '').trim().toLowerCase()
  if (!kw) return list.slice()
  return list.filter((cmd) => {
    const id = String(cmd?.id || '').toLowerCase()
    const label = String(cmd?.label || '').toLowerCase()
    return id.includes(kw) || label.includes(kw)
  })
}

// Build a skill command from its folder name. The folder name is the canonical
// identifier the agent and runtime use, and is what gets displayed as the skill
// name. Selecting it autocompletes the command token ("/<skill> ") into the
// input — the name stays visible — so the user can append their request.
export function buildSkillCommand(folder) {
  const name = String(folder || '').trim()
  if (!name) return null
  return {
    id: '/' + name,
    type: 'skill',
    label: '',
    hint: '技能',
    insertText: '/' + name + ' ',
  }
}

export function buildSkillCommands(folders) {
  const seen = new Set()
  const result = []
  for (const folder of Array.isArray(folders) ? folders : []) {
    const cmd = buildSkillCommand(folder)
    if (cmd && !seen.has(cmd.id)) {
      seen.add(cmd.id)
      result.push(cmd)
    }
  }
  return result
}

// Composable wiring the menu state to a textarea. `getCommands` returns the live
// command list, `inputText` is the shared engine ref, `focusInput` re-focuses the
// textarea after a skill directive is inserted.
export function useSlashCommands({ getCommands, inputText, focusInput }) {
  const resolveCommands = () => (typeof getCommands === 'function' ? getCommands() || [] : [])

  const visible = ref(false)
  const query = ref('')
  const activeIndex = ref(0)

  const filtered = computed(() => filterCommands(resolveCommands(), query.value))

  function close() {
    visible.value = false
    query.value = ''
    activeIndex.value = 0
  }

  // Recompute menu state from the current input. Call on every input event.
  function syncFromInput() {
    const q = parseSlashQuery(inputText.value)
    if (q === null) {
      close()
      return
    }
    query.value = q
    const list = filterCommands(resolveCommands(), q)
    visible.value = list.length > 0
    if (activeIndex.value >= list.length) activeIndex.value = 0
  }

  function setActive(index) {
    const n = filtered.value.length
    if (!n) return
    activeIndex.value = ((index % n) + n) % n
  }

  function move(delta) {
    setActive(activeIndex.value + delta)
  }

  function select(cmd) {
    if (!cmd) return
    close()
    if (cmd.type === 'builtin') {
      inputText.value = ''
      if (typeof cmd.run === 'function') cmd.run()
      return
    }
    // skill (and any future insert-style command)
    inputText.value = cmd.insertText || ''
    if (typeof focusInput === 'function') focusInput()
  }

  function selectActive() {
    select(filtered.value[activeIndex.value])
  }

  // Returns true when the keystroke was consumed by the menu, so the caller can
  // skip its own Enter-to-send handling.
  function handleKeydown(event) {
    if (!visible.value || !filtered.value.length) return false
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        move(1)
        return true
      case 'ArrowUp':
        event.preventDefault()
        move(-1)
        return true
      case 'Enter':
      case 'Tab':
        if (event.isComposing || event.keyCode === 229) return false
        event.preventDefault()
        selectActive()
        return true
      case 'Escape':
        event.preventDefault()
        close()
        return true
      default:
        return false
    }
  }

  return { visible, query, activeIndex, filtered, syncFromInput, handleKeydown, select, selectActive, setActive, close }
}
