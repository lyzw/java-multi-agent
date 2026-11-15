/**
 * run_step 跨层口径守卫（E2E 二轮优化，2026-11-14）
 *
 * ⚠️ 背景：生产链路把每一步单条 INSERT 写死 `'RUNNING'::run_state`，语义是
 *    「该步骤已发生」（完成以 completed_at 判定），但 `run_state` 枚举里
 *    RUNNING 的字面含义是「进行中」。这个口径此前只存在于两个 Java 注释里，
 *    DDL / API / 前端结果页 / 测试夹具各自按字面语义解释 ⇒ 结果页把
 *    已完成步骤显示成「执行中」且无正文。
 *
 * ⛔ 本守卫锁死「写入方口径 → 契约注释 → DDL → 读侧呈现 → 夹具」整条链，
 *    任一层回退（例如有人把 RUNNING 改回显示「执行中」、或给 run_step
 *    补一个自相矛盾的 UPDATE state）都必须红。
 */

import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const repo = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = (p: string): string => readFileSync(join(repo, p), 'utf8')

const DDL = 'deploy/sql/platform/01_platform_ddl.sql'
const STEP_REPO = 'platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunStepRepository.java'
const AUDIT_REPO = 'platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentChatAuditRepository.java'
const STEP_PORT = 'platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/persistence/RunStepPort.java'
const RESULT_PORT = 'platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/persistence/ResultQueryPort.java'
const RESULT_CONTROLLER = 'platform/agent-api/src/main/java/cn/studykid/multiagent/api/run/ResultController.java'
const RESULT_PAGE = 'frontend/src/pages/business/RunResultPage.tsx'
const RESULT_TYPES = 'frontend/src/api/types.ts'
const SEARCH_PAGE = 'frontend/src/pages/rag/RagSearchPage.tsx'
const MGMT_PAGE = 'frontend/src/pages/rag/RagManagementPage.tsx'
const TENANT_STORAGE = 'frontend/src/features/rag/ragTenantStorage.ts'

/** 锚点守卫：缺失或出现禁止模式即抛错（便于直接做「撤掉修复点会红」的反证）。 */
function assertContract(
  source: string,
  mustContain: readonly string[],
  mustNotContain: readonly string[] = [],
): void {
  for (const anchor of mustContain) {
    if (!source.includes(anchor)) throw new Error(`缺少口径锚点：${anchor}`)
  }
  for (const forbidden of mustNotContain) {
    if (source.includes(forbidden)) throw new Error(`出现禁止模式：${forbidden}`)
  }
}

describe('run_step 口径：DDL 入库', () => {
  it('表与两列都必须有口径 COMMENT，且不得凭空造独立步骤枚举', () => {
    const ddl = read(DDL)
    expect(() => assertContract(ddl, [
      'COMMENT ON TABLE public.run_step',
      'COMMENT ON COLUMN public.run_step.state',
      'COMMENT ON COLUMN public.run_step.output_payload',
      '不表示进行中',
    ], ['CREATE TYPE public.run_step_state'])).not.toThrow()
  })

  it('⛔ 反证：删掉任意一条 COMMENT 会被守卫抓到', () => {
    const ddl = read(DDL)
    const flawed = ddl.replace('COMMENT ON COLUMN public.run_step.state IS', '-- removed')
    expect(() => assertContract(flawed, [
      'COMMENT ON COLUMN public.run_step.state',
    ])).toThrow(/缺少口径锚点/)
  })
})

describe('run_step 口径：生产写入方', () => {
  it('两条执行链都写死 RUNNING，且不存在「改步骤终态」的 UPDATE', () => {
    for (const file of [STEP_REPO, AUDIT_REPO]) {
      const code = read(file)
      expect(() => assertContract(code, ["'RUNNING'::run_state"])).not.toThrow()
      // ⛔ 两阶段状态迁移与「已发生」口径冲突；若将来真要做，必须连同
      //    枚举 / DDL / 契约 / 前端 / 夹具一起改，不能只加一条 UPDATE。
      expect(code).not.toMatch(/UPDATE\s+run_step\s+SET\s+state/i)
    }
  })

  it('RunStepPort.append 不接收 state / output（没有写入方就不该有参数）', () => {
    const port = read(STEP_PORT)
    const args = /void append\(([\s\S]*?)\);/.exec(port)?.[1] ?? ''
    expect(args).not.toBe('')
    expect(args).not.toMatch(/\bstate\b|\boutput\b/i)
    expect(port).toContain('该步骤已发生')
  })
})

describe('run_step 口径：契约层与读侧', () => {
  it('Port / Controller / types 都明写「RUNNING ≠ 进行中」', () => {
    for (const file of [RESULT_PORT, RESULT_CONTROLLER, RESULT_TYPES]) {
      expect(read(file)).toMatch(/不是进行中|非进行中|不得按 Run 状态机/)
    }
  })

  it('结果页把 RUNNING 映射为「已完成」并给绿点，且告知「无逐步正文」', () => {
    const page = read(RESULT_PAGE)
    expect(() => assertContract(page, [
      "RUNNING: '已完成'",
      "case 'RUNNING':",
      'return \'steps__item--done\'',
      '逐步讲解请看上方',
    ], ["RUNNING: '执行中'"])).not.toThrow()
  })

  it('⛔ 反证：把 RUNNING 改回「执行中」会被守卫抓到', () => {
    const page = read(RESULT_PAGE)
    const flawed = page.replace("RUNNING: '已完成'", "RUNNING: '执行中'")
    expect(() => assertContract(flawed, [
      "RUNNING: '已完成'",
    ], ["RUNNING: '执行中'"])).toThrow()
  })
})

describe('RAG 租户标识：两页共用一份存储实现（跨页串写守卫）', () => {
  it('两页都 import 共享模块，且不再直接读写 localStorage', () => {
    for (const file of [SEARCH_PAGE, MGMT_PAGE]) {
      const page = read(file)
      expect(() => assertContract(page, [
        "from '@/features/rag/ragTenantStorage'",
      ], ['localStorage.getItem(', 'localStorage.setItem('])).not.toThrow()
    }
  })

  it('共享模块有 TTL、JSON 结构与历史裸字符串兼容', () => {
    const mod = read(TENANT_STORAGE)
    expect(() => assertContract(mod, [
      'RAG_TENANT_STORAGE_TTL_MS',
      'JSON.stringify(payload)',
      "startsWith('{')",
      '跨页串写',
    ])).not.toThrow()
  })
})
