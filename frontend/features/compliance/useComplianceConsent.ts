/**
 * F-8/F-9: 合规同意流程 (Compliance Consent) — 诚实渲染层
 *
 * 基于 FE-D7 §2.3：零保留供应商清单与 fail-closed 闸门的诚实渲染。
 *
 * ⚠️ 诚实边界（与台账一致）：
 * 本文件只负责「渲染已声明状态」，不伪造业务确认。
 * 供应商的 CONFIRMED（出境 / 本地处理边界确认）仍待产品确认，
 * 在确认前 registryStatus 恒为 PENDING，闸门处于 fail-closed。
 * 不得声称「合规同意流程已完成」——业务侧确认流程未落地。
 */

import type { ZeroRetentionView } from '@/api/types';
import { useConfig } from '../config/useConfig';

type ProviderStatus = 'PENDING' | 'CONFIRMED';

/**
 * F-8: 供应商清单诚实渲染。
 * PENDING → chip--mark（待确认）/ CONFIRMED → chip--settled（已确认）。
 */
export function renderZeroRetentionProviders(view: ZeroRetentionView): JSX.Element {
  const providers = view.declaredProviders?.split(',').filter(Boolean) ?? [];
  return (
    <table className="zero-retention">
      <thead>
        <tr>
          <th>供应商</th>
          <th>确认状态</th>
        </tr>
      </thead>
      <tbody>
        {providers.map((provider) => {
          const status = (view.registryStatus?.[provider] as ProviderStatus) ?? 'PENDING';
          return (
            <tr key={provider}>
              <td>{provider}</td>
              <td className={status === 'PENDING' ? 'chip chip--mark' : 'chip chip--settled'}>
                {status}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/**
 * F-9: fail-closed 闸门说明 — 仅在闸门生效时渲染。
 */
export function renderZeroRetentionFailClosed(view: ZeroRetentionView): JSX.Element | null {
  if (!view.failClosed) {
    return null;
  }
  return (
    <div className="marked">
      零保留闸门生效中 (fail-closed)：未确认证据的供应商调用真实数据会被拒绝
    </div>
  );
}

/**
 * 合规同意流程实现状态。
 * ⚠️ 诚实返回：仅「渲染层」完成；出境 / 本地处理边界的业务确认仍待产品介入。
 */
export function getComplianceConsentStatus(): {
  rendered: true;
  businessConfirmation: 'pending-product';
} {
  return { rendered: true, businessConfirmation: 'pending-product' };
}

/** 供组件层使用：从 useConfig 读取零保留视图并渲染 fail-closed 说明。 */
export function useComplianceConsent() {
  const [view] = useConfig();
  return {
    view,
    failClosedNotice: renderZeroRetentionFailClosed(view),
  };
}
