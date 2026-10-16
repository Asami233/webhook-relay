<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, api, clearKey, setKey } from '../api'
import ErrorNotice from '../components/ErrorNotice.vue'

const route = useRoute()
const router = useRouter()
const key = ref('')
const error = ref<string | null>(route.query.expired ? '登录已失效（Key 可能已被轮换），请重新输入。' : null)
const busy = ref(false)

async function login() {
  const value = key.value.trim()
  if (!value.startsWith('rk_')) {
    error.value = 'API Key 以 rk_ 开头。'
    return
  }
  busy.value = true
  error.value = null
  setKey(value)
  try {
    await api('/api/v1/stats') // 用一次真实请求验证 Key
    const next = typeof route.query.next === 'string' ? route.query.next : '/overview'
    router.replace(next)
  } catch (e) {
    clearKey()
    error.value = e instanceof ApiError && e.status === 401 ? 'API Key 不正确。' : `无法连接服务：${(e as Error).message}`
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <h1>Relay 控制台</h1>
  <p class="muted">Webhook 可靠投递平台。用租户的 API Key 登录，查看事件、投递与死信。</p>
  <form class="card" @submit.prevent="login">
    <div class="field">
      <label for="key">API Key</label>
      <input id="key" v-model="key" type="password" autocomplete="off" placeholder="rk_…" required />
    </div>
    <ErrorNotice :error="error" />
    <button class="btn btn-paper" type="submit" :disabled="busy">{{ busy ? '验证中…' : '进入' }}</button>
  </form>
  <p class="small faint">
    Key 只保存在本标签页的 sessionStorage，关闭标签页即失效。租户与 Key 由管理员通过 <code>/api/admin/tenants</code> 创建。
  </p>
</template>
