import React from 'react'
import ReactDOM from 'react-dom/client'
import { App } from '@/App'
import { AppErrorBoundary } from '@/shared/AppErrorBoundary'
import { installGlobalErrorHandlers } from '@/shared/globalErrorHandlers'
import '@/styles/global.css'

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
