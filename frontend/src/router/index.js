import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    redirect: '/tables',
    children: [
      {
        path: '/tables',
        name: 'Tables',
        component: () => import('@/views/tables/TableList.vue'),
        meta: { title: '表管理' }
      },
      {
        path: '/tables/create',
        name: 'TableCreate',
        component: () => import('@/views/tables/TableCreate.vue'),
        meta: { title: '新建表' }
      },
      {
        path: '/tables/:id(\\d+)',
        name: 'TableDetail',
        component: () => import('@/views/tables/TableDetail.vue'),
        meta: { title: '表详情' }
      },
      {
        path: '/domains',
        name: 'Domains',
        component: () => import('@/views/domains/DomainManagement.vue'),
        meta: { title: '域管理' }
      },
      {
        path: '/tasks',
        name: 'Tasks',
        component: () => import('@/views/tasks/TaskList.vue'),
        meta: { title: '任务管理' }
      },
      {
        path: '/tasks/create',
        name: 'TaskCreate',
        component: () => import('@/views/tasks/TaskForm.vue'),
        meta: { title: '创建任务' }
      },
      {
        path: '/tasks/:id/edit',
        name: 'TaskEdit',
        component: () => import('@/views/tasks/TaskForm.vue'),
        meta: { title: '编辑任务' }
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
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
