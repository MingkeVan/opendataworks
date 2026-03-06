import NL2SqlChat from './components/NL2SqlChat.vue'
import NL2SqlPage from './components/NL2SqlPage.vue'
import { createNl2SqlApiClient } from './api/nl2sqlApi'

export { NL2SqlChat, NL2SqlPage, createNl2SqlApiClient }

export default {
  install(app) {
    app.component('NL2SqlChat', NL2SqlChat)
    app.component('NL2SqlPage', NL2SqlPage)
  }
}
