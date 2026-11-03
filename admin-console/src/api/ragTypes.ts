/**
 * RAG 语料数据管理 —— 前后端契约类型与中文标签映射（RAG-D10 管理面）。
 *
 * ⚠️ 本文件是 admin-console 侧对 `rag-management` 模块的契约镜像，
 *    取值/枚举严格对齐后端 `ManagementDtos` 与 `rag-core` 枚举。
 *    治理纪律（RAG-D07 §2.4 / RAG-D10）一并写进类型注释，供 UI 层遵守：
 *      · `stale` / `admissible` 是**服务端计算属性**，前端不得自行推算；
 *      · REVOKED 是终态，不可复活；
 *      · 409 = 非法状态转移，不是服务故障。
 */

/* ── 枚举（严格对齐后端）────────────────────────────── */

/**
 * 语料来源的校验状态（对齐后端 `SourceVerification.VerificationStatus`）。
 *
 * ⚠️ 取值与后端枚举严格一致（RAG-D07 §2.4 状态机 T-1～T-12）：
 *    PENDING 待校验 → IN_REVIEW 审核中 → VERIFIED 已通过
 *    REJECTED 已拒绝 / REVOKED 已撤回
 *
 * ⚠️ **REVOKED 是终态**：撤回后不可复活，重新准入必须新建 source_id。
 *    前端不得提供「撤销撤回」入口。
 */
export type RagVerificationStatus =
  | 'PENDING'
  | 'IN_REVIEW'
  | 'VERIFIED'
  | 'REJECTED'
  | 'REVOKED'

/** 权威等级（对齐后端 `TrustTier`）。 */
export type RagTrustTier =
  | 'OFFICIAL_PUBLISHER'
  | 'OFFICIAL_TUTORIAL'
  | 'PEER_REVIEWED_PAPER'
  | 'PREPRINT'
  | 'UNVERIFIED'

/** 校验手段（对齐后端 `SourceVerification.VerificationMethod`）。 */
export type RagVerificationMethod =
  | 'NONE'
  | 'ISBN'
  | 'DOI'
  | 'MANUAL_REVIEW'
  | 'OFFICIAL_HASH'

/** 版权状态（对齐后端 `rag_license_status` 枚举，V3 迁移后为 DB 枚举）。 */
export type RagLicenseStatus =
  | 'LICENSED'
  | 'FAIR_USE_QUOTATION'
  | 'PENDING'
  | 'RESTRICTED'

/**
 * 文档载体类型（对齐后端 `DocumentImportCommand` 的白名单）。
 *
 * ⚠️ 后端只接受 `PDF / SCAN / EPUB / HTML / TXT` 五种大写值；
 *    `SCAN` 只能显式给出（扫描件往往是一组图片，无法从单一文件后缀推断）。
 *    前端上传时若没显式选 carrier，则由文件名后缀推断（PDF/EPUB/HTML/HTM/TXT/MD）。
 */
export type RagCarrier = 'PDF' | 'SCAN' | 'EPUB' | 'HTML' | 'TXT'

/* ── 视图对象 ─────────────────────────────────────────── */

/**
 * 来源视图（对齐后端 `ManagementDtos.SourceResponse`）。
 *
 * ⚠️ `stale` 与 `admissible` 是**服务端算好的计算属性**：
 *    · stale = 校验结论已过期（verified_at + valid_days < now）
 *    · admissible = 当前准入（VERIFIED ∧ 未过期 ∧ 达门槛 ∧ 版权放行）
 *    前端**不得**自行推算，租户权威门槛只有服务端知道。
 */
