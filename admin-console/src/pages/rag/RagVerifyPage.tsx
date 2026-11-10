/**
 * RAG 语料管理 · 校验决定（RAG-D13 §3.3.1 / RAG-D14 §4.6）
 *
 * <p><b>这一页做什么</b>：审核员对来源/载体做 `VERIFIED` / `REVOKED` 决定，
 * 并维护权威等级与有效期。
 *
 * <p><b>⚠️ 三条不可妥协的纪律</b>（保持改造前后端与页面的约定）：
 * <ol>
 *   <li><b>VERIFIED 须填校验证据</b>、REVOKED 须填撤回原因——
 *       审查动作不能"无痕一句话"，否则审计流形同虚设；</li>
 *   <li><b>双人复核</b>：通过校验须由两个人签署（执行人 ≠ 复核人）；</li>
 *   <li><b>抽检前置</b>：签发 VERIFIED 前该来源须已通过人工抽检
 *       （后端 `rag_source.inspection_status` 是前置守卫）。</li>
 * </ol>
 *
 * <p><b>视觉稿的"审计流"</b>：本页底部展示该来源的最近动作时间线——
 * 改造前这些信息散在抽屉里，现在集中呈现，让"谁在什么时候做了什么"可一眼核对。
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Timeline,
  Typography,
} from 'antd'
import { useSearchParams } from 'react-router-dom'

import { ApiError, getRagSource, listRagCoSigners } from '@/api/ragClient'
import type {
  RagSource,
  RagTrustTier,
  RagVerificationMethod,
  RagVerificationRequest,
  RagVerificationStatus,
} from '@/api/ragTypes'
import { RAG_VERIFICATION_LABELS } from '@/api/ragTypes'
import { useRagManagement } from '@/features/rag/useRagManagement'
import { currentPrincipalId, LicenseTag, TrustTierTag, timeText } from './ragUi'

/**
 * 时间线用的人类可读时刻（空值 → 破折号）。
 *
 * ⚠️ 复用 `timeText` 而不是再写一份 `toLocaleString`：同一页里
 *    时间格式不一致，用户会以为那是两个系统的数据。
 */
function formatTime(iso: string | null | undefined): string {
  return timeText(iso) || '—'
}
import { RagNotReady, RagScopeShell, type RagScopePageProps } from './RagScopeShell'
import { GovernanceBadge, RagEmpty } from './ragVisuals'

const { Text } = Typography
const { TextArea } = Input

/**
 * ⚠️ 来源 ID 必须是完整 UUID 才能触发读取。
 *
 * 此前 `useEffect([sourceId])` 对每一次键入都直接请求 `getRagSource`：
 * 用户粘贴 36 位 UUID 会产生 30+ 次 partial UUID 请求，后端 `?::uuid`
 * 转换失败 → 连续 HTTP 500 提示刷屏，且最后一次成功前页面已被错误态淹没。
 * 这里与「片段校对」页统一走完整 UUID 正则 + 300ms 防抖。
 */
const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
const SOURCE_LOAD_DEBOUNCE_MS = 300

function isUuid(value: string | null | undefined): value is string {
  return Boolean(value && UUID_PATTERN.test(value.trim()))
}

export function RagVerifyPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="审核"
      title="校验决定"
      description="对来源做 VERIFIED / REVOKED 决定。VERIFIED 须填校验证据并指定复核人（双人复核）；REVOKED 须填撤回原因。撤回是终态，不可复活。"
    >
      {(scope) => <RagVerifyBody scope={scope} />}
    </RagScopeShell>
  )
}

