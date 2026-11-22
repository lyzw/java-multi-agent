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
    colorFillAlter: '#FAFBF9',
    colorFillQuaternary: '#FAFBF9',
    borderRadius: 6,
    borderRadiusLG: 8,
    borderRadiusSM: 4,
    fontFamily:
      "'IBM Plex Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
    fontSize: 14,
    fontSizeSM: 12,
    fontSizeLG: 16,
    lineHeight: 1.5714,
    controlHeight: 36,
    controlHeightSM: 28,
    controlHeightLG: 44,
    motionDurationMid: '0.16s',
    motionDurationSlow: '0.24s',
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
      headerColor: '#586862',
      headerSplitColor: 'transparent',
      headerFontWeight: 600,
      // ⚠️ 必须是不透明色：固定列（fixed left/right）在 hover 时继承行背景，
      // 半透明 rgba 会让横向滚动后方的单元格文字透到固定列上（操作按钮被"遮挡"）。
      rowHoverBg: '#faf7f1',
      borderColor: '#E8EBE6',
      cellPaddingBlock: 12,
      cellPaddingInline: 16,
    },
    Button: {
      primaryShadow: 'none',
      defaultShadow: 'none',
      fontWeight: 500,
    },
    // 以下组件 token 全站统一——覆盖那些未使用 dc- 类的页面（90+ 页）。
    // 目标：所有页面共享同一套间距/圆角/字号/交互节奏，而非各自 AntD 默认。
    Card: {
      bodyPadding: 16,
      bodyPaddingSM: 12,
      headerFontSize: 15,
    },
    Typography: {
      titleMarginTop: 0,
      titleMarginBottom: 12,
      colorText: '#1B2624',
      colorTextSecondary: '#586862',
    },
    Pagination: {
      itemActiveBg: '#A9761B',
    },
    Tabs: {
      itemColor: '#586862',
      itemActiveColor: '#A9761B',
      itemHoverColor: '#B9862A',
      itemSelectedColor: '#A9761B',
      inkBarColor: '#A9761B',
      titleFontSize: 14,
    },
    Descriptions: {
      labelBg: '#FAFBF9',
      titleMarginBottom: 12,
    },
    Form: {
      labelColor: '#586862',
      verticalLabelPadding: '0 0 6px',
    },
    Segmented: {
      itemSelectedBg: '#FFFFFF',
      itemSelectedColor: '#855C12',
      trackBg: '#EDEFEA',
    },
    Modal: {
      titleFontSize: 16,
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
