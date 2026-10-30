import { Breadcrumb as AntBreadcrumb } from 'antd'
import { HomeOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { NAV_GROUPS, type NavNode } from '@/layout/navConfig'
import { useMemo } from 'react'

/**
 * 面包屑导航：从当前路径反查 分组 → 节点 链路。
 *
 * 路径映射表由 NAV_GROUPS 派生（单一来源），避免硬编码。
 */
export function Breadcrumb(): JSX.Element {
  const location = useLocation()
  const navigate = useNavigate()

  const crumbs = useMemo(() => {
    const path = location.pathname.replace(/\/+$/, '') || '/'
    const flat: (NavNode & { groupLabel: string })[] = []
    for (const g of NAV_GROUPS) {
      for (const n of g.children) {
        flat.push({ ...n, groupLabel: g.label })
      }
    }

    // 精确匹配优先，再前缀匹配（如 /tenants/:id 匹配 /tenants）
    const exact = flat.find((n) => n.path === path)
    const node = exact ?? flat.find((n) => path.startsWith(n.path + '/') || path === n.path)

    if (!node) {
      return [
        { label: '平台管理控制台', path: '/' },
      ]
    }

    return [
      { label: '平台管理控制台', path: '/' },
      { label: node.groupLabel, path: undefined },
      { label: node.label, path: node.path },
    ]
  }, [location.pathname])

  return (
    <AntBreadcrumb
      style={{ marginBottom: 16 }}
      items={crumbs.map((c, i) => ({
        key: i,
        title: c.label,
        href: c.path && i > 0 ? c.path : undefined,
        onClick: (e: React.MouseEvent) => {
          if (c.path && i > 0) {
            e.preventDefault()
            navigate(c.path)
          }
        },
        icon: i === 0 ? <HomeOutlined /> : undefined,
        className: i === 0 ? 'breadcrumb-home' : undefined,
      }))}
    />
  )
}
