import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import DataAgentHost from '../components/DataAgentHost.vue'

const App = {
  components: { DataAgentHost },
  template: `
    <div style="height: 100vh; background: #f5f7fb;">
      <div style="padding: 20px; color: #334155;">dataagent-front standalone playground</div>
      <DataAgentHost :entry-visible="true" api-base="/api" stream-base="/api" />
    </div>
  `
}

createApp(App).use(ElementPlus).mount('#app')
