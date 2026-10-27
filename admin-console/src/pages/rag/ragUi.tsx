/**
 * RAG 管理页面共享 UI 工具（颜色 / 标签 / 当前主体 / 校验表单）。
 *
 * ⚠️ 所有颜色与标签集中在此，避免散落各处导致治理语义漂移。
 */

import { Tag } from 'antd'
import { loadSession } from '@/auth/tokenStorage'
import type {
  RagVerificationStatus,
  RagTrustTier,
  RagLicenseStatus,
  RagVerificationMethod,
} from '@/api/ragTypes'
import {
  RAG_VERIFICATION_LABELS,
  RAG_TRUST_TIER_LABELS,
  RAG_LICENSE_LABELS,
} from '@/api/ragTypes'

/** 校验状态配色（与后端状态机语义一致）。 */
export const RAG_VERIFICATION_COLORS: Record<RagVerificationStatus, string> = {
  PENDING: 'default',
  IN_REVIEW: 'blue',
  VERIFIED: 'green',
  REJECTED: 'volcano',
  REVOKED: 'red',
}

/** 权威等级配色。 */
export const RAG_TRUST_TIER_COLORS: Record<RagTrustTier, string> = {
  OFFICIAL_PUBLISHER: 'green',
  OFFICIAL_TUTORIAL: 'cyan',
  PEER_REVIEWED_PAPER: 'blue',
  PREPRINT: 'gold',
  UNVERIFIED: 'default',
}

/** 版权状态配色。 */
export const RAG_LICENSE_COLORS: Record<RagLicenseStatus, string> = {
  LICENSED: 'green',
  FAIR_USE_QUOTATION: 'blue',
  PENDING: 'gold',
  RESTRICTED: 'red',
}

/** 校验手段配色（仅作轻区分）。 */
export const RAG_METHOD_COLORS: Record<RagVerificationMethod, string> = {
  NONE: 'default',
  ISBN: 'blue',
  DOI: 'blue',
  MANUAL_REVIEW: 'purple',
  OFFICIAL_HASH: 'geekblue',
}

/** 渲染校验状态标签。 */
export function VerificationTag({ status }: { status: RagVerificationStatus }): JSX.Element {
  return <Tag color={RAG_VERIFICATION_COLORS[status]}>{RAG_VERIFICATION_LABELS[status]}</Tag>
}

/** 渲染权威等级标签。 */
export function TrustTierTag({ tier }: { tier: RagTrustTier }): JSX.Element {
  return <Tag color={RAG_TRUST_TIER_COLORS[tier]}>{RAG_TRUST_TIER_LABELS[tier]}</Tag>
}

/** 渲染版权状态标签。 */
export function LicenseTag({ status }: { status: RagLicenseStatus }): JSX.Element {
  return <Tag color={RAG_LICENSE_COLORS[status]}>{RAG_LICENSE_LABELS[status]}</Tag>
}

/**
 * 合法流转目标的动词标签（用于按钮文案）。
 *
 * ⚠️ 仅覆盖 `allowedVerificationTargets` 可能返回的目标，
 *    与后端状态机白名单逐条对齐（RAG-D07 §2.4 T-1～T-12）。
 */
export const VERIFICATION_TARGET_VERB: Record<RagVerificationStatus, string> = {
  IN_REVIEW: '提交审核',
  VERIFIED: '通过校验',
  REJECTED: '拒绝',
  REVOKED: '撤回',
  PENDING: '退回待校验',
}

/** 当前登录平台主体的 UUID（校验留痕用）。缺失回落空串，由表单校验拦截。 */
export function currentPrincipalId(): string {
  return loadSession()?.principalId ?? ''
}
