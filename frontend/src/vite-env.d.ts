/// <reference types="vite/client" />

/**
 * 环境变量类型声明
 *
 * ⚠️ 本项目不使用 `import.meta.env` 直接内联敏感值——
 * 任何密钥/口令都**不得**出现在前端构建产物中（FE-D1 §11 CI 扫描项）。
 * 此处仅声明非敏感配置。
 */

interface ImportMetaEnv {
  /** 开发代理指向的后端地址（仅本地开发用） */
  readonly VITE_DEV_API_TARGET?: string
  /**
   * Agent 标识。
   * ⚠️ 缺口 G2：后端无 Agent 管理接口，当前只能由环境变量注入，
   *    无法在界面切换。真实方案需后端补 GET /api/agents。
   */
  readonly VITE_AGENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
