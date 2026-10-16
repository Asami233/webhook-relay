import { createApp } from 'vue'
import App from './App.vue'
import { setUnauthorizedHandler } from './api'
import { router } from './router'
import './styles/tokens.css'
import './styles/app.css'

setUnauthorizedHandler(() => {
  if (router.currentRoute.value.path !== '/login') {
    router.push({ path: '/login', query: { next: router.currentRoute.value.fullPath, expired: '1' } })
  }
})

createApp(App).use(router).mount('#app')
