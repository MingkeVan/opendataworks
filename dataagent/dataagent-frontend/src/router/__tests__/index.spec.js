import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { routes } from '../index'

const buildRouter = () => createRouter({
  history: createMemoryHistory(),
  routes
})

const resolveTo = async (location) => {
  const router = buildRouter()
  await router.push(location)
  await router.isReady()
  const current = router.currentRoute.value
  return {
    path: current.path,
    query: current.query,
    hash: current.hash,
    name: current.name,
    params: current.params
  }
}

describe('DataAgent page routing', () => {
  it('redirects the root path to the readable chat URL and preserves context', async () => {
    const route = await resolveTo('/?topic_id=topic-1#message-2')
    expect(route.path).toBe('/chat')
    expect(route.name).toBe('IntelligentQueryChat')
    expect(route.query).toEqual({ topic_id: 'topic-1' })
    expect(route.hash).toBe('#message-2')
  })

  it.each([
    ['/chat', 'IntelligentQueryChat'],
    ['/skills', 'IntelligentQuerySkills'],
    ['/agents', 'IntelligentQueryAgents'],
    ['/models', 'IntelligentQueryModels'],
    ['/widget-access', 'IntelligentQueryWidget']
  ])('resolves canonical route %s', async (path, name) => {
    const route = await resolveTo(path)
    expect(route.path).toBe(path)
    expect(route.name).toBe(name)
  })

  it('keeps canonical business paths independent from the deployment base', () => {
    const router = createRouter({
      history: createMemoryHistory('/dataagent/'),
      routes
    })

    expect(router.resolve('/chat').href).toBe('/dataagent/chat')
    expect(router.resolve('/skills/marketing-insights').href).toBe('/dataagent/skills/marketing-insights')
  })

  it('migrates a legacy ?tab= link and drops only the tab param', async () => {
    const route = await resolveTo('/intelligent-query?tab=skills&source=bookmark#recent')
    expect(route.path).toBe('/skills')
    expect(route.query.tab).toBeUndefined()
    expect(route.query).toEqual({ source: 'bookmark' })
    expect(route.hash).toBe('#recent')
  })

  it('migrates the bare legacy section path to chat', async () => {
    const route = await resolveTo('/intelligent-query?topic_id=topic-1')
    expect(route.path).toBe('/chat')
    expect(route.query).toEqual({ topic_id: 'topic-1' })
  })

  it.each([
    ['/intelligent-query/chat', '/chat', 'IntelligentQueryChat'],
    ['/intelligent-query/skills', '/skills', 'IntelligentQuerySkills'],
    ['/intelligent-query/agents', '/agents', 'IntelligentQueryAgents'],
    ['/intelligent-query/models', '/models', 'IntelligentQueryModels']
  ])('migrates legacy page %s to %s', async (legacyPath, canonicalPath, name) => {
    const route = await resolveTo(legacyPath)
    expect(route.path).toBe(canonicalPath)
    expect(route.name).toBe(name)
  })

  it('removes the legacy /nl2sql implementation term from the final URL', async () => {
    const route = await resolveTo('/nl2sql?tab=skills')
    expect(route.path).toBe('/skills')
    expect(route.query.tab).toBeUndefined()
  })

  it('routes /nl2sql without a tab to chat while keeping other params', async () => {
    const route = await resolveTo('/nl2sql?topic_id=topic-1')
    expect(route.path).toBe('/chat')
    expect(route.query).toEqual({ topic_id: 'topic-1' })
  })

  it('resolves canonical detail routes', async () => {
    const route = await resolveTo('/skills/marketing-insights')
    expect(route.name).toBe('IntelligentQuerySkillDetail')
    expect(route.path).toBe('/skills/marketing-insights')
    expect(route.params.folder).toBe('marketing-insights')
  })

  it('migrates a legacy detail link while preserving its query and hash', async () => {
    const route = await resolveTo('/intelligent-query/agents/agent_1?mode=edit#prompt')
    expect(route.name).toBe('IntelligentQueryAgentDetail')
    expect(route.path).toBe('/agents/agent_1')
    expect(route.params.agentId).toBe('agent_1')
    expect(route.query).toEqual({ mode: 'edit' })
    expect(route.hash).toBe('#prompt')
  })

  it('maps the legacy widget page to the non-conflicting readable route', async () => {
    const route = await resolveTo('/intelligent-query/widget')
    expect(route.path).toBe('/widget-access')
    expect(route.name).toBe('IntelligentQueryWidget')
  })

  it('falls back safely when an unknown legacy child route is requested', async () => {
    const route = await resolveTo('/intelligent-query/internal-name?source=old')
    expect(route.path).toBe('/chat')
    expect(route.query).toEqual({ source: 'old' })
  })
})