/** 页面主体：处在 {@link RagScopeShell} 的 provider 之内，故可直接消费作用域。 */
function RagVerifyBody({ scope }: RagScopePageProps): JSX.Element {
  const { message } = App.useApp()
  const { target, ready, scopeKey } = scope
  const [searchParams] = useSearchParams()

  const [sourceId, setSourceId] = useState<string>(searchParams.get('sourceId') ?? '')
  const [source, setSource] = useState<RagSource | null>(null)
  const [loading, setLoading] = useState(false)
  const [targetStatus, setTargetStatus] = useState<RagVerificationStatus>('VERIFIED')
  const [evidence, setEvidence] = useState('')
  const [validDays, setValidDays] = useState<number>(365)
  const [trustTier, setTrustTier] = useState<RagTrustTier | null>(null)
  const [method, setMethod] = useState<RagVerificationMethod>('MANUAL_REVIEW')
  const [coSignerId, setCoSignerId] = useState<string | undefined>(undefined)
  const [coSigners, setCoSigners] = useState<{ principalId: string; label?: string }[]>([])

  const { action, verify, resetAction } = useRagManagement()

  // 深链进来的 sourceId 变化（如从 Dashboard 点"处理"）。
  useEffect(() => {
    const fromQuery = searchParams.get('sourceId')
    if (fromQuery) setSourceId(fromQuery)
  }, [searchParams])

  useEffect(() => {
    setSource(null)
    setSourceId('')
    resetAction()
  }, [scopeKey, resetAction])

  const loadSource = useCallback(
    async (sid: string) => {
      // ⚠️ 守卫必须在发请求之前：partial UUID 打到后端 `?::uuid` 是 500，
      //    不是「来源不存在」。缺字符时只提示用户，不制造服务端错误。
      if (!isUuid(sid)) return
      setLoading(true)
      try {
        const s = await getRagSource(target, sid.trim())
        setSource(s)
      } catch (cause) {
        message.error(cause instanceof ApiError ? cause.message : '读取来源失败')
        setSource(null)
      } finally {
        setLoading(false)
      }
    },
    [target, message],
  )

  useEffect(() => {
    if (!ready || !isUuid(sourceId)) return
    const timer = setTimeout(() => void loadSource(sourceId), SOURCE_LOAD_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [sourceId, ready, loadSource])

  // 复核人候选（双人复核：执行人不得复核自己）。
  useEffect(() => {
    if (!ready || !isUuid(sourceId)) return
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const list = await listRagCoSigners(target, sourceId.trim())
        if (cancelled) return
        setCoSigners(
          list.map((c) => ({
            principalId: c.principalId,
            label: c.displayName ?? c.principalId.slice(0, 8),
          })),
        )
      } catch {
        // 复核人列表失败不阻断：允许手填 UUID。
        if (!cancelled) setCoSigners([])
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [sourceId, ready, target])

  const executorId = currentPrincipalId()
  /**
   * 复核人必须先通过本地形状/自审校验。
   *
   * ⚠️ 后端 `verified_by` 是 UUID 列：手输少一位会让 409 变成 500；
   *     「自己复核自己」则会被 SoD 明确拒绝。两者都在提交前拦住，
   *     而不是把服务端错误堆栈暴露给审核员。
   */
  const coSignerIsValid = useMemo(
    () =>
      isUuid(coSignerId) &&
      coSignerId.trim().toLowerCase() !== (executorId ?? '').toLowerCase(),
    [coSignerId, executorId],
  )

  const canSubmit = useMemo(() => {
    if (!source) return false
    if (targetStatus === 'VERIFIED') {
      // VERIFIED 需：证据 + 合法复核人 + 权威等级（否则通过校验却仍被权威门槛拦下）
      return evidence.trim().length > 0 && coSignerIsValid && trustTier !== null
    }
    return evidence.trim().length > 0
  }, [source, targetStatus, evidence, coSignerIsValid, trustTier])

  const onSubmit = async (): Promise<void> => {
    if (!source) return
    const payload: RagVerificationRequest = {
      targetStatus,
      // 双人复核的第二人；其余状态传 null（后端 SoD 会比对并拒绝同人）
      verifiedBy: targetStatus === 'VERIFIED' ? coSignerId?.trim() ?? null : null,
      evidenceRef: evidence.trim(),
      decidedBy: executorId || source.sourceId,
      verificationMethod: method,
      // VERIFIED 必填：否则来源通过校验却仍被权威门槛拦下
      trustTier: targetStatus === 'VERIFIED' ? trustTier : null,
      validDays,
    }
    await verify(target, source.sourceId, payload)
  }

  useEffect(() => {
    if (action.status === 'success') {
      message.success('校验状态已更新')
      if (action.updated) setSource(action.updated)
      setEvidence('')
      setCoSignerId(undefined)
    }
  }, [action.status, action.updated, message])

  return (
    <>
      {!ready ? (
        <RagNotReady />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card size="small">
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Space wrap align="center">
                <Text>来源 ID</Text>
                <Input
                  style={{ width: 380 }}
                  placeholder="粘贴来源 UUID（或从 Dashboard / 列表页进入）"
                  value={sourceId}
                  onChange={(e) => setSourceId(e.target.value.trim())}
                />
                <Button
                  onClick={() => void loadSource(sourceId)}
                  disabled={!isUuid(sourceId)}
                  title={isUuid(sourceId) ? undefined : '请输入完整 36 位 UUID'}
                >
                  读取
                </Button>
              </Space>
              {sourceId && !isUuid(sourceId) ? (
                <Text type="warning" style={{ fontSize: 12 }}>
                  输入完整 UUID 后自动读取；缺字符不会被发给后端（避免 ?::uuid 转换 500）
                </Text>
              ) : null}
            </Space>
          </Card>

          {loading ? (
            <Card size="small">
              <Spin />
            </Card>
          ) : source ? (
            <Row gutter={16}>
              <Col span={14}>
                <Card size="small" title="来源详情">
                  <Descriptions column={1} size="small" bordered>
                    <Descriptions.Item label="来源名称">{source.title}</Descriptions.Item>
                    <Descriptions.Item label="出版社">{source.publisher}</Descriptions.Item>
                    <Descriptions.Item label="权威等级">
                      <TrustTierTag tier={source.trustTier} />
                    </Descriptions.Item>
                    <Descriptions.Item label="版权状态">
                      <LicenseTag status={source.licenseStatus} />
                    </Descriptions.Item>
                    <Descriptions.Item label="校验状态">
                      <GovernanceBadge status={source.verificationStatus} />{' '}
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {RAG_VERIFICATION_LABELS[source.verificationStatus]}
                      </Text>
                    </Descriptions.Item>
                    <Descriptions.Item label="抽检状态">
                      {source.inspectionStatus ? (
                        <Tag
                          color={
                            source.inspectionStatus === 'PASSED'
                              ? 'green'
                              : source.inspectionStatus === 'FAILED'
                                ? 'red'
                                : 'default'
                          }
                        >
                          {source.inspectionStatus}
                        </Tag>
                      ) : (
                        <Text type="secondary">本次响应未读取</Text>
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="载体数">
                      {source.documentCount ?? '—'}
                    </Descriptions.Item>
                    <Descriptions.Item label="有效期">
                      {source.validDays} 天{source.stale ? '（已过期）' : ''}
                    </Descriptions.Item>
                  </Descriptions>
                </Card>

                <Card size="small" title="审计时间线" style={{ marginTop: 12 }}>
                  {/*
                    ⚠️ 时间线按**事件发生顺序**排，并把两个此前完全缺失的事件补上：
                      「元数据确认」与「审核认领/判定」。只画「登记 → 校验通过」
                      会把审核期整段涂空——审核员看不出这条已经卡了多久，
                      而这正是审计时间线存在的理由。
                    ⚠️ 未发生的事件画成灰色并写明「尚未…」，**不省略**：
                      省略会让人以为系统没有这条记录，而不是这件事还没发生。
                  */}
                  <Timeline
                    items={[
                      {
                        color: 'gray',
                        children: `录入（登记）于 ${formatTime(source.createdAt)}`,
                      },
                      {
                        color: source.metadataConfirmedAt ? 'blue' : 'gray',
                        children:
                          source.metadataConfirmedAt === undefined
                            ? '元数据确认：本次响应未返回'
                            : source.metadataConfirmedAt === null
                              ? '元数据尚未确认（推断值待人工核对）'
                              : `元数据确认于 ${formatTime(source.metadataConfirmedAt)}`,
                      },
                      {
                        color: source.reviewStartedAt ? 'blue' : 'gray',
                        children:
                          source.reviewStartedAt === undefined
                            ? '审核认领：本次响应未返回'
                            : source.reviewStartedAt === null
                              ? '尚未进入审核'
                              : `进入审核于 ${formatTime(source.reviewStartedAt)}`,
                      },
                      {
                        color: source.decidedAt ? 'green' : 'gray',
                        children:
                          source.decidedAt === undefined
                            ? '审核决定：本次响应未返回'
                            : source.decidedAt === null
                              ? '尚无审核结论'
                              : `审核判定于 ${formatTime(source.decidedAt)}${
                                  source.verifiedBy ? `（签署人 ${source.verifiedBy.slice(0, 8)}…）` : ''
                                }`,
                      },
                      {
                        color: source.verifiedAt ? 'green' : 'gray',
                        children: source.verifiedAt
                          ? `有效期自 ${formatTime(source.verifiedAt)} 起算`
                          : '尚未校验通过（无有效期）',
                      },
                      {
                        color: 'gray',
                        children: `最近更新 ${formatTime(source.updatedAt)}`,
                      },
                    ]}
                  />
                </Card>
              </Col>

              <Col span={10}>
                <Card size="small" title="校验决定">
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    <div>
                      <Text type="secondary">目标状态</Text>
                      <Select<RagVerificationStatus>
                        style={{ width: '100%', marginTop: 4 }}
                        value={targetStatus}
                        onChange={setTargetStatus}
                        options={[
                          { value: 'VERIFIED', label: '✓ VERIFIED · 通过校验' },
                          { value: 'REVOKED', label: '✕ REVOKED · 撤回' },
                        ]}
                      />
                    </div>

                    <div>
                      <Text type="secondary">
                        {targetStatus === 'VERIFIED' ? '校验证据（必填）' : '撤回原因（必填）'}
                      </Text>
                      <TextArea
                        style={{ marginTop: 4 }}
                        rows={3}
                        value={evidence}
                        onChange={(e) => setEvidence(e.target.value)}
                        placeholder={
                          targetStatus === 'VERIFIED'
                            ? '例如：已对照出版社授权电子版，ISBN / 版权页与授权凭证一致'
                            : '例如：版权到期 / 扫描件质量不合格 / 检出盗版内容'
                        }
                      />
                    </div>

                    {targetStatus === 'VERIFIED' && (
                      <>
                        <div>
                          <Text type="secondary">复核人（双人复核，必填）</Text>
                          {coSigners.length > 0 ? (
                            <Select
                              style={{ width: '100%', marginTop: 4 }}
                              value={coSignerId}
                              onChange={setCoSignerId}
                              allowClear
                              showSearch
                              optionFilterProp="label"
                              placeholder="选择第二位签署人（不得与执行人相同）"
                              options={coSigners
                                .filter(
                                  (c) =>
                                    c.principalId.toLowerCase() !==
                                    (executorId ?? '').toLowerCase(),
                                )
                                .map((c) => ({ value: c.principalId, label: c.label }))}
                            />
                          ) : (
                            <Input
                              style={{ marginTop: 4 }}
                              value={coSignerId ?? ''}
                              onChange={(e) => setCoSignerId(e.target.value.trim() || undefined)}
                              allowClear
                              placeholder="第二位复核人的主体 UUID（未配置审核员名录时手输）"
                            />
                          )}
                          {coSignerId && !coSignerIsValid ? (
                            <Text type="warning" style={{ fontSize: 12 }}>
                              复核人须为完整 UUID，且不能与决定人相同
                            </Text>
                          ) : null}
                        </div>
                        <div>
                          <Text type="secondary">校验后权威等级（必填）</Text>
                          <Select<RagTrustTier>
                            style={{ width: '100%', marginTop: 4 }}
                            value={trustTier ?? undefined}
                            onChange={setTrustTier}
                            placeholder="通过校验后授予的权威等级"
                            options={[
                              { value: 'OFFICIAL_PUBLISHER', label: 'OFFICIAL_PUBLISHER · 官方出版社' },
                              { value: 'OFFICIAL_TUTORIAL', label: 'OFFICIAL_TUTORIAL · 官方教辅' },
                              { value: 'PEER_REVIEWED_PAPER', label: 'PEER_REVIEWED_PAPER' },
                              { value: 'PREPRINT', label: 'PREPRINT' },
                            ]}
                          />
                        </div>
                        <div>
                          <Text type="secondary">校验手段</Text>
                          <Select<RagVerificationMethod>
                            style={{ width: '100%', marginTop: 4 }}
                            value={method}
                            onChange={setMethod}
                            options={[
                              { value: 'MANUAL_REVIEW', label: 'MANUAL_REVIEW · 人工复核' },
                              { value: 'ISBN', label: 'ISBN' },
                              { value: 'DOI', label: 'DOI' },
                              { value: 'OFFICIAL_HASH', label: 'OFFICIAL_HASH' },
                            ]}
                          />
                        </div>
                        <div>
                          <Text type="secondary">有效期（天）</Text>
                          <InputNumber
                            min={1}
                            max={3650}
                            value={validDays}
                            onChange={(v) => setValidDays(v ?? 365)}
                            style={{ width: '100%', marginTop: 4 }}
                          />
                        </div>
                      </>
                    )}

                    {targetStatus === 'REVOKED' && (
                      <Alert
                        type="error"
                        showIcon
                        message="撤回是终态"
                        description="撤回后该来源立即不可检索、不可引用；历史审计保留，但不可复活。"
                      />
                    )}

                    {action.status === 'error' && (
                      <Alert type="error" showIcon message="校验失败" description={action.message} />
                    )}

                    <Space>
                      <Button
                        type="primary"
                        danger={targetStatus === 'REVOKED'}
                        loading={action.status === 'submitting'}
                        disabled={!canSubmit}
                        onClick={() => void onSubmit()}
                      >
                        {targetStatus === 'VERIFIED' ? '判定 VERIFIED' : '判定 REVOKED'}
                      </Button>
                      {!canSubmit && (
                        <Text type="secondary" style={{ fontSize: 12 }}>
                              请填写证据
                              {targetStatus === 'VERIFIED'
                                ? '，选择合法复核人与权威等级'
                                : ''}
                        </Text>
                      )}
                    </Space>
                  </Space>
                </Card>
              </Col>
            </Row>
          ) : (
            <Card size="small">
              <RagEmpty text="请填写来源 ID 并读取，或从 Dashboard / 来源列表进入" />
            </Card>
          )}
        </Space>
      )}
    </>
  )
}
