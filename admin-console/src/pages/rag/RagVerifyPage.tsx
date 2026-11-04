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
import { currentPrincipalId, LicenseTag, TrustTierTag } from './ragUi'
import { RagNotReady, RagScopeShell, type RagScopePageProps } from './RagScopeShell'
import { GovernanceBadge, RagEmpty } from './ragVisuals'

const { Text } = Typography
const { TextArea } = Input

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
      if (!sid) return
      setLoading(true)
      try {
        const s = await getRagSource(target, sid)
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
    if (sourceId && ready) void loadSource(sourceId)
  }, [sourceId, ready, loadSource])

  // 复核人候选（双人复核：执行人不得复核自己）。
  useEffect(() => {
    if (!sourceId || !ready) return
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const list = await listRagCoSigners(target, sourceId)
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

  const canSubmit = useMemo(() => {
    if (!source) return false
    if (targetStatus === 'VERIFIED') {
      // VERIFIED 需：证据 + 复核人 + 权威等级（否则通过校验却仍被权威门槛拦下）
      return evidence.trim().length > 0 && Boolean(coSignerId) && trustTier !== null
    }
    return evidence.trim().length > 0
  }, [source, targetStatus, evidence, coSignerId, trustTier])

  const onSubmit = async (): Promise<void> => {
    if (!source) return
    const payload: RagVerificationRequest = {
      targetStatus,
      // 双人复核的第二人；其余状态传 null（后端 SoD 会比对并拒绝同人）
      verifiedBy: targetStatus === 'VERIFIED' ? (coSignerId ?? null) : null,
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
            <Space wrap align="center">
              <Text>来源 ID</Text>
              <Input
                style={{ width: 380 }}
                placeholder="粘贴来源 UUID（或从 Dashboard / 列表页进入）"
                value={sourceId}
                onChange={(e) => setSourceId(e.target.value.trim())}
              />
              <Button onClick={() => void loadSource(sourceId)} disabled={!sourceId}>
                读取
              </Button>
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
                  <Timeline
                    items={[
                      {
                        color: 'gray',
                        children: `登记于 ${source.createdAt}`,
                      },
                      {
                        color: source.verifiedAt ? 'green' : 'gray',
                        children: source.verifiedAt
                          ? `校验通过于 ${source.verifiedAt}${
                              source.verifiedBy ? `（签署人 ${source.verifiedBy.slice(0, 8)}…）` : ''
                            }`
                          : '尚未校验通过',
                      },
                      {
                        color: 'gray',
                        children: `最近更新 ${source.updatedAt}`,
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
                          <Select
                            style={{ width: '100%', marginTop: 4 }}
                            value={coSignerId}
                            onChange={setCoSignerId}
                            placeholder="选择第二位签署人（不得与执行人相同）"
                            options={coSigners
                              .filter((c) => c.principalId !== executorId)
                              .map((c) => ({ value: c.principalId, label: c.label }))}
                          />
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
                              请填写证据{targetStatus === 'VERIFIED' ? '并选择复核人' : ''}
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
