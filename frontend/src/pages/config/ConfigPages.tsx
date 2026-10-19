/**
 * 配置页组（FE-D3）：P14–P19 只读骨架
 *
 * ⛔ **全部只读**：后端无配置读接口（C1–C9），
 *   且配置**最终怎么生效尚未决定**（FE-02 §9 第 1 项）。
 *   在生效机制未定前做出「看起来能配、实际改了没用」的界面，
 *   比没有更危险 —— 用户会以为配置生效了。
 */

import { Sheet, Unavailable, Marked, Empty } from '@/shared/states'

/* ── P14 配置总览 ────────────────────────────────────── */

export function ConfigOverviewPage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>配置总览</h1>
        <p className="main__lead">四类配置，以及各自能不能改。</p>
      </div>

      <Unavailable
        feature="配置总览"
        blockedBy="C1"
        reason="后端还没有提供读取配置的接口，配置生效方式也还没定"
        eta="P4"
      >
        <Sheet title="配置域">
          <table>
            <thead>
              <tr>
                <th scope="col">配置域</th>
                <th scope="col">当前值</th>
                <th scope="col">来源</th>
                <th scope="col">可否修改</th>
              </tr>
            </thead>
            <tbody aria-hidden="true">
              <tr>
                <td>模型路由</td>
                <td />
                <td />
                <td />
              </tr>
              <tr>
                <td>工具审批</td>
                <td />
                <td />
                <td />
              </tr>
              <tr>
                <td>容量限制</td>
                <td />
                <td />
                <td />
              </tr>
              <tr>
                <td>零保留供应商</td>
                <td />
                <td />
                <td />
              </tr>
            </tbody>
          </table>
        </Sheet>
      </Unavailable>
    </>
  )
}

/* ── P15 运行与容量 ──────────────────────────────────── */

export function ConfigRuntimePage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>运行与容量</h1>
        <p className="main__lead">并发上限、队列深度和限流阈值。</p>
      </div>

      <Unavailable
        feature="运行与容量"
        blockedBy="C3"
        reason="后端没有开放运行时指标"
        eta="P4"
      >
        <Sheet title="容量参数">
          <table>
            <thead>
              <tr>
                <th scope="col">参数</th>
                <th scope="col">值</th>
                <th scope="col">说明</th>
              </tr>
            </thead>
            <tbody aria-hidden="true">
              <tr>
                <td>并发上限</td>
                <td />
                <td />
              </tr>
              <tr>
                <td>队列深度</td>
                <td />
                <td />
              </tr>
              <tr>
                <td>单次运行花费上限</td>
                <td />
                <td />
              </tr>
            </tbody>
          </table>
        </Sheet>
      </Unavailable>
    </>
  )
}

/* ── P16 工具与审批 ──────────────────────────────────── */

export function ConfigToolsPage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>工具与审批</h1>
        <p className="main__lead">系统能调用哪些工具，各自的分级。</p>
      </div>

      <Unavailable
        feature="工具与审批"
        blockedBy="C4"
        reason="后端没有开放工具注册表的读取接口，审批流本身也还没做"
        eta="P4"
      >
        <Sheet title="工具清单">
          <table>
            <thead>
              <tr>
                <th scope="col">工具</th>
                <th scope="col">分级</th>
                <th scope="col">是否需要审批</th>
                <th scope="col">作用</th>
              </tr>
            </thead>
            <tbody aria-hidden="true">
              <tr>
                <td>读取学生档案</td>
                <td />
                <td />
                <td>只读</td>
              </tr>
              <tr>
                <td>记录薄弱点</td>
                <td />
                <td />
                <td>会写入</td>
              </tr>
            </tbody>
          </table>
        </Sheet>

        <Marked label="有一条规则不能调松">
          <p style={{ margin: 0 }}>
            涉及未成年人的保护项只能调严，不能调松。
            降低保护强度需要重新走评审，不能在页面上改。
          </p>
        </Marked>
      </Unavailable>
    </>
  )
}

/* ── P17 合规与零保留 ────────────────────────────────── */

export function ConfigCompliancePage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>合规与零保留</h1>
        <p className="main__lead">哪些模型供应商承诺不留存对话内容。</p>
      </div>

      <Unavailable
        feature="合规与零保留"
        blockedBy="C8"
        reason="零保留登记表还没接进运行时"
        eta="P4"
      >
        <Sheet title="供应商">
          <Empty title="还没有登记任何供应商" hint="登记要带上合同凭据编号。" />
        </Sheet>

        {/*
          ⛔ 此处**故意不含删除/修改控件**：
          零保留登记属法律承诺（GATE-2 已取得合同承诺），
          前端提供编辑入口即等于允许破坏承诺而无技术校验。
        */}
        <Marked label="为什么这一页不能改">
          <p style={{ margin: 0 }}>
            零保留是供应商在合同里承诺的条款，不是可以随手改的配置。
            这一页只展示，修改要走流程。
          </p>
          <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
            判断按供应商分别做：和一家确认能留存，不代表另一家也能。
          </p>
        </Marked>
      </Unavailable>
    </>
  )
}

/* ── P18 Agent 配置与发布 ────────────────────────────── */

export function AgentsPage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>Agent 配置</h1>
        <p className="main__lead">定义分析流程用的智能体。</p>
      </div>

      <Unavailable
        feature="Agent 配置"
        blockedBy="G2"
        reason="后端还没有提供查询和发布接口"
        eta="P3"
      >
        <Sheet title="Agent">
          <table>
            <thead>
              <tr>
                <th scope="col">名称</th>
                <th scope="col">当前版本</th>
                <th scope="col">状态</th>
              </tr>
            </thead>
            <tbody aria-hidden="true">
              <tr>
                <td />
                <td />
                <td />
              </tr>
            </tbody>
          </table>
        </Sheet>

        <Marked label="改配置不会影响正在进行的分析">
          <p style={{ margin: 0 }}>
            发布时会生成一份不可变的执行快照。已经发起的分析会继续用它开始时的那份，
            不会中途换配置。
          </p>
          <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
            这条说明在具备发布能力后仍要留着，
            否则会让人以为改配置会影响到在途的分析。
          </p>
        </Marked>
      </Unavailable>
    </>
  )
}

/* ── P19 配置变更审计 ────────────────────────────────── */

export function ConfigAuditPage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>配置变更审计</h1>
        <p className="main__lead">谁改过配置，改了什么。</p>
      </div>

      <Unavailable
        feature="配置变更审计"
        blockedBy="C6"
        reason="后端还没有提供审计查询接口"
        eta="P4"
      >
        <Sheet title="变更记录">
          <Empty title="没有变更记录" hint="配置被改动时会在这里留痕。" />
        </Sheet>

        {/* ⛔ 与 P10 同源：审计不含原文，此处同样不提供「查看完整请求体」。 */}
        <Marked label="记录里有什么">
          <p style={{ margin: 0 }}>
            只记录改了哪一项、从什么变成什么、谁改的、什么时候。
            不记录提交内容本身。
          </p>
        </Marked>
      </Unavailable>
    </>
  )
}