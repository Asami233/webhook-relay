<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, formatTime, post, type DeliveryDetail } from '../api'
import ErrorNotice from '../components/ErrorNotice.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { usePolling } from '../components/usePolling'

const props = defineProps<{ id: string }>()
const detail = ref<DeliveryDetail | null>(null)
const error = ref<string | null>(null)
const message = ref<string | null>(null)

async function load() {
  try {
    detail.value = await api<DeliveryDetail>(`/api/v1/deliveries/${encodeURIComponent(props.id)}`)
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function replay() {
  try {
    await post(`/api/v1/deliveries/${encodeURIComponent(props.id)}/replay`)
    message.value = '已重新排队；尝试历史保留，序号接着往下记。'
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}

function codeClass(code: number | null): string {
  if (code === null) return 'danger'
  if (code >= 200 && code < 300) return 'ok'
  return code === 408 || code === 429 || code >= 500 ? 'warn' : 'danger'
}

onMounted(load)
usePolling(load, 3000)
</script>

<template>
  <div class="page-head">
    <h1>投递 #{{ id }}</h1>
    <RouterLink to="/deliveries" class="small">返回列表</RouterLink>
  </div>
  <p v-if="message" class="notice ok">{{ message }}</p>
  <ErrorNotice :error="error" />
  <template v-if="detail">
    <div class="card">
      <dl class="kv">
        <dt>状态</dt>
        <dd><StatusBadge :status="detail.delivery.status" /></dd>
        <dt>事件</dt>
        <dd><RouterLink :to="`/events/${detail.delivery.eventId}`">#{{ detail.delivery.eventId }}</RouterLink></dd>
        <dt>端点</dt>
        <dd class="mono">#{{ detail.delivery.endpointId }}</dd>
        <dt>尝试次数</dt>
        <dd>{{ detail.delivery.attempts }}（人工重放 {{ detail.delivery.replayCount }} 次）</dd>
        <dt>下次尝试</dt>
        <dd>{{ detail.delivery.status === 'PENDING' ? formatTime(detail.delivery.nextAttemptAt) : '—' }}</dd>
        <dt>最近错误</dt>
        <dd>{{ detail.delivery.lastError ?? '—' }}</dd>
      </dl>
      <div v-if="detail.delivery.status === 'DEAD'" class="actions">
        <button class="btn btn-paper" type="button" @click="replay">重放这条死信</button>
      </div>
    </div>

    <h2>每次尝试（{{ detail.attempts.length }}）</h2>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th class="num">#</th>
            <th>开始时间</th>
            <th class="num">耗时</th>
            <th>结果</th>
            <th>响应片段 / 错误</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in detail.attempts" :key="a.attemptNo">
            <td class="num">{{ a.attemptNo }}</td>
            <td>{{ formatTime(a.startedAt) }}</td>
            <td class="num">{{ a.durationMs }} ms</td>
            <td><span class="badge" :class="codeClass(a.statusCode)">{{ a.statusCode ?? '无响应' }}</span></td>
            <td class="wrap mono">{{ a.error ?? a.responseSnippet ?? '' }}</td>
          </tr>
          <tr v-if="!detail.attempts.length">
            <td colspan="5" class="muted">还没有发出过请求。</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="small faint">绿色成功；黄色可重试（408、429、5xx）；红色不可重试或无响应（网络错误、超时）。响应体只保留前 1 KB。</p>
  </template>
</template>
