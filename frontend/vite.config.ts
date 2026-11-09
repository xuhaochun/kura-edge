import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 监听 0.0.0.0：默认只绑 localhost（本机 Node 解析为 ::1，局域网访问会被拒连）。
    // 局域网/容器内访问 `http://<本机IP>:5173/` 需要此项；仅本机开发时无副作用。
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/__tests__/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      // rules/06：语句覆盖率 ≥ 70%，composables 与 stores ≥ 80%
      thresholds: {
        statements: 70,
        'src/composables/**': { statements: 80 },
        'src/stores/**': { statements: 80 },
      },
    },
  },
})
