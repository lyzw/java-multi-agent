import { ConfigProvider, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { router } from '@/router'

/**
 * 视觉主张「控制面 / Control Surface」——精密仪器与工程制图语言。
 * 全部 token 与全局 CSS 变量（global.css :root）同源，避免主题两处漂移。
 *
 * ⚠️ colorPrimary 用黄铜而非默认蓝：侧栏为深墨绿外壳（见 AdminLayout），
 *    选中态/主操作统一走黄铜铭牌色；状态语义（健康/严重）另用 teal/red，
 *    不复用 primary，保证「色码配文字」的语义不被主题色冲淡。
 */
const controlSurfaceTheme = {
  token: {
    colorPrimary: '#A9761B',
    colorPrimaryHover: '#B9862A',
    colorPrimaryActive: '#855C12',
    colorPrimaryBg: '#F1E6CE',
    colorPrimaryBgHover: '#EADDBF',
    colorSuccess: '#1C6E5E',
    colorWarning: '#A9761B',
    colorError: '#B23A2E',
    colorInfo: '#586862',
    colorText: '#1B2624',
    colorTextSecondary: '#586862',
    colorTextTertiary: '#8B9791',
    colorTextQuaternary: '#AAB4AE',
    colorBorder: '#DCE1DB',
    colorBorderSecondary: '#E8EBE6',
    colorBgLayout: '#F4F5F1',
    colorBgContainer: '#FFFFFF',
    colorBgElevated: '#FFFFFF',
    colorBgTextHover: '#F4F5F1',
    borderRadius: 6,
    borderRadiusLG: 8,
    borderRadiusSM: 4,
    fontFamily:
      "'IBM Plex Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
    fontSize: 14,
    controlHeight: 36,
  },
  components: {
    Layout: {
      siderBg: '#16211F',
      headerBg: '#FFFFFF',
      bodyBg: '#F4F5F1',
    },
    Menu: {
      darkItemBg: '#16211F',
      darkSubMenuItemBg: '#16211F',
      darkItemSelectedBg: 'rgba(169,118,27,0.16)',
      darkItemColor: '#B7C3BD',
      darkItemHoverColor: '#EDF1EE',
      darkItemSelectedColor: '#F6EAD0',
      itemBorderRadius: 0,
      itemHeight: 38,
    },
    Table: {
      headerBg: '#FAFBF9',
      headerColor: '#8B9791',
      headerSplitColor: 'transparent',
      rowHoverBg: 'rgba(169,118,27,0.05)',
      borderColor: '#E8EBE6',
    },
    Button: {
      primaryShadow: 'none',
      defaultShadow: 'none',
      fontWeight: 500,
    },
  },
}

export function App(): JSX.Element {
  return (
    <ConfigProvider locale={zhCN} theme={controlSurfaceTheme}>
      <AntApp>
        <SessionProvider>
          <RouterProvider router={router} />
        </SessionProvider>
      </AntApp>
    </ConfigProvider>
  )
}
