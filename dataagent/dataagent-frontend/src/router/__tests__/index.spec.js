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
  return { path: current.path, query: current.query, name: current.name }
}

describe('intelligent-query routing', () => {
  it('redirects the root path to chat', async () => {
    const route = await resolveTo('/')
    expect(route.path).toBe('/intelligent-query/chat')
    expect(route.name).toBe('IntelligentQueryChat')
  })

  it('redirects the bare section path to chat', async () => {
    const route = await resolveTo('/intelligent-query')
    expect(route.path).toBe('/intelligent-query/chat')
  })

  it('maps a legacy ?tab= link to the matching section and drops the tab param', async () => {
    const route = await resolveTo('/intelligent-query?tab=skills')
    expect(route.path).toBe('/intelligent-query/skills')
    expect(route.query.tab).toBeUndefined()
  })

  it('preserves non-tab query params when mapping a legacy ?tab= link', async () => {
    const route = await resolveTo('/intelligent-query?tab=agents&agent_id=agent_1')
    expect(route.path).toBe('/intelligent-query/agents')
    expect(route.query).toEqual({ agent_id: 'agent_1' })
  })

  it('maps the legacy /nl2sql?tab= entry through the same tab mapping', async () => {
    const route = await resolveTo('/nl2sql?tab=skills')
    expect(route.path).toBe('/intelligent-query/skills')
    expect(route.query.tab).toBeUndefined()
  })

  it('routes /nl2sql without a tab to chat while keeping other params', async () => {
    const route = await resolveTo('/nl2sql?topic_id=topic-1')
    expect(route.path).toBe('/intelligent-query/chat')
    expect(route.query).toEqual({ topic_id: 'topic-1' })
  })

  it('resolves the skill detail route with its folder param', async () => {
    const route = await resolveTo('/intelligent-query/skills/marketing-insights')
    expect(route.name).toBe('IntelligentQuerySkillDetail')
    expect(route.path).toBe('/intelligent-query/skills/marketing-insights')
  })
})
