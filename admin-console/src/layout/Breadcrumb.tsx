import { Breadcrumb as AntBreadcrumb } from 'antd'
import { HomeOutlined } from '@ant-design/icons'
import { useLocation } from 'react-router-dom'
import { findNavNode, type NavGroup } from '@/layout/navConfig'
import { useMemo } from 'react'

interface BreadcrumbProps {
  /** 已按角色过滤的可见分组——面包屑不能暴露无权分组的标签。 */
  groups: NavGroup[]
  /** 点击「分组」级：侧栏退回到该分组的页面索引。 */
  onGroupClick: () => void
}

/**
 * 面包屑导航：控制台 › 分组 › 页面。
 *
 * 与侧栏钻取联动——中间「分组」一级可点击，点击后侧栏退回分组索引
 * （由 AdminLayout 的 onGroupClick 切换），从而在视觉上形成
 * 「菜单本身就是一条可回退的路径」的面包屑结构。
 *
 * 路径映射由 NAV_GROUPS 派生 + findNavNode/findNavGroup 反查（单一来源），
 * 精确匹配优先、最长前缀兜底，深链 /tenants/:id 亦能定位。
 */
export function Breadcrumb({ groups, onGroupClick }: BreadcrumbProps): JSX.Element {
  const location = useLocation()

  const { group, node } = useMemo(() => {
    const n = findNavNode(
      location.pathname,
      groups.flatMap((g) => g.children),
    )
    if (!n) return { group: undefined, node: undefined }
    return {
      group: groups.find((g) => g.children.some((c) => c.key === n.key)),
      node: n,
    }
  }, [location.pathname, groups])

  const items = [
    {
      key: 'root',
      title: '平台控制台',
      icon: <HomeOutlined />,
      onClick: onGroupClick, // 首页一级同样退回分组索引，不跳路由（避免改默认落地行为）。
      className: 'dc-crumb-root',
    },
    ...(group
      ? [
          {
            key: 'group',
            title: group.label,
            onClick: onGroupClick,
            className: 'dc-crumb-link',
          },
        ]
      : []),
    ...(node
      ? [
          {
            key: 'leaf',
            title: node.label,
            className: 'dc-crumb-current',
          },
        ]
      : []),
  ] as Parameters<typeof AntBreadcrumb>[0]['items']

  return <AntBreadcrumb items={items} className="dc-crumb" />
}