export interface RagSource {
  sourceId: string
  sourceType: string
  title: string
  publisher: string
  isbn: string | null
  doi: string | null
  trustTier: RagTrustTier
  verificationStatus: RagVerificationStatus
  verificationMethod: RagVerificationMethod
  verificationEvidence: string | null
  verifiedAt: string | null
  verifiedBy: string | null
  validDays: number
  licenseStatus: RagLicenseStatus
  /** 校验结论是否已过期（计算属性） */
  stale: boolean
  /** 当前是否准入可检索（计算属性） */
  admissible: boolean
  objectRef: string | null
  contentHash: string | null
  createdAt: string
  updatedAt: string
  /**
   * 抽检状态（后端读路径自 rag_source.inspection_status 透出；权威闸门字段）。
   *
   * ⚠️ null / 缺省 = 「本次响应未读取」（写路径响应、旧版后端）——
   * 不是任何一种结论，调用方应回退为按抽检历史推导。
   */
  inspectionStatus?: RagInspectionStatus | null
  /**
   * 载体（文档）数量（后端读路径批量统计透出）。
   *
   * ⚠️ 0 = 「仅元数据」登记（从未上传物理文件）——列表页据此打标识，
   * 避免管理员点进一个没有语料的来源白忙一场（2026-11-03 抽检发现）。
   * 缺省（旧版后端）按「未知」处理，不打标识。
   */
  documentCount?: number
}

/** 文档载体视图（对齐后端 `ManagementDtos.DocumentResponse`）。 */
export interface RagDocument {
  documentId: string
  sourceId: string
  editionId: string
  carrier: string
  title: string
  pageCount: number | null
  objectRef: string | null
  parseStatus: string
  parserVersion: string | null
  contentHash: string | null
  chunkCount: number
  /** 载体级校验状态（V3 迁移新增字段） */
  verificationStatus?: RagVerificationStatus
  /** 载体级可检索标记 —— 撤回时置 false */
  retrievable?: boolean
  /** 撤回时刻 */
  revokedAt?: string | null
  /** 撤回依据（DB CHECK：REVOKED 必带依据） */
  revokedReason?: string | null
  /** 载体级版权状态（独立于来源级版权） */
  licenseStatus?: RagLicenseStatus
  createdAt: string
  updatedAt: string
}

/** 片段摘要（对齐后端 `ManagementDtos.ChunkResponse`）。 */
export interface RagChunk {
  chunkId: string
  role: string
  pageNumber: number
  sectionPath: string
  exerciseNo: string | null
  charLength: number
  /** 是否已写入向量 —— 缺失即「静默漏召回」风险 */
  hasEmbedding: boolean
  contentHash: string | null
}

/** 索引统计（对齐后端 `ManagementDtos.StatisticsResponse`）。 */
export interface RagStatistics {
  totalSources: number
  verifiedSources: number
  admissibleSources: number
  totalDocuments: number
  totalChunks: number
  /** 可检索片段数 —— 与 totalChunks 的差额即「已入库但不可检索」 */
  retrievableChunks: number
  /** 已入库但缺向量的片段数 —— 静默漏召回风险 */
  chunksMissingEmbedding: number
  missingEmbeddingRatio: number
  chunksByRole: Record<string, number>
  sourcesByVerificationStatus: Record<string, number>
  documentsByParseStatus: Record<string, number>
}

/** 来源登记请求（对齐后端 `ManagementDtos.RegisterSourceRequest`）。 */
export interface RagRegisterSourceRequest {
  sourceType: string
  title: string
  publisher: string
  isbn: string | null
  doi: string | null
  objectRef: string | null
  contentHash: string | null
  /** 登记人 UUID —— 登记必须留痕（后端 principal_id NOT NULL） */
  registeredBy: string
}

/**
 * 批量导入的单条来源项（对齐后端 `ManagementDtos.BulkSourceItem`）。
 *
 * ⚠️ 不含 `registeredBy`：批量导入的登记人对整批统一留痕，由批量请求头部的
 *    `registeredBy` 提供，避免 CSV/JSON 每行重复写 UUID。
 */
export interface RagBulkSourceItem {
  sourceType: string
  title: string
  publisher: string
  isbn: string | null
  doi: string | null
  objectRef: string | null
  contentHash: string | null
}

