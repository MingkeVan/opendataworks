import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import NL2SqlPage from '../components/NL2SqlPage.vue'
import App from './App.vue'

const routes = [
  { path: '/', redirect: '/nl2sql' },
  { path: '/nl2sql', component: NL2SqlPage },
  { path: '/:pathMatch(.*)*', redirect: '/nl2sql' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

createApp(App).use(router).mount('#app')
