import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import NL2SqlPage from '../components/NL2SqlPage.vue'
import App from './App.vue'

const routes = [
  { path: '/', redirect: '/nl2sql' },
  { path: '/nl2sql', component: NL2SqlPage },
  { path: '/nl2sql-v2', redirect: '/nl2sql' },
  { path: '/:pathMatch(.*)*', redirect: '/nl2sql' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

createApp(App).use(router).use(ElementPlus).mount('#app')
