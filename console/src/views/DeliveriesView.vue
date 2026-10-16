<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, formatTime, post, type DeliveryStatus, type DeliverySummary } from '../api'
import ErrorNotice from '../components/ErrorNotice.vue'
import StatusBadge from '../components/StatusBadge.vue'

const PAGE = 50
const route = useRoute()
const router = useRouter()
const filters: { value: DeliveryStatus | ''; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待投递' },
  { value: 'IN_FLIGHT', label: '投递中' },
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'DEAD', label: '死信' },
]

const status = ref<DeliveryStatus | ''>((route.query.status as DeliveryStatus) || '')
const rows = ref<DeliverySummary[]>([])
const done = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)
const message = ref<string | null>(null)

async function load(reset: boolean) {
  busy.value = true
  try {
    if (reset) rows.value = []
    const params = new URLSearchParams({ limit: String(PAGE) })
    if (status.value) params.set('status', status.value)
    if (rows.value.length) params.set('before', String(rows.value[rows.value.length - 1].id))
    const page = await api<DeliverySummary[]>(`/api/v1/deliveries?${params}`)
    rows.value.push(...page)
    done.value = page.length < PAGE
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

watch(
  status,
  (s) => {
    router.replace({ query: s ? { status: s } : {} })
    void load(true)
  },
  { immediate: true },
)

async function replay(d: DeliverySummary) {
  try {
    const updated = await post<DeliverySummary>(`/api/v1/deliveries/${d.id}/replay`)
    Object.assign(d, updated)
    message.value = `投递 #${d.id} 已重新排队，本轮重新获得完整的重试次数。`
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  }
}
</script>

<template>
  <div class="page-head">
    <h1>投递</h1>
    <div class="pills" role="group" aria-label="按状态筛选">
      <button
        v-for="f in filters"
        :key="f.value"
        type="button"
        class="pill"
        :class="{ active: status === f.value }"
        @click="status = f.value"
      >
        {{ f.label }}
      </button>
    </div>
  </div>
  <p v-if="status === 'DEAD'" class="notice">
    死信：重试次数用尽或收到不可重试的响应（如 400）。修好接收方后可单条重放，或到“端点”页按端点批量重放。
  </p>
  <p v-if="message" class="notice ok">{{ message }}</p>
  <ErrorNotice :error="error" />
  <div class="table-wrap">
    <table class="data">
      <thead>
        <tr>
          <th class="num">ID</th>
          <th class="num">事件</th>
          <th class="num">端点</th>
          <th>状态</th>
          <th class="num">尝试</th>
          <th>最近结果</th>
          <th>更新时间</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="d in rows" :key="d.id" class="clickable" @click="router.push(`/deliveries/${d.id}`)">
          <td class="num mono">{{ d.id }}</td>
          <td class="num mono">{{ d.eventId }}</td>
          <td class="num mono">{{ d.endpointId }}</td>
          <td><StatusBadge :status="d.status" /></td>
          <td class="num">{{ d.attempts }}<span v-if="d.replayCount" class="faint"> · 重放 {{ d.replayCount }}</span></td>
          <td class="wrap">{{ d.lastStatusCode ?? '' }} {{ d.lastError ?? '' }}</td>
          <td>{{ formatTime(d.updatedAt) }}</td>
          <td>
            <button v-if="d.status === 'DEAD'" class="btn btn-sm" type="button" @click.stop="replay(d)">重放</button>
          </td>
        </tr>
        <tr v-if="!rows.length && !busy">
          <td colspan="8" class="muted">没有符合条件的投递。</td>
        </tr>
      </tbody>
    </table>
  </div>
  <div v-if="!done" class="more">
    <button class="btn" type="button" :disabled="busy" @click="load(false)">{{ busy ? '加载中…' : '加载更多' }}</button>
  </div>
</template>
