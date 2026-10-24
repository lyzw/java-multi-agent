import { ConfigProvider, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { router } from '@/router'

export function App(): JSX.Element {
  return (
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#2f6df6' } }}>
      <AntApp>
        <SessionProvider>
          <RouterProvider router={router} />
        </SessionProvider>
      </AntApp>
    </ConfigProvider>
  )
}
