<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api, post, type DemoRun, type Overview, type Sink } from '../api'
import AttemptsChart from '../components/AttemptsChart.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import { usePolling } from '../components/usePolling'

const RUN_STORAGE = 'relay.demoRun'

const form = reactive({ events: 1000, failurePercent: 30, latencyMs: 50 })
const run = ref<DemoRun | null>(readRun())
const sink = ref<Sink | null>(null)
const overview = ref<Overview | null>(null)
const error = ref<string | null>(null)
const busy = ref(false)

function readRun(): DemoRun | null {
  try {
    const raw = sessionStorage.getItem(RUN_STORAGE)
    return raw ? (JSON.parse(raw) as DemoRun) : null
  } catch {
    return null
  }
}

async function start() {
  busy.value = true
  try {
    run.value = await post<DemoRun>('/api/v1/demo', {
      events: form.events,
      failureRate: form.failurePercent / 100,
      latencyMs: form.latencyMs,
    })
    sessionStorage.setItem(RUN_STORAGE, JSON.stringify(run.value))
    sink.value = null
    error.value = null
    await refresh()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function heal() {
  if (!run.value) return
  try {
    sink.value = await api<Sink>(`/api/v1/sinks/${run.value.sinkId}/config`, {
      method: 'PUT',
      body: JSON.stringify({ failureRate: 0 }),
    })
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function refresh() {
  try {
    overview.value = await api<Overview>('/api/v1/stats')
    if (run.value) {
      sink.value = await api<Sink>(`/api/v1/sinks/${run.value.sinkId}`)
    }
    error.value = null
  } catch (e) {
    // 模拟接收方在内存里，服务重启后会消失：清掉本地记录，提示重新开始
    if (run.value && (e as { status?: number }).status === 404) {
      sessionStorage.removeItem(RUN_STORAGE)
      run.value = null
    }
    error.value = (e as Error).message
  }
}

onMounted(refresh)
usePolling(refresh, 1500)

const progress = computed(() => {
  if (!run.value || !sink.value) return 0
  return Math.min(1, sink.value.stats.uniqueDelivered / run.value.events)
})
const attemptRate = computed(() => {
  const s = sink.value?.stats
  return s && s.received ? s.succeeded / s.received : null
})
</script>

<template>
  <div class="page-head">
    <h1>一键演示</h1>
  </div>
  <p class="muted">
    新建一个按比例失败的模拟接收方，登记为端点，然后在服务端写入一批事件。你会看到：失败的请求按指数退避加抖动重试，
    极少数连续失败到上限的进入死信，最终送达率接近 100%，且同一投递不会被成功接收两次。
  </p>

  <form class="card" @submit.prevent="start">
    <div class="form-row">
      <div class="field">
        <label for="n">事件数</label>
        <input id="n" v-model.number="form.events" type="number" min="1" max="2000" />
      </div>
      <div class="field">
        <label for="f">接收方失败率（%）</label>
        <input id="f" v-model.number="form.failurePercent" type="number" min="0" max="100" />
      </div>
      <div class="field">
        <label for="l">接收方延迟（ms，另加同等随机抖动）</label>
        <input id="l" v-model.number="form.latencyMs" type="number" min="0" max="2000" />
      </div>
    </div>
    <div class="actions">
      <button class="btn btn-paper" type="submit" :disabled="busy">{{ busy ? '启动中…' : '开始演示' }}</button>
      <button v-if="run" class="btn" type="button" @click="heal">接收方“修好了”：失败率改为 0</button>
    </div>
  </form>
  <ErrorNotice :error="error" />

  <template v-if="run && sink">
    <h2>本次演示 <span class="mono small faint">{{ run.eventType }}</span></h2>
    <div class="card">
      <div class="small muted">已送达 {{ sink.stats.uniqueDelivered }} / {{ run.events }}</div>
      <div class="progress" role="progressbar" :aria-valuenow="Math.round(progress * 100)" aria-valuemin="0" aria-valuemax="100">
        <span :style="{ width: `${progress * 100}%` }"></span>
      </div>
    </div>
    <section class="stats stats-gap">
      <div class="card">
        <div class="stat-label">接收方收到请求</div>
        <div class="stat-value">{{ sink.stats.received }}</div>
      </div>
      <div class="card">
        <div class="stat-label">其中模拟失败（503）</div>
        <div class="stat-value danger">{{ sink.stats.failed }}</div>
      </div>
      <div class="card">
        <div class="stat-label">单次尝试成功率</div>
        <div class="stat-value">{{ attemptRate === null ? '—' : `${(attemptRate * 100).toFixed(1)}%` }}</div>
      </div>
      <div class="card">
        <div class="stat-label">重复成功接收</div>
        <div class="stat-value" :class="sink.stats.duplicates ? 'warn' : 'ok'">{{ sink.stats.duplicates }}</div>
      </div>
      <div v-if="overview" class="card">
        <div class="stat-label">租户死信（全部）</div>
        <div class="stat-value" :class="{ danger: overview.deliveries.DEAD > 0 }">{{ overview.deliveries.DEAD }}</div>
      </div>
    </section>
    <p class="small faint">
      当前接收方配置：失败率 {{ (sink.config.failureRate * 100).toFixed(0) }}%，延迟 {{ sink.config.latencyMs }} ms。
      重试退避从 5 秒起指数增长，失败率 30% 时大部分在 1 分钟内送达，个别要等几分钟。
    </p>
  </template>

  <template v-if="overview">
    <h2>最近 60 分钟的投递尝试</h2>
    <div class="card">
      <AttemptsChart :points="overview.attemptsPerMinute" />
    </div>
  </template>
</template>
