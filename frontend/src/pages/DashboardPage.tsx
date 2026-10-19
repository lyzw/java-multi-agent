/**
 * 工作台（FE-D1 F1；FE-01 P2）
 *
 * ⚠️ 后端无 Run 列表接口（G7），故本页**不做任务列表**。
 * ⛔ 不得用 localStorage 冒充「任务列表」—— 那会让用户以为看到了全部任务，
 *    而实际只看到本设备发起的，是**静默误导**（S4）。
 *    故显式说明「列表功能尚未开放」。
 */

import { Link } from 'react-router-dom'
import { NotAvailable } from '@/shared/ErrorView'
import { useSession } from '@/auth/session'

export function DashboardPage(): JSX.Element {
  const { session, signOut } = useSession()

  return (
    <main className="dashboard">
      <header className="dashboard__header">
        <h1>工作台</h1>
        <button type="button" onClick={signOut}>
          退出登录
        </button>
      </header>

      {/*
        ⚠️ 缺口 G1：登录响应不含 roles / 用户信息，
        故此处**不显示**当前身份 —— 不能凭空假设角色。
      */}
      {session !== null ? (
        <p className="muted">会话剩余有效时间由服务端控制，过期后将自动跳转登录页。</p>
      ) : null}

      <section className="dashboard__actions">
        <Link to="/runs/new">发起新的辅导</Link>
      </section>

      <NotAvailable
        feature="任务列表"
        detail="任务列表功能尚未开放；当前可在发起后通过链接查看单次分析进度"
      />
    </main>
  )
}
