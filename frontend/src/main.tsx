import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { installGlobalErrorHandlers } from './shared/globalErrorHandlers'
import './index.css'

// ⚠️ 全局未捕获错误安全网：集中上报，避免静默失败（见 shared/globalErrorHandlers.ts）。
installGlobalErrorHandlers()

const container = document.getElementById('root')
if (container === null) {
  throw new Error('找不到挂载点 #root')
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
