import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import { useAuthStore } from '@/stores/auth'
import { createUnauthorizedRedirect, setUnauthorizedHandler } from '@/utils/authRedirect'
import './styles/variables.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// 组合根接线：request.js 只依赖零依赖的 authRedirect，router / store 在这里注入
setUnauthorizedHandler(createUnauthorizedRedirect({ router, authStore: useAuthStore(pinia) }))

app.mount('#app')
