/**
 * 404 页（2026-10-24 新增）
 *
 * ⚠️ 此前未知路径被 `<Navigate to="/" replace />` **静默**弹到工作台：
 *    用户手输一个错网址 / 点一个过期链接，看到的却是"好像成功了"的工作台，
 *    没有任何"你要找的页面不存在"的反馈 —— 这是把系统的沉默当成答案。
 *
 * ⛔ 不提供「站点地图」式的大列表：本产品的导航由角色决定，
 *    列一份包含用户无权访问的入口的清单，等于把权限模型泄露进 404 页。
 *    只给两个真实的去向：回工作台 / 回检索记录。
 */

import { Link } from 'react-router-dom'
import { PageHead } from '@/components/ui'
import { Empty } from '@/shared/states'
import { Icon } from '@/components/Icon'

export function NotFoundPage(): JSX.Element {
  return (
    <>
      <PageHead
        title="页面不存在"
        lead="这个地址没有对应的页面。可能是链接已过期，或地址输入有误。"
      />
      <Empty
        icon="inbox"
        title="找不到你要的页面"
        hint="可以回到工作台重新开始，或到检索记录里找上次的分析。"
        action={
          <div className="btn-row">
            <Link to="/" className="btn">
              <Icon name="dashboard" />
              回工作台
            </Link>
            <Link to="/runs" className="btn btn--quiet">
              <Icon name="search" />
              检索记录
            </Link>
          </div>
        }
      />
    </>
  )
}
