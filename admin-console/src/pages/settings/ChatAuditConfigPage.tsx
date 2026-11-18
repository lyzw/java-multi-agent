import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Divider,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  AUDIT_ENDPOINTS,
  getChatAuditConfig,
  saveChatAuditConfig,
  type ChatAuditConfig,
  type ChatAuditConfigResponse,
} from '@/api/chatAuditConfigApi'

const { Text, Title } = Typography

/**
 * 对话审计配置页（系统配置 → 对话审计配置）。
 *
 * <p>平台级单例配置：控制"聊天相关所有请求"的端到端审计录制行为。
 * 保存后后端写入即生效，无需重启。前端整表提交，缺字段由后端补齐默认值。
 *
 * <p>⚠️ 强制脱敏 Header 黑名单（Authorization/Cookie/…）为后端安全不变量，
 * 本页不可关闭，故只在说明里提示，不提供开关。
 */
export function ChatAuditConfigPage(): JSX.Element {
  const [form] = Form.useForm<ChatAuditConfig>()
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [version, setVersion] = useState<number>(0)
  const [updated, setUpdated] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    getChatAuditConfig()
      .then((resp: ChatAuditConfigResponse) => {
        form.setFieldsValue(resp.config)
        setVersion(resp.version)
        setUpdated(resp.updatedAt)
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : '读取配置失败'))
      .finally(() => setLoading(false))
  }, [form])

  useEffect(() => { load() }, [load])

  const onSave = async () => {
    // ⚠️ 校验失败不是「保存失败」：表单会自己把红字标在字段上，
    //    再顶一条全局错误只会让人以为请求发出去了却没成功。
    const values = await form.validateFields().catch(() => null)
    if (!values) {
      return
    }
    try {
      setSaving(true)
      setError(null)
      const resp = await saveChatAuditConfig(values as ChatAuditConfig)
      form.setFieldsValue(resp.config)
      setVersion(resp.version)
      setUpdated(resp.updatedAt)
      message.success('已保存，下一个请求即生效')
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div style={{ maxWidth: 1040 }}>
      <Title level={4}>对话审计配置</Title>
      <Text type="secondary">
        对"聊天相关所有请求"做端到端完整记录（含原文与逐分片），用于效果评估。
        平台级单例，保存后下一个请求即生效，无需重启。版本 {version}
        {updated ? ` · 最近更新 ${updated}` : ''}。
      </Text>

      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 12 }}
        message="敏感数据红线"
        description={
          <Text>
            审计记录包含用户问题、模型回答与思考草稿原文，属最高敏感级别：强制 30 天保留、
            租户级隔离，仅平台效果评估角色可读。凭据类 Header（Authorization / Cookie / API Key）
            为后端强制脱敏，不可关闭。关闭采样、缩短保留期等调整请遵循合规要求。
          </Text>
        }
      />

      {error && (
        <Alert type="error" showIcon style={{ marginTop: 12 }} message="操作失败" description={error} />
      )}

      <Spin spinning={loading}>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          {/* 总开关 */}
          <Card size="small" title="总览">
            <Form.Item label="审计总开关" name="enabled" valuePropName="checked" extra="关闭后所有端点均不录制；配置仍保留。">
              <Switch checkedChildren="开启" unCheckedChildren="关闭" />
            </Form.Item>
            <Form.Item label="采样率" name={['sampling', 'rate']} extra="1.0 = 全量记录；0~1 之间按 traceId 确定性抽样。">
              <InputNumber min={0} max={1} step={0.05} style={{ width: 180 }} />
            </Form.Item>
          </Card>

          {/* 采集范围 */}
          <Card size="small" title="采集范围" style={{ marginTop: 12 }}>
            <Form.Item label="审计端点白名单" name={['scope', 'endpoints']} extra="仅这些端点的请求会被录制；端点外的请求不记录。">
              <Select
                mode="multiple"
                options={AUDIT_ENDPOINTS}
                placeholder="选择要审计的端点"
              />
            </Form.Item>
            <Space size={32}>
              <Form.Item label="记录建连前失败(4xx)" name={['scope', 'includePreconnectionFailures']} valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item
                label="记录路由模型调用"
                name={['scope', 'includeRoutingModelCalls']}
                valuePropName="checked"
                extra="V1 尚未单独区分「路由语义选择」的模型调用：送模型的 messages 恒按「最后一次调用」记录。本开关保留但不改变行为。"
              >
                <Switch disabled />
              </Form.Item>
            </Space>
          </Card>

          {/* 落盘双写 */}
          <Card size="small" title="落盘（双写）" style={{ marginTop: 12 }}>
            <Row gutter={24}>
              <Col span={12}>
                <Form.Item label="写入数据库" name={['sinks', 'database', 'enabled']} valuePropName="checked" extra="public.chat_audit_record（租户 RLS 隔离）">
                  <Switch />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item label="写入目录" name={['sinks', 'file', 'enabled']} valuePropName="checked" extra="按日期分目录，一请求一 JSON 文件">
                  <Switch />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item label="审计目录（服务端绝对路径）" name={['sinks', 'file', 'directory']} rules={[{ required: true, message: '请输入绝对路径' }]}>
              <Input placeholder="/data/agent/chat-audit" />
            </Form.Item>
            <Space size={32}>
              <Form.Item label="分片粒度" name={['sinks', 'file', 'partition']}>
                <Select style={{ width: 140 }} options={[{ value: 'day', label: '按天' }, { value: 'hour', label: '按小时' }]} />
              </Form.Item>
              <Form.Item label="强制落盘(fsync)" name={['sinks', 'file', 'fsync']} valuePropName="checked">
                <Switch />
              </Form.Item>
            </Space>
          </Card>

          {/* 采集内容 */}
          <Card size="small" title="采集内容" style={{ marginTop: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '4px 24px' }}>
              <Form.Item label="请求原文/历史" name={['capture', 'rawRequestBody']} valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label="完整回答" name={['capture', 'rawAnswer']} valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label="思考草稿" name={['capture', 'thinking']} valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label="逐分片(delta/thinking)" name={['capture', 'deltaChunks']} valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label="送模型 messages" name={['capture', 'modelRequestMessages']} valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label="RAG 命中原文" name={['capture', 'ragHits']} valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label="工具入参与结果" name={['capture', 'toolArgsAndResults']} valuePropName="checked"><Switch /></Form.Item>
              <Form.Item label="额外记录 Header" name={['capture', 'headers']}>
                <Select mode="tags" placeholder="如 x-request-id" tokenSeparators={[',']} />
              </Form.Item>
            </div>
            <Text type="secondary">凭据类 Header（Authorization / Cookie / X-Api-Key 等）为强制脱敏，无论此处如何配置都不落原文。</Text>
          </Card>

          {/* 脱敏与写入 */}
          <Card size="small" title="脱敏 · 写入 · 保留" style={{ marginTop: 12 }}>
            <Row gutter={24}>
              <Col span={12}>
                <Form.Item label="脱敏掩码" name={['redaction', 'mask']}>
                  <Input placeholder="***" />
                </Form.Item>
                <Form.Item label="敏感字段名" name={['redaction', 'sensitiveFields']}>
                  <Select mode="tags" tokenSeparators={[',']} placeholder="如 password、phone" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item label="写入策略" name={['writer', 'onSinkError']} extra="审计写入失败绝不中断业务请求（安全不变量）">
                  <Select options={[{ value: 'IGNORE', label: '忽略(IGNORE)' }, { value: 'LOG_ONLY', label: '仅告警(LOG_ONLY)' }]} />
                </Form.Item>
                <Form.Item label="异步写入" name={['writer', 'async']} valuePropName="checked">
                  <Switch />
                </Form.Item>
              </Col>
            </Row>
            <Divider style={{ margin: '4px 0 12px' }} />
            <Space size={16} wrap>
              <Form.Item label="队列容量" name={['writer', 'queueCapacity']}><InputNumber min={1} max={100000} /></Form.Item>
              <Form.Item label="单请求分片上限" name={['writer', 'maxChunksPerRequest']}><InputNumber min={1} max={1000000} /></Form.Item>
              <Form.Item label="保留天数" name={['retention', 'days']} extra="PIPL 留存，默认 30 天"><InputNumber min={1} max={180} /></Form.Item>
              <Form.Item label="请求体上限(字节)" name={['limits', 'maxBodyBytes']}><InputNumber min={1} /></Form.Item>
              <Form.Item label="回答上限(字符)" name={['limits', 'maxAnswerChars']}><InputNumber min={1} /></Form.Item>
            </Space>
          </Card>

          <Space style={{ marginTop: 16 }}>
            <Button type="primary" onClick={onSave} loading={saving}>保存配置</Button>
            <Button onClick={load} disabled={loading || saving}>重新加载</Button>
            <Tag color="blue">写入即生效</Tag>
          </Space>
        </Form>
      </Spin>
    </div>
  )
}
