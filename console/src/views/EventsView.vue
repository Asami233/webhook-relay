<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, formatTime, type EventSummary } from '../api'
import ErrorNotice from '../components/ErrorNotice.vue'

const PAGE = 50
const router = useRouter()
const rows = ref<EventSummary[]>([])
const done = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)

/** 键集分页：带上已加载的最小 id，避免深翻页时的 OFFSET 扫描。 */
async function loadMore() {
  busy.value = true
  try {
    const before = rows.value.length ? `&before=${rows.value[rows.value.length - 1].id}` : ''
    const page = await api<EventSummary[]>(`/api/v1/events?limit=${PAGE}${before}`)
    rows.value.push(...page)
    done.value = page.length < PAGE
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(loadMore)
</script>

<template>
  <div class="page-head">
    <h1>事件</h1>
    <span class="small faint">最新在前</span>
  </div>
  <ErrorNotice :error="error" />
  <div class="table-wrap">
    <table class="data">
      <thead>
        <tr>
          <th class="num">ID</th>
          <th>类型</th>
          <th>幂等键</th>
          <th>接收时间</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="e in rows" :key="e.id" class="clickable" @click="router.push(`/events/${e.id}`)">
          <td class="num mono">{{ e.id }}</td>
          <td class="mono">{{ e.type }}</td>
          <td class="mono wrap">{{ e.idempotencyKey }}</td>
          <td>{{ formatTime(e.createdAt) }}</td>
        </tr>
        <tr v-if="!rows.length && !busy">
          <td colspan="4" class="muted">还没有事件。可以去“演示”页一键生成。</td>
        </tr>
      </tbody>
    </table>
  </div>
  <div v-if="!done" class="more">
    <button class="btn" type="button" :disabled="busy" @click="loadMore">{{ busy ? '加载中…' : '加载更多' }}</button>
  </div>
</template>
