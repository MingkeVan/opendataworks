import { createRouter, createWebHistory } from 'vue-router'

// Legacy `?tab=` values mapped onto the real child route segments so existing
// deep links keep working after the menu became route-driven.
const LEGACY_TAB_TO_SEGMENT = {
  chat: 'chat',
  'chat-v2': 'chat',
  skills: 'skills',
  agents: 'agents',
  models: 'models',
  widget: 'widget'
}

export const redirectLegacyTab = (to) => {
  const tab = String(to.query.tab || '')
  const segment = LEGACY_TAB_TO_SEGMENT[tab] || 'chat'
  const { tab: _omitTab, ...query } = to.query
  return { path: `/intelligent-query/${segment}`, query, hash: to.hash }
}

export const routes = [
  {
    path: '/',
    redirect: '/intelligent-query/chat'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/intelligent-query',
    component: () => import('@/views/intelligence/IntelligentQueryView.vue'),
    children: [
      {
        path: '',
        // Honour legacy `/intelligent-query?tab=skills` style links.
        redirect: redirectLegacyTab
      },
      {
        path: 'chat',
        name: 'IntelligentQueryChat',
        component: () => import('@/views/intelligence/NL2SqlChatV2.vue'),
        meta: { tab: 'chat-v2', title: '智能问数' }
      },
      {
        path: 'skills',
        name: 'IntelligentQuerySkills',
        component: () => import('@/views/settings/SkillStudio.vue'),
        meta: { tab: 'skills', title: 'Skills', adminOnly: true }
      },
      {
        path: 'skills/:folder',
        name: 'IntelligentQuerySkillDetail',
        component: () => import('@/views/settings/SkillDetailView.vue'),
        meta: { tab: 'skills', title: 'Skill 详情', adminOnly: true }
      },
      {
        path: 'agents',
        name: 'IntelligentQueryAgents',
        component: () => import('@/views/intelligence/AgentStudio.vue'),
        meta: { tab: 'agents', title: '智能体', adminOnly: true }
      },
      {
        path: 'agents/:agentId',
        name: 'IntelligentQueryAgentDetail',
        component: () => import('@/views/intelligence/AgentDetailView.vue'),
        meta: { tab: 'agents', title: '智能体详情', adminOnly: true }
      },
      {
        path: 'models',
        name: 'IntelligentQueryModels',
        component: () => import('@/views/settings/DataAgentConfig.vue'),
        meta: { tab: 'models', title: '模型管理', adminOnly: true }
      },
      {
        path: 'widget',
        name: 'IntelligentQueryWidget',
        component: () => import('@/views/settings/WidgetAccessConfig.vue'),
        meta: { tab: 'widget', title: 'Widget 接入', adminOnly: true }
      }
    ]
  },
  {
    path: '/nl2sql',
    // Reuse the legacy tab mapping so /nl2sql?tab=skills lands on the matching
    // section (and drops the now-unused tab param) instead of always Chat.
    redirect: redirectLegacyTab
  }
]

const router = createRouter({
  // Keep the router base in sync with the Vite base so the app works under the
  // production `/dataagent/` prefix as well as any overridden mount point.
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

// 认证守卫：auth 未启用（后端未挂配置）时完全透明，行为与历史版本一致。
router.beforeEach(async (to) => {
  // 懒加载避免 router → store → api 的启动期循环依赖。
  const { useAuthStore } = await import('@/stores/auth')
  const authStore = useAuthStore()
  await authStore.bootstrap()

  if (!authStore.enabled) return true
  if (to.meta?.public) {
    // 已登录再访问 /login：直接回应用。
    if (to.name === 'Login' && authStore.currentUser) {
      return { path: '/intelligent-query/chat' }
    }
    return true
  }
  if (!authStore.currentUser) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta?.adminOnly && !authStore.isAdmin) {
    return { path: '/intelligent-query/chat' }
  }
  return true
})

export default router
