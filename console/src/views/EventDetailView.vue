<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, formatTime, type EventDetail } from '../api'
import ErrorNotice from '../components/ErrorNotice.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { usePolling } from '../components/usePolling'

const props = defineProps<{ id: string }>()
const router = useRouter()
const event = ref<EventDetail | null>(null)
const error = ref<string | null>(null)

async function load() {
  try {
    event.value = await api<EventDetail>(`/api/v1/events/${encodeURIComponent(props.id)}`)
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  }
}

onMounted(load)
usePolling(load, 3000)
</script>

<template>
  <div class="page-head">
    <h1>事件 #{{ id }}</h1>
    <RouterLink to="/events" class="small">返回列表</RouterLink>
  </div>
  <ErrorNotice :error="error" />
  <template v-if="event">
    <div class="card">
      <dl class="kv">
        <dt>类型</dt>
        <dd class="mono">{{ event.type }}</dd>
        <dt>幂等键</dt>
        <dd class="mono">{{ event.idempotencyKey }}</dd>
        <dt>接收时间</dt>
        <dd>{{ formatTime(event.createdAt) }}</dd>
      </dl>
    </div>

    <h2>按端点的投递（{{ event.deliveries.length }}）</h2>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th class="num">投递 ID</th>
            <th class="num">端点</th>
            <th>状态</th>
            <th class="num">尝试</th>
            <th>最近结果</th>
            <th>下次尝试</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in event.deliveries" :key="d.id" class="clickable" @click="router.push(`/deliveries/${d.id}`)">
            <td class="num mono">{{ d.id }}</td>
            <td class="num mono">{{ d.endpointId }}</td>
            <td><StatusBadge :status="d.status" /></td>
            <td class="num">{{ d.attempts }}</td>
            <td class="wrap">{{ d.lastStatusCode ?? '' }} {{ d.lastError ?? '' }}</td>
            <td>{{ d.status === 'PENDING' ? formatTime(d.nextAttemptAt) : '—' }}</td>
          </tr>
          <tr v-if="!event.deliveries.length">
            <td colspan="6" class="muted">没有订阅该事件类型的启用端点，因此没有投递任务。</td>
          </tr>
        </tbody>
      </table>
    </div>

    <h2>Payload</h2>
    <pre class="json">{{ JSON.stringify(event.payload, null, 2) }}</pre>
  </template>
</template>
