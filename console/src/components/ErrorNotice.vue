<script setup lang="ts">
defineProps<{ error: string | null }>()
</script>

<template>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
</template>
