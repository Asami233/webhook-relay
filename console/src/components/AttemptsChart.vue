<script setup lang="ts">
import { computed } from 'vue'
import type { MinutePoint } from '../api'

// 最近 60 分钟每分钟的投递尝试：绿色成功、红色失败，堆叠柱状图。纯 SVG，不引入图表库。
const props = defineProps<{ points: MinutePoint[] }>()

const W = 600
const H = 160
const PAD_BOTTOM = 18

const max = computed(() => Math.max(1, ...props.points.map((p) => p.ok + p.failed)))
const bars = computed(() => {
  const n = Math.max(1, props.points.length)
  const slot = W / n
  const usable = H - PAD_BOTTOM
  return props.points.map((p, i) => {
    const okH = (p.ok / max.value) * usable
    const failH = (p.failed / max.value) * usable
    const time = new Date(p.minute).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
    return {
      x: i * slot + 1,
      w: Math.max(1, slot - 2),
      okY: usable - okH,
      okH,
      failY: usable - okH - failH,
      failH,
      title: `${time}  成功 ${p.ok}  失败 ${p.failed}`,
    }
  })
})
</script>

<template>
  <figure class="chart">
    <svg :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="最近 60 分钟每分钟投递尝试的成功与失败数" preserveAspectRatio="none">
      <line :x1="0" :x2="W" :y1="H - PAD_BOTTOM" :y2="H - PAD_BOTTOM" class="axis" />
      <g v-for="(b, i) in bars" :key="i">
        <title>{{ b.title }}</title>
        <rect :x="b.x" :y="b.okY" :width="b.w" :height="b.okH" class="ok" />
        <rect :x="b.x" :y="b.failY" :width="b.w" :height="b.failH" class="failed" />
      </g>
      <text :x="2" :y="H - 4" class="label">60 分钟前</text>
      <text :x="W - 2" :y="H - 4" class="label" text-anchor="end">现在</text>
    </svg>
    <figcaption class="legend small muted">
      <span><i class="dot ok"></i>成功</span>
      <span><i class="dot failed"></i>失败（含超时、连接错误）</span>
      <span class="faint">峰值 {{ max }} 次/分钟</span>
    </figcaption>
  </figure>
</template>

<style scoped>
.chart {
  margin: 0;
}
svg {
  display: block;
  width: 100%;
  height: 180px;
}
.axis {
  stroke: var(--line);
}
.ok {
  fill: var(--ok);
}
.failed {
  fill: var(--danger);
}
.label {
  fill: var(--faint);
  font-size: 11px;
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-4);
  margin-top: var(--s-2);
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
}
.dot.ok {
  background: var(--ok);
}
.dot.failed {
  background: var(--danger);
}
</style>
