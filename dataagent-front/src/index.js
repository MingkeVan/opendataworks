import DataAgentHost from './components/DataAgentHost.vue'
import { createAssistantApiClient } from './api/assistantApi'

export { DataAgentHost, createAssistantApiClient }

export default {
  install(app) {
    app.component('DataAgentHost', DataAgentHost)
  }
}
