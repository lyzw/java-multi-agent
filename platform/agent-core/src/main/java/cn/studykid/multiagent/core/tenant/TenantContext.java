package cn.studykid.multiagent.core.tenant;

import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * 租户上下文 —— 一切授权判定的起点。
 *
 * <p><b>不可妥协的规则</b>（P0-D02 NFR-001 / P0-D03 §2）：
 * <blockquote>
 * tenant_id <b>只能</b>从可信认证上下文推导，<b>客户端传入的 tenant_id 不作为授权依据</b>。
 * </blockquote>
 *
 * <p>反例（必须避免）：接口形参带 {@code tenantId} 并直接使用 —— 这会让攻击者
 * 改一个参数即可读取他租户数据，属 Sev-0。正确做法是从 JWT/会话声明中提取
 * 主体所属租户，再校验该主体是否被授权访问目标资源。
 *
 * <p>本类为不可变值对象，创建后不可修改，防止请求处理中途被篡改。
 *
 * @param tenantId    租户标识，与数据库 {@code tenant_id} 一致
 * @param principalId 主体标识（用户/服务账号）
 * @param roles       主体在本租户内持有的角色
 * @param authzVersion 授权版本号；撤销授权后递增，用于拒绝持有陈旧上下文的在途请求
 */
public record TenantContext(
        @NonNull String tenantId,
        @NonNull String principalId,
        @NonNull Set<String> roles,
        long authzVersion
) {

    public TenantContext {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId 不能为空");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (authzVersion < 0) {
            throw new IllegalArgumentException("authzVersion 不能为负");
        }
    }

    public boolean hasRole(@NonNull String role) {
        return roles.contains(role);
    }

    /**
     * 断言当前上下文属于指定租户。
     *
     * <p>用于「本应已按租户分区的查询却传入了他人租户标识」的纵深防御——
     * 正常路径下 RLS 已能拦截，此处提供更明确的失败原因。
     *
     * @throws TenantMismatchException 租户不匹配
     */
    public void requireSameTenant(@NonNull String expectedTenantId) {
        if (!tenantId.equals(expectedTenantId)) {
            throw new TenantMismatchException(tenantId, expectedTenantId);
        }
    }

    /**
     * 生成审计友好的表示，<b>不含</b>敏感信息。
     * <p>未成年人场景下 principalId 已假定为不可反推身份的标识（P0-D00 §4）。
     */
    public @NonNull String auditString() {
        return "tenant=" + tenantId + ", principal=" + principalId
                + ", roles=" + roles + ", authzVersion=" + authzVersion;
    }
}