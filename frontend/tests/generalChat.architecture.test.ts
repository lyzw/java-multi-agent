/**
 * 通用知识问答的**身份域边界**守卫（源码级）。
 *
 * ⛔ 事故背景（2026-11-07）：admin-console 的 `/api/chat/**` 归入平台安全链，
 *    只认 `aud=admin-console-api`；租户前台持有的是 `aud=agent-platform` 令牌，
 *    直接复用必然 401，且前端若把 401 当登录过期会把对话草稿一起丢掉。
 *    租户通道必须是独立前缀、留在租户链上，两条链不得互相放宽。
 *
 * ⚠️ 防空转：先断言相关源码确实被读到且含预期锚点，再做包含 / 不包含判断；
 *    路径发现失败会让空串恒绿。
 */

import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const frontendSrc = join(here, '..', 'src')
const repoRoot = join(here, '..', '..')
const platformRoot = join(repoRoot, 'platform')

const readFrontend = (p: string): string =>
  readFileSync(join(frontendSrc, p), 'utf8')
const readPlatform = (p: string): string =>
  readFileSync(join(platformRoot, p), 'utf8')

/** 逐行剔除注释，只留代码行（与后端 ConsoleChatIdentityDomainTest 同构）。 */
function codeOnly(src: string): string {
  const out: string[] = []
  let inBlock = false
  for (const line of src.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (inBlock) {
      if (trimmed.includes('*/')) inBlock = false
      continue
    }
    if (trimmed.startsWith('/*')) {
      inBlock = !trimmed.includes('*/')
      continue
    }
    if (trimmed.startsWith('*') || trimmed.startsWith('//')) continue
    out.push(line)
  }
  return out.join('\n')
}

describe('通用问答身份域边界', () => {
  const transport = readFrontend('api/generalChatApi.ts')
  const app = readFrontend('App.tsx')
  const shell = readFrontend('layouts/AppShell.tsx')
  // ⚠️ P1-3（2026-11-17）：受众侧入口的元数据已收敛到 navCatalog，侧栏由它派生。
  const navCatalog = readFrontend('auth/navCatalog.ts')
  const controller = codeOnly(
    readPlatform(
      'agent-api/src/main/java/cn/studykid/multiagent/api/chat/ConsoleChatController.java',
    ),
  )
  const security = codeOnly(
    readPlatform(
      'platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/SecurityConfiguration.java',
    ),
  )

  it('防空转：四份源码都确实被读到', () => {
    expect(transport).toContain('streamGeneralChat')
    expect(app).toContain('<Route path="/rag"')
    // ⚠️ P1-3：入口元数据在 navCatalog，侧栏由它派生。
    expect(navCatalog).toContain("to: '/rag'")
    expect(controller).toContain('@PostMapping(value = "/stream"')
    expect(security).toContain('platformSecurityFilterChain')
  })

  it('⛔ 租户前端只允许调用租户专用端点', () => {
    expect(transport).toContain(
      "GENERAL_CHAT_PATH = '/api/knowledge/chat/stream'",
    )
    expect(codeOnly(transport)).not.toContain("'/api/chat/stream'")
    expect(codeOnly(transport)).not.toContain('fetch(`/api/chat')
  })

  it('⛔ 后端两个前缀分别归平台链与租户链，平台链不得收进新前缀', () => {
    expect(controller).toContain(
      '@RequestMapping({"/api/chat", "/api/knowledge/chat"})',
    )
    // 平台链 securityMatcher 可能跨行、且含多个管理前缀（如 model catalog）：
    // 折叠空白后只取首个 securityMatcher 的参数列表逐项验证，不依赖换行/前缀数量。
    const flat = security.replace(/\s+/g, ' ')
    const matcherOpen = flat.indexOf('securityMatcher(')
    expect(matcherOpen).toBeGreaterThan(-1)
    const matcherArgs = flat.slice(matcherOpen, flat.indexOf(')', matcherOpen) + 1)
    expect(matcherArgs).toContain('PLATFORM_API_PREFIX + "**"')
    expect(matcherArgs).toContain('CONSOLE_CHAT_PREFIX + "**"')
    // 平台链一旦纳入任意 /api/knowledge/** 前缀，租户令牌会被 PlatformBearerTokenFilter
    // 拒成 401。用前缀根（而非仅 /chat）兜住未来新增的 knowledge 子路径。
    expect(matcherArgs).not.toMatch(/KNOWLEDGE|knowledge/i)
    expect(flat).not.toContain('/api/knowledge')
    const publicStart = security.indexOf('PUBLIC_ENDPOINTS')
    const publicEnd = security.indexOf('}', publicStart)
    expect(publicEnd).toBeGreaterThan(publicStart)
    expect(security.slice(publicStart, publicEnd)).not.toContain('/api/knowledge')
  })

  it('⛔ 推理草稿不得回落到租户通道', () => {
    expect(controller).toContain('consoleThinkingAllowed()')
    expect(controller).toContain('!includeThinking')
    expect(codeOnly(transport)).toContain("case 'thinking':")
  })

  it('路由与导航入口必须成对存在（否则新页面是死链）', () => {
    expect(app).toContain('<Route path="/chat"')
    expect(navCatalog).toContain("to: '/chat'")
    expect(navCatalog).toContain("label: '通用问答'")
    // ⚠️ 侧栏必须**真的**从目录表派生（否则目录表只是摆设）
    expect(shell).toContain('AUDIENCE_ENTRIES.map(')
  })
})
