/**
 * 配置页组（FE-D2 §1.1；详规见 FE-02 P14–P19）
 *
 * ⛔ **全部为只读骨架**：
 *   · 后端无配置读端点（C1–C9）
 *   · 且配置**最终怎么生效尚未决定**（FE-02 §9 第 1 项：
 *     yml+重启 还是 转 DB），这决定写入交互的形态。
 *
 * ⚠️ 因此本组**刻意不含任何写入控件**（输入框/保存按钮）：
 *   在生效机制未定前做出「看起来能配、实际改了没用」的界面，
 *   比没有更危险 —— 用户会以为配置生效了。
 */

import {
  DataUnavailable,
  BlockSkeleton,
  TableSkeleton,
} from '@/shared/DataUnavailable'

/* ── P14 配置总览 ─────────────────────────────────────── */

export function ConfigOverviewPage(): JSX.Element {
  return (
    <>
      <h1>配置总览</h1>
      <p className="muted">四类配置域的当前状态与可写性分级。</p>

      <DataUnavailable
        blockedBy="C1"
        feature="配置总览"
        reason="后端尚未提供配置读取端点；且配置生效机制（yml+重启 / 转数据库）尚未确定"
        eta="P4"
      >
        <BlockSkeleton
          title="配置域一览"
          note="预期含：模型路由、工具审批、容量限制、零保留供应商状态"
          rows={4}
        />
        <TableSkeleton
          columns={['配置域', '当前值', '来源', '可写性', '最近变更']}
          rows={4}
        />
      </DataUnavailable>
    </>
  )
}

/* ── P15 运行与容量 ───────────────────────────────────── */

export function ConfigRuntimePage(): JSX.Element {
  return (
    <>
      <h1>运行与容量</h1>
      <p className="muted">并发上限、队列深度、限流与熔断阈值。</p>

      <DataUnavailable
        blockedBy="C3"
        feature="运行与容量"
        reason="后端未暴露运行时指标（无端点，且 Actuator 为 show-details: never）"
        eta="P4"
      >
        <BlockSkeleton title="容量参数" rows={5} />
        <TableSkeleton
          columns={['参数', '值', '说明', '可写性']}
          rows={5}
        />
      </DataUnavailable>
    </>
  )
}

/* ── P16 工具与审批 ───────────────────────────────────── */

export function ConfigToolsPage(): JSX.Element {
  return (
    <>
      <h1>工具与审批</h1>
      <p className="muted">
        工具注册、审批策略与数据分级。
        <strong className="warn-inline">
          「只收紧不能放松」：未成年人保护强度禁止运行时下调。
        </strong>
      </p>

      <DataUnavailable
        blockedBy="C4"
        feature="工具与审批"
        reason="后端无工具注册表读取端点；审批流本身也尚未实现（P3-D05 暂为 fail-closed 拒绝）"
        eta="P4"
      >
        <BlockSkeleton title="工具清单" rows={3} />
        <TableSkeleton
          columns={['工具', '分级', '是否需审批', '幂等键', '可写性']}
          rows={3}
        />
      </DataUnavailable>
    </>
  )
}

/* ── P17 合规与零保留 ─────────────────────────────────── */

export function ConfigCompliancePage(): JSX.Element {
  return (
    <>
      <h1>合规与零保留</h1>
      <p className="muted">零保留供应商登记、跨境标注与保留期策略。</p>

      <DataUnavailable
        blockedBy="C8"
        feature="合规与零保留"
        reason="零保留注册表尚未接入运行时（ConfigFailClosedTest 保障下当前零可用供应商）"
        eta="P4"
      >
        <BlockSkeleton title="零保留供应商" rows={3} />
        <TableSkeleton
          columns={['供应商', '零保留', '是否出境', '合同凭据', '生效日期']}
          rows={3}
        />

        {/*
          ⛔ 此区块**故意不含删除/修改控件**：
          零保留登记属法律承诺（GATE-2 已取得合同承诺），
          前端提供编辑入口即等于允许破坏承诺而无技术校验。
        */}
        <section className="notice notice--warn">
          <p>
            <strong>合规约束</strong>
            ：零保留状态按供应商<b>分别</b>判定，
            且登记必须带凭据引用（evidenceRef 非空）。
          </p>
          <p className="muted">
            本页不提供登记编辑入口——法律承诺类数据不应由前端自由改动。
          </p>
        </section>
      </DataUnavailable>
    </>
  )
}

/* ── P18 Agent 配置与发布 ─────────────────────────────── */

export function AgentsPage(): JSX.Element {
  return (
    <>
      <h1>Agent 配置与发布</h1>
      <p className="muted">Agent 草稿、版本与发布。</p>

      <DataUnavailable
        blockedBy="G2"
        feature="Agent 配置"
        reason="后端无 agent:manage 端点，无法查询或发布 Agent"
        eta="P3"
      >
        <section className="notice notice--info">
          <p>
            <strong>配置快照隔离</strong>（DEC-9 / FR-002）：
            修改草稿<b>不会</b>影响已创建的 Run ——
            发布时会生成不可变执行快照，Run 启动时按版本号绑定。
          </p>
          <p className="muted">
            该说明在具备发布能力后仍须保留，
            否则用户会误以为改配置会影响在途任务。
          </p>
        </section>

        <BlockSkeleton title="Agent 列表" rows={3} />
        <TableSkeleton columns={['名称', '当前版本', '状态', '最近发布', '操作']} rows={3} />
      </DataUnavailable>
    </>
  )
}

/* ── P19 配置变更审计 ─────────────────────────────────── */

export function ConfigAuditPage(): JSX.Element {
  return (
    <>
      <h1>配置变更审计</h1>
      <p className="muted">配置项的变更历史与责任人。</p>

      {/*
        ⛔ 与 P10 同源红线：审计日志不含 Prompt 原文（保留 180 天），
          因此本页**不提供**「查看完整请求体」入口 —— 从结构上杜绝。
      */}
      <DataUnavailable
        blockedBy="C6"
        feature="配置变更审计"
        reason="后端无审计查询端点（audit:read 未实现）"
        eta="P4"
      >
        <TableSkeleton
          columns={['时间', '操作人', '配置域', '变更项', '前后值']}
          rows={6}
        />
        <p className="muted">
          审计记录保留 180 天，且不含提示词原文——
          本页不提供查看完整请求体的入口。
        </p>
      </DataUnavailable>
    </>
  )
}