/** 批量导入逐条结果（对齐后端 `ManagementDtos.BulkItemResultDto`）。 */
export interface RagBulkImportItemResult {
  /** 该行在提交数组中的下标（从 0 开始） */
  index: number
  /** 是否登记成功 */
  ok: boolean
  /** 成功时的来源 ID（失败为 null） */
  sourceId: string | null
  /** 失败原因（成功为 null） */
  error: string | null
}

/** 批量导入响应（对齐后端 `ManagementDtos.BulkRegisterResponse`）。 */
export interface RagBulkImportResponse {
  /** 请求总条数 */
  total: number
  /** 成功条数 */
  succeeded: number
  /** 失败条数 */
  failed: number
  /** 逐条结果（与请求同序） */
  items: RagBulkImportItemResult[]
}

/** 校验决定请求（对齐后端 `ManagementDtos.VerificationRequest`）。 */
export interface RagVerificationRequest {
  targetStatus: RagVerificationStatus
  /**
   * 复核人主体 UUID（双人复核的第二个人）。
   *
   * ⚠️ 判定 VERIFIED 时**必填**，且必须与执行人（登录主体）不同——
   *    后端 `SegregationOfDuties` 会比对并拒绝「缺少复核人 / 与执行人同一人」。
   *    其余状态传 null。
   *
   * ⚠️ 这个值必须由**人工填写**，不能由前端自造：后端无法验证它是真实第二个人，
   *    前端若"自动补一个"就等于替审核员伪造了一次复核。
   */
  verifiedBy: string | null
  /** 校验依据 —— 必填（后端强制留痕） */
  evidenceRef: string
  /** 决定人 UUID —— 必填 */
  decidedBy: string
  verificationMethod: RagVerificationMethod
  /** 判定 VERIFIED 时必填（否则来源通过校验却仍被权威门槛拦下） */
  trustTier: RagTrustTier | null
  validDays: number
}

/**
 * 载体级校验决定请求（对齐后端 `ManagementDtos.DocumentVerificationRequest`）。
 *
 * ⚠️ 字段与来源级**同构**，但作用对象不同：载体是「物理载体」
 *    （一份 PDF / 一册扫描件），下架它不影响同版本的其它载体。
 */
export interface RagDocumentVerificationRequest {
  targetStatus: RagVerificationStatus
  /** 校验依据 —— 必填（撤回必带依据，DB CHECK 亦会拦） */
  evidenceRef: string
  /** 决定人 UUID —— 必填 */
  decidedBy: string
  verificationMethod: RagVerificationMethod
  trustTier: RagTrustTier | null
  validDays: number
}

/* ── 维护问题清单 ─────────────────────────────────────── */

/**
 * 维护问题清单（对齐后端 `ManagementDtos.IssuesResponse`）。
 *
 * ⚠️ 这些是**「哪里坏了」的定位信息**，不是统计数字——
 *    统计只说「缺 12 个向量」，清单才说「是哪几个载体」。
 */
export interface RagMaintenanceIssues {
  /** 缺向量的载体（片段已入库但没有向量 → 静默漏召回） */
  documentsMissingEmbedding: RagIssueDocument[]
  /** 解析失败的载体（不产出片段 → 这份语料在检索侧等于不存在） */
  documentsFailedParse: RagIssueDocument[]
  /** 待审核的来源 */
  sourcesPendingReview: RagIssueSource[]
  totals: {
    documentsMissingEmbedding: number
    documentsFailedParse: number
    sourcesPendingReview: number
  }
}

/** 问题清单里的载体条目。 */
export interface RagIssueDocument {
  documentId: string
  sourceId: string
  title: string
  carrier: string
  parseStatus: string
  chunkCount: number
  /** 缺向量的片段数（仅 documentsMissingEmbedding 有意义） */
  missingEmbeddingCount?: number
}

/** 问题清单里的来源条目。 */
export interface RagIssueSource {
  sourceId: string
  title: string
  verificationStatus: RagVerificationStatus
  stale: boolean
  trustTier: RagTrustTier
}

