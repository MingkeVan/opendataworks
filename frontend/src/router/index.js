import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: '/dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/Dashboard.vue'),
        meta: { title: '控制台' }
      },
      {
        path: '/datastudio-new',
        name: 'DataStudioNew',
        component: () => import('@/views/datastudio/DataStudioNew.vue'),
        meta: { title: 'datastudio' }
      },
      {
        path: '/tables',
        redirect: '/datastudio-new'
      },
      {
        path: '/tables/create',
        redirect: { path: '/datastudio-new', query: { create: '1' } }
      },
      {
        path: '/query',
        redirect: '/datastudio-new'
      },
      {
        path: '/domains',
        name: 'Domains',
        component: () => import('@/views/domains/DomainManagement.vue'),
        meta: { title: '域管理' }
      },
      {
        path: '/workflows',
        name: 'Workflows',
        component: () => import('@/views/workflows/WorkflowManagement.vue'),
        meta: { title: '工作流管理' }
      },
      {
        path: '/workflows/:id(\\d+)',
        name: 'WorkflowDetail',
        component: () => import('@/views/workflows/WorkflowDetail.vue'),
        meta: { title: '工作流详情' }
      },
      {
        path: '/tasks',
        redirect: { path: '/workflows', query: { tab: 'tasks' } }
      },

      {
        path: '/lineage',
        name: 'Lineage',
        component: () => import('@/views/lineage/LineageView.vue'),
        meta: { title: '血缘关系' }
      },
      {
        path: '/monitor',
        name: 'Monitor',
        component: () => import('@/views/executions/ExecutionMonitor.vue'),
        meta: { title: '执行监控' }
      },
      {
        path: '/inspection',
        name: 'Inspection',
        component: () => import('@/views/inspection/InspectionView.vue'),
        meta: { title: '数据巡检' }
      },
      {
        path: '/settings',
        name: 'Settings',
        component: () => import('@/views/settings/ConfigurationManagement.vue'),
        meta: { title: '管理员' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
