import type { ReactNode } from 'react'
import {
  ApartmentOutlined,
  AppstoreOutlined,
  ControlOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  FileProtectOutlined,
  FileSearchOutlined,
  LineChartOutlined,
  MergeCellsOutlined,
  MessageOutlined,
  PartitionOutlined,
  RobotOutlined,
  BookOutlined,
  TeamOutlined,
  ToolOutlined,
  ThunderboltOutlined,
  UserOutlined,
  ApiOutlined,
} from '@ant-design/icons'
import { PLATFORM_SCOPE_ROLES, ROLES, type Role } from '@/config/constants'

export interface NavNode {
  key: string
  label: string
  path: string
  icon: ReactNode
  requiredRoles: Role[]
  description: string
  /** 所属分组标签（由 NAV_GROUPS 派生，供仪表盘等只读消费） */
  phase: string
}

export interface NavGroup {
  key: string
  label: string
  icon: ReactNode
  requiredRoles: Role[]
  children: NavNode[]
}

/**
 * 平台控制台导航树（分组重构）。
 *
 * ⚠️ **分组的唯一来源是 `NAV_GROUPS`**，`NAV_NODES` 是由它派生的扁平视图
 *    （保留给仪表盘「模块导航」等只读消费方）。
 *    刻意不让两处各写一份——分组与节点一旦分家，
 *    就会出现「侧栏有、仪表盘没有」这类只在某一条路径上可见的偏差。
 *
 * ⚠️ 能力目录四页（Agent/Tool/Skill/MCP）走平台链端点
 *    /api/platform/v1/capabilities/*（平台令牌 aud=admin-console-api）；
 *    旧租户链 /api/cap/* 端点对平台令牌不可达。
 *    角色仅用于导航显隐，安全边界以执行时后端重授权为准。
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'governance',
    label: '平台治理',
    icon: <ApartmentOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'tenants',
        label: '租户管理',
        path: '/tenants',
        icon: <ApartmentOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '租户生命周期状态机（PENDING_SETUP→ACTIVE→SUSPENDED→DECOMMISSIONING…）、配额、能力授权。开通/启停需 TENANT_LIFECYCLE_ADMIN 或 SUPER_ADMIN。',
        phase: '平台治理',
      },
      {
        key: 'users',
        label: '平台用户',
        path: '/users',
        icon: <UserOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '平台用户全生命周期：新建账号、启用/停用、重置密码、赋予/撤销角色。写端点 /api/platform/v1/users/*（自身保护 + 超管 SoD）。',
        phase: '平台治理',
      },
      {
        key: 'roles',
        label: '角色权限矩阵',
        path: '/roles',
        icon: <TeamOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '6 个最小特权角色的冻结权限矩阵（代码冻结，运行期不可改），按命名空间分组只读呈现，含中文释义。角色赋权走「平台用户」页或带外运维通道。',
        phase: '平台治理',
      },
      {
        key: 'audit',
        label: '平台审计',
        path: '/audit',
        icon: <FileProtectOutlined />,
        requiredRoles: [ROLES.SUPER_ADMIN, ROLES.AUDITOR],
        description:
          '平台管理动作审计（登录/租户生命周期/配额/能力授权/用户管理），读 platform_audit_event，与租户业务审计物理分域。仅追加、只读。',
        phase: '平台治理',
      },
    ],
  },
  {
    key: 'capability',
    label: '能力目录',
    icon: <AppstoreOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'agents',
        label: 'Agent 注册表',
        path: '/agents',
        icon: <RobotOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '跨租户 Agent 只读盘点（平台链 /api/platform/v1/capabilities/agents，BYPASSRLS 数据源 + 会话窗）。只有元数据：定义正文、运行输出永不出现（禁区红线），不提供写端点。',
        phase: '能力目录',
      },
      {
        key: 'tools',
        label: 'Tool',
        path: '/tools',
        icon: <ToolOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '平台级 Tool 目录与三步发布工作流（申请→审批→执行）。登记/修改经 PlatformRegistrationModal，停用走生命周期申请。',
        phase: '能力目录',
      },
      {
        key: 'skills',
        label: 'Skill',
        path: '/skills',
        icon: <MergeCellsOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '平台级 Skill 目录（含 Tool/MCP 闭包声明）与三步发布工作流。声明的对象取不到定义会被 400 拒（闭包自洽）。',
        phase: '能力目录',
      },
      {
        key: 'mcps',
        label: 'MCP Server',
        path: '/mcps',
        icon: <PartitionOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '平台级 MCP Server 目录与三步发布工作流。凭据定位符从不回显，重发必须显式重新填写（fail-closed）。',
        phase: '能力目录',
      },
      {
        key: 'routing-policies',
        label: '路由策略',
        path: '/routing-policies',
        icon: <MergeCellsOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '有界规则引擎的策略登记与试跑。试跑把草稿原样交给后端 agent-routing 的同一个引擎与守卫'
          + '（前端无第二份求值器，故界面结论与运行期一致）。'
          + '⚠️ 端点属租户域（/api/cap/config/**，租户来自验签令牌），平台令牌可能 401/403；'
          + '此时页面明示「需租户级令牌通道」，不会把「没跑成」显示成「没问题」。',
        phase: '能力目录',
      },
    ],
  },
  {
    key: 'knowledge',
    label: '知识与数据',
    icon: <DatabaseOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'rag',
        label: 'RAG 语料管理',
        path: '/rag/dashboard',
        icon: <FileSearchOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '教辅语料治理：登记 → 校验流转 → 可检索。独立 RAG 服务（默认 8081，/api/v1/management/*），'
          + '平台数据优先：首屏默认展示平台全局语料、不选租户（读走登录态、写需治理票据）；'
          + '切到本租户语料档需选租户（读写俱全）。作用域由 X-Rag-Scope 显式声明。撤回是终态、无删除入口。',
        phase: '知识与数据',
      },
    ],
  },
  // ------------------------------------------------------------------
  // RAG 语料管理 · 四治理域分区（RAG-D13 §2.1 / RAG-D14）
  //
  // ⚠️ 改造前 RAG 只有 /rag 一个入口，页内挂 5 个 Tab——用户进去不知道先看哪，
  //    且「审核」「运维」两种完全不同的职责混在同一页（RAG-D12 靶点 G）。
  //    现按治理域拆成四个分组，每组页面共享同一类角色与同一种操作语气。
  // ------------------------------------------------------------------
  {
    key: 'rag-ingest',
    label: 'RAG · 录入与校对',
    icon: <FileSearchOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'rag-dashboard',
        label: 'Dashboard 总览',
        path: '/rag/dashboard',
        icon: <AppstoreOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '三面健康度一览：写面入库 · 读面检索 · 治理面审核，按角色给出下一步。',
        phase: 'RAG · 录入与校对',
      },
      {
        key: 'rag-source-new',
        label: '来源登记',
        path: '/rag/sources/new',
        icon: <FileProtectOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '登记来源（作品级）：版本五轴、权威等级与版权状态，五轴缺一不可入库。',
        phase: 'RAG · 录入与校对',
      },
      {
        key: 'rag-source-list',
        label: '来源列表',
        path: '/rag/sources',
        icon: <FileSearchOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '浏览与筛选全部来源，快速定位治理状态与生命周期。',
        phase: 'RAG · 录入与校对',
      },
      {
        key: 'rag-doc-upload',
        label: '载体上传与解析预览',
        path: '/rag/doc-upload',
        icon: <ThunderboltOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '上传载体后先看解析结果（切片/角色着色/页码），确认再入库——切分错误会直接导致泄题。',
        phase: 'RAG · 录入与校对',
      },
      {
        key: 'rag-chunk-fix',
        label: '片段校对',
        path: '/rag/chunk-fix',
        icon: <FileProtectOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '人工纠正片段角色，重点处理疑似答案区误判（判成讲解会直接泄题）。',
        phase: 'RAG · 录入与校对',
      },
    ],
  },
  {
    key: 'rag-review',
    label: 'RAG · 审核',
    icon: <FileProtectOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'rag-verify',
        label: '校验决定',
        path: '/rag/verify',
        icon: <FileProtectOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: 'VERIFIED / REVOKED 决定；VERIFIED 须证据并双人复核，REVOKED 须填原因。',
        phase: 'RAG · 审核',
      },
      {
        key: 'rag-inspection',
        label: '抽检队列',
        path: '/rag/inspection',
        icon: <MergeCellsOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '对疑似片段人工抽检；抽检未通过则来源签不出 VERIFIED（必经关卡）。',
        phase: 'RAG · 审核',
      },
      {
        key: 'rag-recall',
        label: '召回解释（质检）',
        path: '/rag/recall',
        icon: <LineChartOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '模拟检索：召回什么、为什么、是否触发门禁。学生搜不到时先在这里自查。',
        phase: 'RAG · 审核',
      },
    ],
  },
  {
    key: 'rag-rules',
    label: 'RAG · 规则配置',
    icon: <ControlOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'rag-reviewers',
        label: '审核员名录',
        path: '/rag/reviewers',
        icon: <TeamOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '维护审核员与复核权限，显式暴露职责分离（SoD）冲突。',
        phase: 'RAG · 规则配置',
      },
      {
        key: 'rag-kp-alias',
        label: '知识点别名',
        path: '/rag/kp-alias',
        icon: <BookOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '维护知识点别名，让口语化问法确定性匹配到规范知识点。',
        phase: 'RAG · 规则配置',
      },
      {
        key: 'rag-dictionaries',
        label: '字典',
        path: '/rag/dictionaries',
        icon: <BookOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '维护治理下拉枚举的数据源；仅「来源类型」可编辑，其余为派生只读。',
        phase: 'RAG · 规则配置',
      },
    ],
  },
  {
    key: 'rag-ops',
    label: 'RAG · 运维',
    icon: <ToolOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'rag-jobs',
        label: '摄取任务',
        path: '/rag/jobs',
        icon: <ThunderboltOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '入库任务三态 + 单进度条；失败可重试（新建一次尝试，旧尝试留痕）。',
        phase: 'RAG · 运维',
      },
      {
        key: 'rag-reindex',
        label: '重索引',
        path: '/rag/reindex',
        icon: <DatabaseOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description: '补齐缺失向量 / 重建索引（同步执行）。蓝绿代际切换后端尚未提供。',
        phase: 'RAG · 运维',
      },
    ],
  },
  {
    key: 'system',
    label: '系统配置',
    icon: <ControlOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'dictionaries',
        label: '字典配置',
        path: '/dictionaries',
        icon: <BookOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '把前端下拉枚举收敛到 sys_dictionary / sys_dictionary_item 两表维护：新增、改名、停用无需改前端代码。内置字典禁止物理删除，仅可停用（避免历史数据取值悬空）。',
        phase: '系统配置',
      },
      {
        key: 'model-catalog',
        label: '模型配置',
        path: '/model-catalog',
        icon: <ApiOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '模型配置（Model Catalog）：模型定义 CRUD（供应商 / 场景 / 能力开关 / 维度 / 排序）+ 供应商密钥（仅脱敏回显，明文只上传不回显）。写端点需 PLATFORM_ADMIN，安全边界以执行时后端重授权为准。',
        phase: '系统配置',
      },
    ],
  },
  {
    key: 'observability',
    label: '观测度量',
    icon: <LineChartOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'metrics',
        label: 'Agent 效果指标',
        path: '/metrics',
        icon: <LineChartOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '六大类线上指标（SLA/成本/过程行为/业务效果/用户反馈/风险安全），按 L0 系统事实 → L1 自动校验 → L2 抽样标注 → L3 有噪信号分层标注可信度。⚠️ 未采集显示「未采集」而非 0；样本量 < 30 显示「样本不足」；链路成功 ≠ 任务成功。',
        phase: '观测度量',
      },
      {
        key: 'metrics-entry',
        label: '效果数据录入',
        path: '/metrics-entry',
        icon: <FileSearchOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '手动回填四类效果数据：L1 自动校验回执 / L2 抽样标注（黄金基线）/ 风险事件 / L3 用户反馈。要求 platform.resource.manage；风险事件只接受摘要绝不接收原文（合规红线）。租户侧反馈请勿经此页，走租户端反馈入口。',
        phase: '观测度量',
      },
    ],
  },
  {
    key: 'lab',
    label: 'AI 实验室',
    icon: <ExperimentOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'agent-lab',
        label: 'Agent 效果测试',
        path: '/agent-lab',
        icon: <ThunderboltOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '挑选 Agent 与用例，发起一次真实执行并观察事件流、工具调用、耗时与预算消耗，用于回归比对与效果验收。',
        phase: 'AI 实验室',
      },
      {
        key: 'ai-chat',
        label: 'AI 对话',
        path: '/ai-chat',
        icon: <MessageOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '基于现有 RAG 检索/生成端点的语料问答：输入问题、阶段增量接收回答、引用可核对，覆盖停止、重试、超时与依据不足。回答当前为逐字摘录装配，非大模型生成；平台控制台禁区红线不展示租户运行输出正文。',
        phase: 'AI 实验室',
      },
    ],
  },
]

/** 扁平视图（由分组派生）——供仪表盘等只读消费方使用。 */
export const NAV_NODES: NavNode[] = NAV_GROUPS.flatMap((g) => g.children)