/** 重跑索引结果（对齐后端 `ManagementDtos.ReindexResponse`）。 */
export interface RagReindexResult {
  indexedChunks: number
  scannedChunks: number
  durationMs: number
}

/** 来源查询参数（对齐后端 `SourceQuery`）。 */
export interface RagSourceQuery {
  status?: RagVerificationStatus | null
  trustTier?: RagTrustTier | null
  keyword?: string | null
  includeStale?: boolean
  limit?: number
  offset?: number
}

/* ── 中文标签映射 ─────────────────────────────────────── */

/** 校验状态的可读标签。 */
export const RAG_VERIFICATION_LABELS: Record<RagVerificationStatus, string> = {
  PENDING: '待校验',
  IN_REVIEW: '审核中',
  VERIFIED: '已通过',
  REJECTED: '已拒绝',
  REVOKED: '已撤回',
}

/** 权威等级的可读标签。 */
export const RAG_TRUST_TIER_LABELS: Record<RagTrustTier, string> = {
  OFFICIAL_PUBLISHER: '教材出版社官方',
  OFFICIAL_TUTORIAL: '教辅品牌正式出版',
  PEER_REVIEWED_PAPER: '同行评审论文',
  PREPRINT: '预印本 / 白皮书',
  UNVERIFIED: '未校验',
}

/** 校验手段的可读标签。 */
export const RAG_VERIFICATION_METHOD_LABELS: Record<RagVerificationMethod, string> = {
  NONE: '无',
  ISBN: 'ISBN 校验',
  DOI: 'DOI 校验',
  MANUAL_REVIEW: '人工逐份比对',
  OFFICIAL_HASH: '官方哈希比对',
}

/** 版权状态的可读标签。 */
export const RAG_LICENSE_LABELS: Record<RagLicenseStatus, string> = {
  LICENSED: '已获授权',
  FAIR_USE_QUOTATION: '合理引用',
  PENDING: '未取得授权',
  RESTRICTED: '禁止使用',
}

/** 文档载体类型的可读标签。 */
export const RAG_CARRIER_LABELS: Record<RagCarrier, string> = {
  PDF: 'PDF（电子版）',
  SCAN: '扫描件',
  EPUB: 'EPUB 电子书',
  HTML: 'HTML 网页',
  TXT: '纯文本',
}

/**
 * 片段角色的可读标签（对齐后端 `rag-core` 的 `ChunkRole` 枚举）。
 *
 * ⚠️ 用 `Record<string, string>` 而非 `Record<ChunkRole, string>`：
 *    分布表的 key 来自 DB 的 `role::text`，**类型上不可知**，
 *    新增枚举值时不该让前端编译失败（那会阻断构建而非暴露问题）。
 */
export const RAG_CHUNK_ROLE_LABELS: Record<string, string> = {
  EXPLANATION: '正文讲解',
  EXAMPLE: '例题',
  EXAMPLE_SOLUTION: '例题标准解答',
  EXERCISE: '习题',
  EXERCISE_ANSWER: '习题答案',
  EXERCISE_HINT: '习题提示',
  PAPER_BODY: '论文正文',
  PAPER_METHOD: '论文方法',
  APPENDIX: '附录',
}

/**
 * 载体解析状态的可读标签（对齐 `rag_document.parse_status` 的 DB CHECK 白名单）。
 *
 * ⚠️ 同上：key 来自 DB，类型不可知，故用 `Record<string, string>`。
 */
export const RAG_PARSE_STATUS_LABELS: Record<string, string> = {
  PENDING: '待解析',
  PARSED: '已解析',
  CHUNKED: '已切分',
  FAILED: '解析失败',
}

/* ── RAG 人工抽检（审核前置，V1.0.2 §5 R2）────────────────── */

/**
 * 抽检错误类型（对齐后端 `InspectionRecord.ErrorType`）。
 *
 * ⚠️ `ANSWER_LEAK` 是<b>单点否决</b>：命中一条即整册不合格（D04 §6），
 * 因为「应为答案却判成讲解」等于把标准答案送进讲解模式上下文 → 泄题。
 */
