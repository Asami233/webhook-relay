import { onBeforeUnmount, onMounted } from 'vue'

/** 定时刷新；标签页不可见时暂停，避免后台标签页持续打接口。 */
export function usePolling(fn: () => Promise<void> | void, intervalMs: number): void {
  let timer: number | undefined
  const tick = () => {
    if (document.visibilityState === 'visible') {
      void fn()
    }
  }
  onMounted(() => {
    timer = window.setInterval(tick, intervalMs)
  })
  onBeforeUnmount(() => window.clearInterval(timer))
}
