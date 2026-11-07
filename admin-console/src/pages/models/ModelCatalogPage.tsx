import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  AutoComplete,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { App as AntApp } from 'antd'
import {
  createModel,
  deleteModel,
  deleteProviderCredential,
  listModels,
  listProviderCredentials,
  setProviderCredential,
  updateModel,
} from '@/api/modelCatalogApi'
import type {
  ModelConfig,
  ModelConfigRequest,
  ProviderCredential,
} from '@/api/modelCatalogApi'
import { formatTime } from '@/pages/monitoring/monitoringFormat'

const { Text } = Typography

/** 常见供应商（下拉候选，同时允许自由输入） */
const COMMON_PROVIDERS = [
  'minimax',
  'openai',
  'deepseek',
  'ollama',
  'workbuddy',
  'bge',
]

/** 场景候选（可自由扩展） */
const COMMON_SCENARIOS = ['agent-chat', 'console-chat', 'embedding', 'default']

/** 表单内的可编辑字段（比契约更宽松：允许空串 / undefined 以便校验与默认值） */
interface ModelFormValues {
  provider?: string
  scenario?: string
  modelName?: string
  displayName?: string
  baseUrl?: string
  isDefault?: boolean
  supportsTools?: boolean
  supportsStreaming?: boolean
  supportsVision?: boolean
  dimension?: number | null
  isEnabled?: boolean
  sortOrder?: number
  description?: string
}

/** 把 ModelConfig / 表单值收敛成后端契约要求的 ModelConfigRequest（null→空串） */
function toRequest(m: ModelConfig | ModelFormValues): ModelConfigRequest {
  return {
    provider: m.provider ?? '',
    scenario: m.scenario ?? '',
    modelName: m.modelName ?? '',
    displayName: m.displayName ?? '',
    baseUrl: m.baseUrl ?? '',
    isDefault: Boolean(m.isDefault),
    supportsTools: Boolean(m.supportsTools),
    supportsStreaming: Boolean(m.supportsStreaming),
    supportsVision: Boolean(m.supportsVision),
    dimension: m.dimension ?? null,
    isEnabled: m.isEnabled ?? true,
    sortOrder: Number(m.sortOrder ?? 0),
    description: m.description ?? '',
  }
}

const EMPTY_FORM: ModelFormValues = {
  provider: undefined,
  scenario: 'default',
  modelName: '',
  displayName: '',
  baseUrl: '',
  isDefault: false,
  supportsTools: false,
  supportsStreaming: false,
  supportsVision: false,
  dimension: null,
  isEnabled: true,
  sortOrder: 0,
  description: '',
}

/**
 * Feature A · 模型定义 CRUD 面板。
 *
 * <p>⚠️ 列表状态用本地 useState 维护（按 client 契约：plain fetch，无 react-query）。
 *    提交前校验 provider / scenario / modelName 三个必填项（antd Form rules + 兜底判断）。
 */
