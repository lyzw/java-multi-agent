import { Breadcrumb as AntBreadcrumb } from 'antd'
import { HomeOutlined } from '@ant-design/icons'
import { useLocation } from 'react-router-dom'
import { findNavNode, type NavGroup } from '@/layout/navConfig'
import { useMemo } from 'react'

interface BreadcrumbProps {
  /** 已按角色过滤的可见分组——面包屑不能暴露无权分组的标签。 */
  groups: NavGroup[]
  /**
   * 点击「控制台 / 分组」级：展开侧栏对应分组的子菜单（不跳路由、不改默认落地行为）。
   * 不带参数 = 控制台级，展开当前路由所属分组；带参数 = 展开所点分组。
   */
  onGroupClick: (groupKey?: string) => void
}

/**
 * 面包屑导航：控制台 › 分组 › 页面。
 *
 * 与侧栏联动——中间「分组」一级可点击，点击后侧栏就地展开该分组
 * （由 AdminLayout 的 onGroupClick 追加展开，不替换视图）。
 *
 * 路径映射由 NAV_GROUPS 派生 + findNavNode 反查（单一来源），
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
      onClick: () => onGroupClick(), // 控制台级：展开当前路由所属分组，不跳路由。
      className: 'dc-crumb-root',
    },
    ...(group
      ? [
          {
            key: 'group',
            title: group.label,
            onClick: () => onGroupClick(group.key),
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
