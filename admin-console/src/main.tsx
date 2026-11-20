import React from 'react'
import ReactDOM from 'react-dom/client'
import { App } from '@/App'
import { AppErrorBoundary } from '@/shared/AppErrorBoundary'
import { installGlobalErrorHandlers } from '@/shared/globalErrorHandlers'
import '@/styles/global.css'
// 全站视觉统一层：必须在 global.css 之后，覆盖 90+ 个未挂 dc- 类的 AntD 页面。
import '@/styles/global-overrides.css'

// ⚠️ 全局未捕获错误安全网：集中上报，避免静默失败（对齐 frontend）。
installGlobalErrorHandlers()

const root = document.getElementById('root')
if (root) {
  ReactDOM.createRoot(root).render(
    <React.StrictMode>
      <AppErrorBoundary>
        <App />
      </AppErrorBoundary>
    </React.StrictMode>,
  )
}
