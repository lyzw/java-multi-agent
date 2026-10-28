/**
 * 字典配置页：把前端下拉枚举收敛到数据库维护。
 *
 * ⚠️ 治理纪律（与后端 PlatformDictionaryController 逐条对齐）：
 *    · **内置字典（built_in）禁止删除、禁止改取值编码**——后端按 Java 枚举校验的
 *      正是 item_code，改了会让已落库的历史行取值悬空。中文标签 / 排序 / 停用可以改。
 *    · **删除 = 停用**，后端不物理删行；页面文案也必须说「停用」，
 *      写成「删除」会让人误以为数据没了。
 *    · **诚实边界**：对内置字典新增一项，若后端枚举未同步，写入业务表时仍会被
 *      后端拒绝。字典页只负责字典，不替后端背书——这条写在页面上，不藏在文档里。
 */

import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  createDictionary,
  disableDictionaryItem,
  updateDictionary,
  upsertDictionaryItem,
  updateDictionaryItem,
  type DictionaryItem,
  type DictionaryView,
} from '@/api/dictionaryApi'
import { ApiError } from '@/api/client'
import { useDictionaries } from '@/features/dictionaries/useDictionaries'

const { Text, Paragraph } = Typography

export function DictionaryAdminPage(): JSX.Element {
  const { list, loading, error, usingFallback, reload } = useDictionaries()
  const [selectedCode, setSelectedCode] = useState<string | null>(null)
  const [dictModalOpen, setDictModalOpen] = useState(false)
  const [itemModalOpen, setItemModalOpen] = useState(false)
  const [editingItem, setEditingItem] = useState<DictionaryItem | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    const first = list[0]
    if (selectedCode === null && first) {
      setSelectedCode(first.dictCode)
    }
  }, [list, selectedCode])

  const current = useMemo(
    () => list.find((d) => d.dictCode === selectedCode) ?? null,
    [list, selectedCode],
  )

  const [dictForm] = Form.useForm()
  const [itemForm] = Form.useForm()

  const dictColumns: ColumnsType<DictionaryView> = [
    {
      title: '字典',
      dataIndex: 'dictName',
      render: (_: unknown, row) => (
        <Space direction="vertical" size={0}>
          <Text strong>{row.dictName}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {row.dictCode}
          </Text>
        </Space>
      ),
    },
    {
      title: '类型',
      key: 'builtIn',
      width: 90,
      render: (_: unknown, row) =>
        row.builtIn ? <Tag color="blue">内置</Tag> : <Tag>业务</Tag>,
    },
    {
      title: '项数',
      key: 'count',
      width: 70,
      render: (_: unknown, row) => row.items.length,
    },
    {
      title: '状态',
      key: 'enabled',
      width: 80,
      render: (_: unknown, row) =>
        row.enabled ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
    },
  ]

  const itemColumns: ColumnsType<DictionaryItem> = [
    {
      title: '取值编码（写库值）',
      dataIndex: 'itemCode',
      width: 220,
      render: (code: string, row) => (
        <Space>
          <Text code copyable={!row.builtIn}>{code}</Text>
          {row.builtIn && <Tag color="blue">不可改</Tag>}
        </Space>
      ),
    },
    { title: '展示标签', dataIndex: 'itemLabel' },
    { title: '排序', dataIndex: 'sortOrder', width: 80 },
    {
      title: '状态',
      key: 'enabled',
      width: 90,
      render: (_: unknown, row) =>
        row.enabled ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
    },
    {
      title: '操作',
      key: 'ops',
      width: 140,
      render: (_: unknown, row) => (
        <Space>
          <Button
            size="small"
            onClick={() => {
              setEditingItem(row)
              itemForm.setFieldsValue({
                itemCode: row.itemCode,
                itemLabel: row.itemLabel,
                sortOrder: row.sortOrder,
                enabled: row.enabled,
              })
              setItemModalOpen(true)
            }}
          >
            编辑
          </Button>
          {row.enabled && (
            <Button size="small" danger onClick={() => void disableItem(row)}>
              停用
            </Button>
          )}
        </Space>
      ),
    },
  ]

  const disableItem = async (item: DictionaryItem): Promise<void> => {
    try {
      await disableDictionaryItem(item.dictCode, item.itemCode)
      message.success(`已停用「${item.itemLabel}」（未物理删除）`)
      reload()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '停用失败')
    }
  }

  const submitDictionary = async (values: {
    dictCode: string
    dictName: string
    description?: string
    sortOrder?: number
  }): Promise<void> => {
    setSubmitting(true)
    try {
      await createDictionary({
        dictCode: values.dictCode.trim(),
        dictName: values.dictName.trim(),
        description: values.description ?? null,
        sortOrder: values.sortOrder ?? 100,
      })
      message.success('字典已创建')
      setDictModalOpen(false)
      dictForm.resetFields()
      setSelectedCode(values.dictCode.trim())
      reload()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '创建失败')
    } finally {
      setSubmitting(false)
    }
  }

  const submitItem = async (values: {
    itemCode: string
    itemLabel: string
    sortOrder?: number
    enabled?: boolean
  }): Promise<void> => {
    if (!current) return
    setSubmitting(true)
    try {
      if (editingItem) {
        await updateDictionaryItem(current.dictCode, editingItem.itemCode, {
          itemCode: values.itemCode.trim(),
          itemLabel: values.itemLabel.trim(),
          sortOrder: values.sortOrder ?? 100,
          enabled: values.enabled ?? true,
        })
        message.success('已更新')
      } else {
        await upsertDictionaryItem(current.dictCode, {
          itemCode: values.itemCode.trim(),
          itemLabel: values.itemLabel.trim(),
          sortOrder: values.sortOrder ?? 100,
          enabled: values.enabled ?? true,
        })
        message.success('已新增')
      }
      setItemModalOpen(false)
      setEditingItem(null)
      itemForm.resetFields()
      reload()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        message.error('这一步不被允许：内置字典项的取值编码不可修改（可改标签或停用该项）')
      } else {
        message.error(cause instanceof ApiError ? cause.message : '保存失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  const toggleDictEnabled = async (enabled: boolean): Promise<void> => {
    if (!current) return
    try {
      await updateDictionary(current.dictCode, {
        dictName: current.dictName,
        description: current.description,
        enabled,
        sortOrder: current.sortOrder,
      })
      message.success(enabled ? '字典已启用' : '字典已停用')
      reload()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '操作失败')
    }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <div>
        <Typography.Title level={4} style={{ marginBottom: 4 }}>
          字典配置
        </Typography.Title>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          前端下拉枚举的取值来源。改中文标签、调排序、停用旧取值都在这里完成，
          <b>不必改前端代码与重新发版</b>。
        </Paragraph>
      </div>

      <Alert
        type="warning"
        showIcon
        message="内置字典的两条硬约束"
        description={
          <>
            1）内置字典（标记「内置」）与后端代码枚举强绑定，<b>禁止删除、禁止改取值编码</b>
            ——后端校验写库值的正是它，改了会让已落库的历史行取值悬空；中文标签、排序、停用可以改。
            <br />
            2）给内置字典<b>新增</b>一项后，若后端枚举未同步，写入业务表时仍会被后端拒绝。
            字典页只负责字典，不替后端背书。
          </>
        }
      />

      {usingFallback && (
        <Alert
          type="error"
          showIcon
          message="字典服务不可达，当前下拉走静态兜底"
          description="表单仍可用，但兜底值可能与数据库不一致。请修复后刷新——此刻在字典页做的改动不会反映到表单上。"
          action={
            <Button size="small" onClick={reload}>
              重试
            </Button>
          }
        />
      )}

      {error && !usingFallback && <Alert type="error" showIcon message={error} />}

      <Row gutter={16}>
        <Col span={9}>
          <Card
            title="字典列表"
            size="small"
            extra={
              <Space>
                <Button size="small" onClick={reload} loading={loading}>
                  刷新
                </Button>
                <Button
                  size="small"
                  type="primary"
                  onClick={() => {
                    dictForm.resetFields()
                    setDictModalOpen(true)
                  }}
                >
                  新建
                </Button>
              </Space>
            }
          >
            <Table<DictionaryView>
              rowKey="dictCode"
              size="small"
              loading={loading}
              columns={dictColumns}
              dataSource={list}
              pagination={false}
              rowClassName={(row) => (row.dictCode === selectedCode ? 'row-selected' : '')}
              onRow={(row) => ({
                onClick: () => setSelectedCode(row.dictCode),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>
        </Col>

        <Col span={15}>
          {current === null ? (
            <Card size="small">
              <Alert type="info" showIcon message="请选择左侧字典" />
            </Card>
          ) : (
            <Card
              title={current.dictName}
              size="small"
              extra={
                <Space>
                  <Text type="secondary">启用</Text>
                  <Switch checked={current.enabled} onChange={toggleDictEnabled} />
                  <Button
                    size="small"
                    type="primary"
                    onClick={() => {
                      setEditingItem(null)
                      itemForm.resetFields()
                      setItemModalOpen(true)
                    }}
                  >
                    新增取值
                  </Button>
                </Space>
              }
            >
              <Paragraph type="secondary" style={{ marginBottom: 12 }}>
                <Text code>{current.dictCode}</Text>
                {current.description ? ` · ${current.description}` : ''}
              </Paragraph>
              <Table<DictionaryItem>
                rowKey="itemCode"
                size="small"
                columns={itemColumns}
                dataSource={current.items}
                pagination={false}
              />
            </Card>
          )}
        </Col>
      </Row>

      {/* 新建字典 */}
      <Modal
        title="新建字典"
        open={dictModalOpen}
        onCancel={() => setDictModalOpen(false)}
        footer={null}
        destroyOnHidden
      >
        <Form form={dictForm} layout="vertical" onFinish={(v) => void submitDictionary(v)}>
          <Form.Item
            label="字典编码"
            name="dictCode"
            rules={[
              { required: true, message: '请填写字典编码' },
              {
                pattern: /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$/,
                message: '须为小写点分形式，如 rag.subject',
              },
            ]}
            extra="创建后不可修改（前后端契约）"
          >
            <Input placeholder="如：rag.subject" />
          </Form.Item>
          <Form.Item label="字典名称" name="dictName" rules={[{ required: true }]}>
            <Input placeholder="如：学科" />
          </Form.Item>
          <Form.Item label="说明" name="description">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder">
            <InputNumber min={0} max={9999} />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="新建的字典恒为「业务」类型"
            description="内置字典由迁移脚本播种（与代码枚举强绑定），不接受应用层创建。"
          />
          <Button type="primary" htmlType="submit" loading={submitting} block>
            创建
          </Button>
        </Form>
      </Modal>

      {/* 新增 / 编辑取值 */}
      <Modal
        title={editingItem ? `编辑取值 · ${editingItem.itemCode}` : `新增取值 · ${current?.dictName ?? ''}`}
        open={itemModalOpen}
        onCancel={() => {
          setItemModalOpen(false)
          setEditingItem(null)
        }}
        footer={null}
        destroyOnHidden
      >
        <Form form={itemForm} layout="vertical" onFinish={(v) => void submitItem(v)}>
          <Form.Item
            label="取值编码（写库值）"
            name="itemCode"
            rules={[
              { required: true, message: '请填写取值编码' },
              { pattern: /^[A-Za-z0-9._-]{1,128}$/, message: '只允许字母、数字、点、下划线与短横线' },
            ]}
            extra={
              editingItem?.builtIn
                ? '内置项的编码不可修改（后端按枚举校验的正是它）'
                : '写入业务表的实际取值'
            }
          >
            <Input disabled={editingItem?.builtIn === true} placeholder="如：MATH" />
          </Form.Item>
          <Form.Item label="展示标签" name="itemLabel" rules={[{ required: true }]}>
            <Input placeholder="如：数学" />
          </Form.Item>
          <Space size="large" align="start">
            <Form.Item label="排序" name="sortOrder">
              <InputNumber min={0} max={9999} />
            </Form.Item>
            <Form.Item label="启用" name="enabled" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Button type="primary" htmlType="submit" loading={submitting} block>
            保存
          </Button>
        </Form>
      </Modal>
    </Space>
  )
}