function ModelDefinitionsPane(): JSX.Element {
  const { message } = AntApp.useApp()
  const [models, setModels] = useState<ModelConfig[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editing, setEditing] = useState<ModelConfig | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<ModelFormValues>()

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    listModels()
      .then(setModels)
      .catch((e: unknown) => {
        setModels([])
        setError(e instanceof Error ? e.message : '模型配置加载失败')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const openCreate = (): void => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue(EMPTY_FORM)
    setDrawerOpen(true)
  }

  const openEdit = (m: ModelConfig): void => {
    setEditing(m)
    form.resetFields()
    form.setFieldsValue(toRequest(m))
    setDrawerOpen(true)
  }

  /** 表格里的「启用」开关：就地全量 PUT（其余字段原样回写） */
  const handleToggleEnabled = async (record: ModelConfig, checked: boolean): Promise<void> => {
    try {
      await updateModel(record.id, toRequest({ ...record, isEnabled: checked }))
      message.success(checked ? '已启用' : '已停用')
      load()
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '更新启用状态失败')
    }
  }

  const handleDelete = async (record: ModelConfig): Promise<void> => {
    try {
      await deleteModel(record.id)
      message.success('已删除')
      load()
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const onFinish = async (values: ModelFormValues): Promise<void> => {
    // 兜底校验（Form rules 已拦一遍，这里防止绕过）
    const provider = (values.provider ?? '').trim()
    const scenario = (values.scenario ?? '').trim()
    const modelName = (values.modelName ?? '').trim()
    if (!provider || !scenario || !modelName) {
      message.warning('供应商 / 场景 / 模型名称均为必填')
      return
    }
    setSubmitting(true)
    const payload = toRequest({ ...values, provider, scenario, modelName })
    try {
      if (editing) {
        await updateModel(editing.id, payload)
        message.success('模型已更新')
      } else {
        await createModel(payload)
        message.success('模型已创建')
      }
      setDrawerOpen(false)
      load()
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  const columns = [
    { title: '供应商', dataIndex: 'provider', width: 140 },
    { title: '场景', dataIndex: 'scenario', width: 140 },
    { title: '模型名称', dataIndex: 'modelName', width: 180 },
    { title: '显示名', dataIndex: 'displayName', width: 160, render: (v: string | null) => v ?? <Text type="secondary">—</Text> },
    {
      title: '默认',
      dataIndex: 'isDefault',
      width: 80,
      render: (v: boolean) => (v ? <Tag color="gold">默认</Tag> : <Text type="secondary">—</Text>),
    },
    {
      title: '启用',
      dataIndex: 'isEnabled',
      width: 90,
      render: (v: boolean, record: ModelConfig) => (
        <Switch checked={v} onChange={(checked) => handleToggleEnabled(record, checked)} />
      ),
    },
    {
      title: '维度',
      dataIndex: 'dimension',
      width: 90,
      render: (v: number | null) => (v == null ? <Text type="secondary">—</Text> : v),
    },
    { title: '排序', dataIndex: 'sortOrder', width: 80 },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_: unknown, record: ModelConfig) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEdit(record)}>
            编辑
          </Button>
          <Popconfirm
            title="删除该模型配置？"
            description="删除后不可恢复"
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => handleDelete(record)}
          >
            <Button type="link" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建模型
        </Button>
      </Space>

      {error && (
        <Alert type="error" showIcon message="模型配置加载失败" description={<Text>{error}</Text>} />
      )}

      <Table<ModelConfig>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={models}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        locale={{ emptyText: '尚未配置任何模型' }}
        columns={columns}
      />

      <Drawer
        title={editing ? `编辑模型 · ${editing.modelName}` : '新建模型'}
        width={480}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        destroyOnClose
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" loading={submitting} onClick={() => form.submit()}>
              保存
            </Button>
          </Space>
        }
      >
        <Form<ModelFormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          initialValues={EMPTY_FORM}
        >
          <Form.Item
            name="provider"
            label="供应商"
            rules={[{ required: true, message: '请选择或输入供应商' }]}
          >
            <AutoComplete
              placeholder="选择或输入供应商"
              options={COMMON_PROVIDERS.map((p) => ({ value: p }))}
              filterOption={(input, option) =>
                (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
              }
            />
          </Form.Item>

          <Form.Item
            name="scenario"
            label="场景"
            rules={[{ required: true, message: '请选择或输入场景' }]}
          >
            <Select
              placeholder="选择或输入场景"
              options={COMMON_SCENARIOS.map((s) => ({ value: s }))}
              showSearch
            />
          </Form.Item>

          <Form.Item
            name="modelName"
            label="模型名称"
            rules={[{ required: true, message: '请输入模型名称' }]}
          >
            <Input placeholder="如 gpt-4o / minimax-01 / bge-large-zh" />
          </Form.Item>

          <Form.Item name="displayName" label="显示名">
            <Input placeholder="界面展示用名称（可空）" />
          </Form.Item>

          <Form.Item name="baseUrl" label="Base URL">
            <Input placeholder="如 https://api.openai.com/v1（可空）" />
          </Form.Item>

          <Form.Item name="dimension" label="向量维度（embedding 模型）">
            <InputNumber style={{ width: '100%' }} min={1} placeholder="如 1536（非 embedding 留空）" />
          </Form.Item>

          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item name="isDefault" label="默认模型" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="isEnabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="supportsTools" label="支持工具调用" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="supportsStreaming" label="支持流式" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="supportsVision" label="支持视觉" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="可选说明" />
          </Form.Item>
        </Form>
      </Drawer>
    </Space>
  )
}

/**
 * Feature B · 供应商密钥面板。
 *
 * <p>⚠️ 后端仅回显脱敏后的 maskedKey，<b>绝不</b>返回明文；设置密钥走 PUT 仅上传明文，
 *    列表刷新后看到的仍是脱敏片段。删除走确认后 DELETE。
 */
function ProviderCredentialsPane(): JSX.Element {
  const { message } = AntApp.useApp()
  const [creds, setCreds] = useState<ProviderCredential[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<{ provider?: string; apiKey?: string }>()

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    listProviderCredentials()
      .then(setCreds)
      .catch((e: unknown) => {
        setCreds([])
        setError(e instanceof Error ? e.message : '密钥列表加载失败')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const openSet = (): void => {
    form.resetFields()
    setModalOpen(true)
  }

  const onFinish = async (values: { provider?: string; apiKey?: string }): Promise<void> => {
    const provider = (values.provider ?? '').trim()
    const apiKey = (values.apiKey ?? '').trim()
    if (!provider) {
      message.warning('请选择或输入供应商')
      return
    }
    if (!apiKey) {
      message.warning('请输入 API Key')
      return
    }
    setSubmitting(true)
    try {
      await setProviderCredential({ provider, apiKey })
      message.success('密钥已设置')
      setModalOpen(false)
      load()
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '设置失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (provider: string): Promise<void> => {
    try {
      await deleteProviderCredential(provider)
      message.success('密钥已删除')
      load()
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const columns = [
    { title: '供应商', dataIndex: 'provider', width: 200 },
    {
      title: '已配置密钥',
      dataIndex: 'maskedKey',
      render: (v: string | null) =>
        v ? <Text code>{v}</Text> : <Tag>未配置</Tag>,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 200,
      render: (v: string | null) => formatTime(v),
    },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_: unknown, record: ProviderCredential) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={openSet}>
            设置密钥
          </Button>
          <Popconfirm
            title={`删除 ${record.provider} 的密钥？`}
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => handleDelete(record.provider)}
          >
            <Button type="link" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="密钥仅脱敏回显"
        description={
          <Text>
            列表只展示脱敏后的密钥片段（如 <Text code>sk-…aBc9</Text>），后端绝不返回明文。
            设置密钥时仅上传明文，刷新后仍只见脱敏片段。
          </Text>
        }
      />
      {error && (
        <Alert type="error" showIcon message="密钥列表加载失败" description={<Text>{error}</Text>} />
      )}
      <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openSet}>
          设置密钥
        </Button>
      </Space>
      <Table<ProviderCredential>
        rowKey="provider"
        size="small"
        loading={loading}
        dataSource={creds}
        pagination={false}
        locale={{ emptyText: '尚未配置任何供应商密钥' }}
        columns={columns}
      />

      <Modal
        title="设置供应商密钥"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnClose
        okText="保存"
        cancelText="取消"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={onFinish}
        >
          <Form.Item
            name="provider"
            label="供应商"
            rules={[{ required: true, message: '请选择或输入供应商' }]}
          >
            <AutoComplete
              data-testid="cred-provider"
              placeholder="选择或输入供应商"
              options={COMMON_PROVIDERS.map((p) => ({ value: p }))}
              filterOption={(input, option) =>
                (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
              }
            />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label="API Key"
            rules={[{ required: true, message: '请输入 API Key' }]}
          >
            <Input.Password data-testid="cred-apikey" placeholder="明文仅上传，不回显" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

/** 模型配置（Model Catalog）管理页：模型定义 CRUD + 供应商密钥 */
export function ModelCatalogPage(): JSX.Element {
  return (
    <Card title="模型配置">
      <Tabs
        defaultActiveKey="models"
        items={[
          { key: 'models', label: '模型定义', children: <ModelDefinitionsPane /> },
          { key: 'credentials', label: '供应商密钥', children: <ProviderCredentialsPane /> },
        ]}
      />
    </Card>
  )
}
