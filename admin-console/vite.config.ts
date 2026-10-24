import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

/**
 * P0 工程配置 —— 独立前端工程
 *
 * 部署形态（用户决策）：
 *  - 路径部署：控制台挂载在 `/admin` 下（`base: '/admin/'` + router `basename: '/admin'`）。
 *  - 同源反向代理：生产由 Nginx 将 `/admin/*` 指静态资源、`/api/*` 指共享后端
 *    `platform/agent-api`（与现有前端一致，后端 `cors.disable()`，故不走 CORS）。
 *  - 本地开发：Vite devServer proxy 把 `/api` 同源代理到后端（默认 127.0.0.1:8080），
 *    可用 `VITE_DEV_API_TARGET` 覆盖。
 */
export default defineConfig({
  base: '/admin/',
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5180,
    proxy: {
      // 与现有前端一致：同源代理到共享后端 agent-api（本地 8080）
      '/api': {
        target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
    css: false,
    pool: 'forks',
    poolOptions: {
      forks: { singleFork: true },
    },
    testTimeout: 15000,
    hookTimeout: 15000,
  },
})
