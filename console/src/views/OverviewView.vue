<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type Overview } from '../api'
import AttemptsChart from '../components/AttemptsChart.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import { usePolling } from '../components/usePolling'

const data = ref<Overview | null>(null)
const error = ref<string | null>(null)

async function load() {
  try {
    data.value = await api<Overview>('/api/v1/stats')
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  }
}

onMounted(load)
usePolling(load, 3000)

const hour = computed(() => {
  const pts = data.value?.attemptsPerMinute ?? []
  const ok = pts.reduce((s, p) => s + p.ok, 0)
  const failed = pts.reduce((s, p) => s + p.failed, 0)
  return { ok, failed, rate: ok + failed === 0 ? null : ok / (ok + failed) }
})
const open = computed(() => (data.value ? data.value.deliveries.PENDING + data.value.deliveries.IN_FLIGHT : 0))
</script>

<template>
  <div class="page-head">
    <h1>概览</h1>
    <span class="small faint">每 3 秒刷新</span>
  </div>
  <ErrorNotice :error="error" />
  <template v-if="data">
    <section class="stats">
      <div class="card">
        <div class="stat-label">事件总数</div>
        <div class="stat-value">{{ data.events.toLocaleString() }}</div>
      </div>
      <div class="card">
        <div class="stat-label">已送达</div>
        <div class="stat-value ok">{{ data.deliveries.SUCCEEDED.toLocaleString() }}</div>
      </div>
      <div class="card">
        <div class="stat-label">待投递 / 投递中</div>
        <div class="stat-value warn">{{ open.toLocaleString() }}</div>
      </div>
      <RouterLink to="/deliveries?status=DEAD" class="card card-link">
        <div class="stat-label">死信（点击处理）</div>
        <div class="stat-value" :class="{ danger: data.deliveries.DEAD > 0 }">{{ data.deliveries.DEAD.toLocaleString() }}</div>
      </RouterLink>
      <div class="card">
        <div class="stat-label">近 1 小时尝试成功率</div>
        <div class="stat-value" :class="hour.rate === null ? '' : hour.rate >= 0.99 ? 'ok' : hour.rate >= 0.9 ? 'warn' : 'danger'">
          {{ hour.rate === null ? '—' : `${(hour.rate * 100).toFixed(1)}%` }}
        </div>
      </div>
    </section>

    <h2>最近 60 分钟的投递尝试</h2>
    <div class="card">
      <AttemptsChart :points="data.attemptsPerMinute" />
    </div>
    <p class="small faint">
      成功率按“尝试”统计：失败后重试成功的投递，会同时贡献失败与成功各一次。投递语义为至少一次，接收方按
      <code>X-Relay-Event-Id</code> 去重。
    </p>
  </template>
</template>
