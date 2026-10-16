<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, patch, post, type Endpoint } from '../api'
import ErrorNotice from '../components/ErrorNotice.vue'

const rows = ref<Endpoint[]>([])
const error = ref<string | null>(null)
const message = ref<string | null>(null)
const secret = ref<string | null>(null)
const form = reactive({ url: '', eventTypes: '*', maxAttempts: 8, deliverQps: 20 })

async function load() {
  try {
    rows.value = await api<Endpoint[]>('/api/v1/endpoints')
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function create() {
  try {
    const res = await post<{ endpoint: Endpoint; secret: string }>('/api/v1/endpoints', {
      url: form.url.trim(),
      eventTypes: form.eventTypes.split(',').map((s) => s.trim()).filter(Boolean),
      maxAttempts: form.maxAttempts,
      deliverQps: form.deliverQps,
    })
    secret.value = res.secret
    message.value = null
    form.url = ''
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function toggle(e: Endpoint) {
  try {
    Object.assign(e, await patch<Endpoint>(`/api/v1/endpoints/${e.id}`, { enabled: !e.enabled }))
  } catch (err) {
    error.value = (err as Error).message
  }
}

async function replayDead(e: Endpoint) {
  try {
    const res = await post<{ replayed: number }>(`/api/v1/endpoints/${e.id}/replay-dead`)
    message.value = `端点 #${e.id}：${res.replayed} 条死信已重新排队。`
    secret.value = null
  } catch (err) {
    error.value = (err as Error).message
  }
}

onMounted(load)
</script>

<template>
  <div class="page-head">
    <h1>端点</h1>
  </div>
  <p v-if="message" class="notice ok">{{ message }}</p>
  <div v-if="secret" class="notice ok">
    端点已创建。签名 secret <strong>只显示这一次</strong>，请保存到接收方用于校验 <code>X-Relay-Signature</code>：
    <pre class="json">{{ secret }}</pre>
  </div>
  <ErrorNotice :error="error" />

  <div class="table-wrap">
    <table class="data">
      <thead>
        <tr>
          <th class="num">ID</th>
          <th>URL</th>
          <th>订阅</th>
          <th>状态</th>
          <th class="num">最多尝试</th>
          <th class="num">限速</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="e in rows" :key="e.id">
          <td class="num mono">{{ e.id }}</td>
          <td class="wrap mono">{{ e.url }}<div v-if="e.description" class="faint">{{ e.description }}</div></td>
          <td class="mono">{{ e.eventTypes.join(', ') }}</td>
          <td><span class="badge" :class="e.enabled ? 'ok' : ''">{{ e.enabled ? '启用' : '停用' }}</span></td>
          <td class="num">{{ e.maxAttempts }}</td>
          <td class="num">{{ e.deliverQps }}/s</td>
          <td>
            <div class="pills">
              <button class="btn btn-sm" type="button" @click="toggle(e)">{{ e.enabled ? '停用' : '启用' }}</button>
              <button class="btn btn-sm" type="button" :disabled="!e.enabled" @click="replayDead(e)">重放全部死信</button>
            </div>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td colspan="7" class="muted">还没有端点。</td>
        </tr>
      </tbody>
    </table>
  </div>

  <h2>新建端点</h2>
  <form class="card" @submit.prevent="create">
    <div class="field">
      <label for="url">接收地址（只允许公网 http/https；内网、环回、云元数据地址会被拒绝）</label>
      <input id="url" v-model="form.url" type="url" required placeholder="https://example.com/webhooks/relay" />
    </div>
    <div class="form-row">
      <div class="field">
        <label for="types">订阅的事件类型（逗号分隔，支持 * 与 order.*）</label>
        <input id="types" v-model="form.eventTypes" required />
      </div>
      <div class="field">
        <label for="attempts">最多尝试次数</label>
        <input id="attempts" v-model.number="form.maxAttempts" type="number" min="1" max="30" />
      </div>
      <div class="field">
        <label for="qps">投递限速（次/秒）</label>
        <input id="qps" v-model.number="form.deliverQps" type="number" min="1" max="1000" />
      </div>
    </div>
    <button class="btn btn-paper" type="submit">创建</button>
  </form>
</template>
