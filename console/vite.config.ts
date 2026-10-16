import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 生产：构建到 dist/，由 Spring Boot 在 /console/ 下提供（同源调用 API）。
// 开发：npm run dev，API 请求代理到本机 8080 上运行的 Relay。
export default defineConfig({
  base: '/console/',
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