export type RagInspectionErrorType =
  | 'ANSWER_LEAK'
  | 'EXERCISE_NO_LOST'
  | 'LOCATOR_WRONG'
  | 'CHUNK_BOUNDARY'
  | 'OTHER'

/** 错误类型可读标签（下拉与历史列表共用）。 */
export const RAG_INSPECTION_ERROR_TYPE_LABELS: Record<RagInspectionErrorType, string> = {
  ANSWER_LEAK: '答案区漏判（单点否决）',
  EXERCISE_NO_LOST: '题号丢失/错位',
  LOCATOR_WRONG: '页码或章节坐标错误',
  CHUNK_BOUNDARY: '切分边界错误',
  OTHER: '其他错误',
}

/** 抽检结论。 */
export type RagInspectionVerdict = 'PASS' | 'FAIL'

/** 单条抽检记录（请求项，对齐后端 `InspectionItemRequest`）。 */
export interface RagInspectionItem {
  chunkId: string
  /** 期望角色（人工判定的正确角色）；PASS 时与 actualRole 相同，FAIL 时由人工给出正确角色 */
  expectedRole: string
  /** 实际角色（系统产出） */
  actualRole: string
  /** 错误类型；verdict=FAIL 时必填，PASS 时不得带 */
  errorType: RagInspectionErrorType | null
  verdict: RagInspectionVerdict
}

/** 提交抽检请求（对齐后端 `SubmitInspectionRequest`）。 */
export interface RagInspectionSubmitRequest {
  /** 抽检人；缺省时后端用令牌主体 */
  inspectorId?: string | null
  items: RagInspectionItem[]
}

/** 抽检记录（响应项，对齐后端 `InspectionRecordResponse`）。 */
export interface RagInspectionRecord {
  recordId: string
  chunkId: string
  inspectorId: string
  expectedRole: string
  actualRole: string
  /** 错误类型；结论为 PASS 时为 null */
  errorType: RagInspectionErrorType | null
  verdict: RagInspectionVerdict
  createdAt: string
}

/** 抽检判定结果（对齐后端 `InspectionVerdictResponse`）。 */
export interface RagInspectionVerdictResult {
  passed: boolean
  total: number
  failed: number
  answerLeaks: number
  accuracy: number
  reason: string | null
}

/**
 * 抽检状态（对齐后端 `rag_source.inspection_status` 三值）。
 *
 * ⚠️ 仅 `PASSED` 允许签发 VERIFIED（见 `InspectionGuard`）；
 *    `NOT_REQUIRED`（默认态）与 `FAILED` 都不放行——缺抽检等于未通过。
 */
export type RagInspectionStatus = 'NOT_REQUIRED' | 'PASSED' | 'FAILED'

/** 抽检状态可读标签。 */
export const RAG_INSPECTION_STATUS_LABELS: Record<RagInspectionStatus, string> = {
  NOT_REQUIRED: '未抽检',
  PASSED: '抽检通过',
  FAILED: '抽检不通过',
}

/* ── RAG 字典（SOURCE_TYPE 可由平台前端维护，其余派生只读）──── */

/**
 * 字典类型。
 *
 * - `SOURCE_TYPE`：动态字典，可前端维护（增删改），落地 `rag_dictionary` 表；
 * - 其余三类为派生字典（由后端按 Java 枚举派生，只读），保证准入判定单一权威。
 */
export type RagDictionaryType = 'SOURCE_TYPE' | 'TRUST_TIER' | 'VERIFICATION_METHOD' | 'LICENSE_STATUS'

/**
 * 字典项作用域（对齐后端 `DictionaryPort.Scope`）。
 *
 * - `PLATFORM`：平台全局项（含派生枚举），对租户**只读**——
 *   后端 RLS 的写/删策略不放行 `tenant_id IS NULL`，
 *   所以编辑/删除按钮点了也只会拿到拒绝，UI 不该提供这两个入口；
 * - `TENANT`：本租户私有项，可维护；同 `code` 时**覆盖**同名的全局项。
 */
