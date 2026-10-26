import { Navigate } from 'react-router-dom'

/**
 * 开通租户（第三十七批 · C37-3：整页改为**弹窗**）。
 *
 * <p>⚠️ 本路径现在只作**深链入口**：立刻跳回租户管理页并打开开通弹窗 ——
 * 同一套开通表单不该有两个宿主（一个在弹窗里、一个在整页里），否则迟早漂移。
 *
 * <p>⚠️ 真正的表单在 {@code TenantProvisionForm}（弹窗与本页共用）。
 */
export function TenantNewPage(): JSX.Element {
  return <Navigate to="/tenants?register=1" replace />
}

export default TenantNewPage
