import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

/**
 * FE-D1 §2 工程配置
 *
 * ⚠️ 代理设计说明（FE-D1 §3.4）：
 * 后端 SecurityConfiguration 明确 `cors.disable()`，因此**不能**开
 * `cors.allowOrigins("*")` 绕过（FE-01 §5 G11）。
 * 本地开发通过 Vite devServer proxy 做**同源代理**，
 * 生产通过反向代理（Nginx）做同源转发。两者语义一致。
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // /api 与 /actuator 转发到后端，与生产同源部署保持一致
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
    /*
     * ⚠️ 单 fork + 受限内存：
     *   jsdom 每个 worker 约占 200–400MB，本机可用内存不足时
     *   并发执行会被 OOM Killer 终止（表现为 SIGTERM / exit 137）。
     *   故强制单进程顺序执行。测试规模变大后可评估放宽。
     */
    pool: 'forks',
    poolOptions: {
      forks: {
        singleFork: true,
      },
    },
    // 防止测试超时掩盖真实问题
    testTimeout: 15000,
    hookTimeout: 15000,
  },
})