export type RagDictionaryScope = 'PLATFORM' | 'TENANT'

/** 字典项（动态 / 派生类型共用）。 */
export interface RagDictionaryEntry {
  type: RagDictionaryType
  code: string
  label: string
  sortOrder: number
  enabled: boolean
  scope: RagDictionaryScope
}

/** 字典类型视图（含是否可维护、该类型下的全部条目）。 */
export interface RagDictionaryTypeView {
  type: RagDictionaryType
  editable: boolean
  entries: RagDictionaryEntry[]
}

/** 字典项写入 / 更新请求（仅对可编辑类型生效）。 */
export interface RagUpsertDictionaryEntryRequest {
  code: string
  label: string
  sortOrder: number
  enabled: boolean
}

/* ── 每日报表（RAG 统计）──────────────────────────────── */

/** RAG 侧每日检索次数（对齐后端 `ManagementDtos.DailySearchResponse`）。 */
export interface RagDailySearchStatistics {
  searchesByDay: RagDailySearchCount[]
  generatedDays: number
}

/** 单日检索次数。 */
export interface RagDailySearchCount {
  date: string
  searches: number
}

/** RAG 侧每日导入语料量（对齐后端 `ManagementDtos.DailyImportResponse`）。 */
export interface RagDailyImportStatistics {
  importsByDay: RagDailyImportCount[]
  generatedDays: number
}

/** 单日导入语料量（三层级）。 */
export interface RagDailyImportCount {
  date: string
  /** 资料来源（册 / 作品）数 */
  sources: number
  /** 文档载体（物理文件）数 */
  documents: number
  /** 可检索片段数 */
  chunks: number
}

/* ── RAG 检索探针（维护 · 诚实边界 RAG-R01）──────────────
 *
 * ⚠️ 检索端点不强制 X-Tenant-Id 之外的鉴权；admin-console 以平台会话令牌
 *    + X-Tenant-Id 调用，用于验证「admissible 是否真的可检索」。
 *    探针结果若为 0 召回，应如实呈现「治理状态可管理 ≠ 检索路径已强制」。
 */

/** 检索模式（对齐后端 `RetrievalRequest.RetrievalMode`）。 */
export type RagRetrievalMode = 'EXPLAIN' | 'SOLVE' | 'VERIFY' | 'OVERVIEW'

/** 版本检索范围模式。 */
export type RagScopeMode = 'STRICT' | 'PREFERRED_WITH_FALLBACK' | 'CROSS_EDITION'

/** 教材版本坐标系（五轴展示名，全部必填）。 */
export interface RagEdition {
  subject: string
  grade: string
  term: string
  publisher: string
  revisionCode: string
}

/** 单个召回片段。 */
export interface RagRetrievalHit {
  chunkId: string
  text: string
  role: string
  score: number
  edition: string
  locator: string
  channel: string
  reason: string
}

/** 检索探针请求体。 */
export interface RagRetrievalRequest {
  query: string
  mode: RagRetrievalMode
  edition: RagEdition
  scopeMode?: RagScopeMode | null
  topK: number
}

/** 检索探针响应体。 */
export interface RagRetrievalResponse {
  hits: RagRetrievalHit[]
  versionMismatchDetected: boolean
  answerContentSuppressed: boolean
  primaryEdition: string
}

/** 检索模式可读标签。 */
export const RAG_RETRIEVAL_MODE_LABELS: Record<RagRetrievalMode, string> = {
  EXPLAIN: '讲解',
  SOLVE: '解题',
  VERIFY: '核对',
  OVERVIEW: '概览',
}

/** 版本范围模式可读标签。 */
export const RAG_SCOPE_MODE_LABELS: Record<RagScopeMode, string> = {
  STRICT: '严格锁定当前版本',
  PREFERRED_WITH_FALLBACK: '优先本版可回退同册',
  CROSS_EDITION: '跨版本',
}
