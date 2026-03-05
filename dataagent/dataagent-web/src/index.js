import DataAgentHost from './components/DataAgentHost.vue'
import NL2SqlChat from './components/NL2SqlChat.vue'
import NL2SqlPage from './components/NL2SqlPage.vue'
import { createAssistantApiClient } from './api/assistantApi'
import { createNl2SqlApiClient } from './api/nl2sqlApi'

export { DataAgentHost, NL2SqlChat, NL2SqlPage, createAssistantApiClient, createNl2SqlApiClient }

export default {
  install(app) {
    app.component('DataAgentHost', DataAgentHost)
    app.component('NL2SqlChat', NL2SqlChat)
    app.component('NL2SqlPage', NL2SqlPage)
  }
}
