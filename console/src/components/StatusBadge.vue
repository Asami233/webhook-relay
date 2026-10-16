<script setup lang="ts">
import { computed } from 'vue'
import type { DeliveryStatus } from '../api'

const props = defineProps<{ status: DeliveryStatus }>()

const view = computed(() => {
  switch (props.status) {
    case 'SUCCEEDED':
      return { cls: 'ok', text: '成功' }
    case 'DEAD':
      return { cls: 'danger', text: '死信' }
    case 'IN_FLIGHT':
      return { cls: 'warn', text: '投递中' }
    default:
      return { cls: '', text: '待投递' }
  }
})
</script>

<template>
  <span class="badge" :class="view.cls" :title="status">{{ view.text }}</span>
</template>
