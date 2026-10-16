<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearKey, getKey } from './api'

const route = useRoute()
const router = useRouter()

const links = [
  { to: '/overview', label: '概览' },
  { to: '/events', label: '事件' },
  { to: '/deliveries', label: '投递' },
  { to: '/endpoints', label: '端点' },
  { to: '/demo', label: '演示' },
]

const isLogin = computed(() => route.path === '/login')
// 只显示 Key 的前缀，便于确认当前登录的是哪个租户
const keyPrefix = computed(() => {
  route.fullPath // 路由变化时重新读取
  const key = getKey()
  return key ? `${key.slice(0, 9)}…` : ''
})

function logout() {
  clearKey()
  router.push('/login')
}
</script>

<template>
  <header v-if="!isLogin" class="topbar">
    <nav class="nav" aria-label="主导航">
      <RouterLink to="/overview" class="brand">Relay</RouterLink>
      <RouterLink
        v-for="l in links"
        :key="l.to"
        :to="l.to"
        class="nav-link"
        :class="{ active: route.path.startsWith(l.to) }"
      >
        {{ l.label }}
      </RouterLink>
      <span class="nav-key" :title="'当前 API Key'">{{ keyPrefix }}</span>
      <button class="btn btn-paper btn-sm" type="button" @click="logout">退出</button>
    </nav>
  </header>
  <main class="page" :class="{ 'page-narrow': isLogin }">
    <RouterView />
  </main>
</template>
