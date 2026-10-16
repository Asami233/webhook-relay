import { createRouter, createWebHashHistory } from 'vue-router'
import { getKey } from './api'

// hash 路由：服务端只需提供 /console/index.html，无需把任意路径回退到首页
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/overview', component: () => import('./views/OverviewView.vue') },
    { path: '/events', component: () => import('./views/EventsView.vue') },
    { path: '/events/:id', component: () => import('./views/EventDetailView.vue'), props: true },
    { path: '/deliveries', component: () => import('./views/DeliveriesView.vue') },
    { path: '/deliveries/:id', component: () => import('./views/DeliveryDetailView.vue'), props: true },
    { path: '/endpoints', component: () => import('./views/EndpointsView.vue') },
    { path: '/demo', component: () => import('./views/DemoView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})

router.beforeEach((to) => {
  if (!to.meta.public && !getKey()) {
    return { path: '/login', query: { next: to.fullPath } }
  }
})
