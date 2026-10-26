import type { ReactNode } from 'react'
import { Form, Modal } from 'antd'
import type { PlatformPublishRequest } from '@/api/types'
import { PlatformPublishPanel } from './PlatformPublishPanel'

/**
 * 平台级对象的**登记弹窗**（第三十七批 · C37-3）。
 *
 * <p><b>⚠️ 为什么「新增」改用弹窗</b>：
 * 「登记」是一次**独立的动作**，不是「详情页的一个变体」——
 * 原来点「登记」要跳到 `/xxx/new` 整页，作者要在一个新页面里填完全部字段，
 * 填完提交后才发现「这个标识已经有人用了」也是常事。
 * 弹窗让「登记」留在目录页上下文里：左边看着目录、右边填表单，
 * 提交完目录还在原地。
 *
 * <p><b>⚠️ 三步工作流不能被弹窗吞掉</b>：
 * 弹窗里放的是<b>完整的</b> {@link PlatformPublishPanel} ——
 * 申请 → 审批 → 执行三步都在弹窗内闭环。
 * 若弹窗只做「申请」而把审批/执行挪走，作者会以为「提交了就已经生效」，
 * 而实际上配置**一个字都没变**。
 *
 * <p><b>⚠️ 字段只有一份</b>：弹窗与详情页都渲染同一套字段组件，
 * 登记与修改**不会**漂移。
 */
export interface PlatformRegistrationModalProps<TValues> {
  /** 弹窗是否打开 */
  open: boolean
  /** 弹窗标题 */
  title: string
  /**
   * 表单取值 → 发布申请体。
   *
   * ⚠️ 与 {@link PlatformPublishPanel} 同口径：
   * 返回 `{ error }` 表示**本地**校验未过；允许返回 Promise（闭包补齐要读网络）。
   */
  buildRequest: (
    values: TValues,
  ) => PlatformPublishRequest | { error: string } | Promise<PlatformPublishRequest | { error: string }>
  /** 字段渲染（必须渲染在 `<Form>` 内部） */
  renderFields: () => ReactNode
  /** 关闭弹窗 */
  onClose: () => void
}

/**
 * 平台级登记弹窗。
 *
 * @param props 弹窗属性
 * @returns 弹窗
 */
export function PlatformRegistrationModal<TValues>({
  open,
  title,
  buildRequest,
  renderFields,
  onClose,
}: PlatformRegistrationModalProps<TValues>): JSX.Element {
  // ⚠️ **弹窗持有自己的表单实例**：与详情页的表单分开 ——
  //    否则「登记」里填了一半的值会出现在「修改」表单里（反之亦然）。
  const [form] = Form.useForm<TValues>()

  return (
    <Modal
      open={open}
      title={title}
      onCancel={onClose}
      width={960}
      footer={null}
      styles={{ body: { maxHeight: '72vh', overflowY: 'auto' } }}
      // ⚠️ 关闭即销毁：下一次「登记」必须是**干净**的表单，
      //    否则上一次填了一半的内容会幽灵般地出现在新表单里。
      destroyOnClose
    >
      <PlatformPublishPanel<TValues>
        form={form}
        buildRequest={buildRequest}
        title="申请 → 审批 → 执行（三步不可合并）"
        submitText="提交登记申请"
        renderFields={renderFields}
      />
    </Modal>
  )
}

export default PlatformRegistrationModal
