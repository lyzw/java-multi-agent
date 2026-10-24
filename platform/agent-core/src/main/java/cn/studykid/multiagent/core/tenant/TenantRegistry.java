package cn.studykid.multiagent.core.tenant;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 租户注册表行记录（管理控制台 P0.5 · 平台级租户治理）。
 *
 * <p>⚠️ <b>为什么记录下沉到 core</b>：{@code tenant} 表是<b>全局表、无 RLS</b>，
 * 平台级读/写不走租户连接；其行结构既被 API 层消费（响应投影），
 * 也被持久层产出（JDBC 映射）。
 * 若把记录留在 infrastructure，API 层就必须引用 infrastructure ——
 * 那会打破 {@code agent-api → agent-ports → agent-core} 的分层方向，
 * 使 API 层绕过应用层直连持久实现。
 *
 * <p>⚠️ <b>本记录不承载任何凭据</b>：主体行只有 id、登录名、显示名、状态、
 * 授权版本与角色集，没有口令散列、没有 token、没有 secret。
 */
public final class TenantRegistry {

    private TenantRegistry() {
    }

    /**
     * 租户行。
     *
     * @param tenantId  租户标识
     * @param slug      租户短标识（URL 友好，全局唯一）
     * @param name      租户显示名
     * @param status    生命周期状态（ACTIVE / SUSPENDED / DECOMMISSIONED）
     * @param createdAt 创建时刻
     * @param updatedAt 最后更新时刻
     */
    public record TenantRow(@NonNull String tenantId, @NonNull String slug,
                            @NonNull String name, @NonNull String status,
                            @Nullable Instant createdAt, @Nullable Instant updatedAt) {

        public TenantRow {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId 不能为空");
            }
            if (slug == null || slug.isBlank()) {
                throw new IllegalArgumentException("slug 不能为空");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name 不能为空");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status 不能为空");
            }
        }
    }

    /**
     * 主体行（含其角色集）。
     *
     * ⚠️ <b>无任何凭据字段</b>：口令散列、token、secret 一律不出现。
     *
     * @param principalId   主体标识
     * @param loginName     登录名
     * @param displayName   显示名
     * @param status        主体状态
     * @param authzVersion  主体授权版本（撤权水位）
     * @param roles         角色集
     */
    public record PrincipalRow(@NonNull String principalId, @NonNull String loginName,
                               @Nullable String displayName, @NonNull String status,
                               long authzVersion, @NonNull List<String> roles) {

        public PrincipalRow {
            if (principalId == null || principalId.isBlank()) {
                throw new IllegalArgumentException("principalId 不能为空");
            }
            if (loginName == null || loginName.isBlank()) {
                throw new IllegalArgumentException("loginName 不能为空");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status 不能为空");
            }
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /**
     * RLS 隔离校验结果。
     *
     * @param contextBound  会话上下文是否成功绑定为目标租户
     * @param isolated      是否无法读取其他租户数据
     * @param leakedRows    越界读到的行数（须为 0）
     * @param boundTenantId 实际绑定的租户标识
     */
    public record RlsVerifyResult(boolean contextBound, boolean isolated,
                                  long leakedRows, @Nullable String boundTenantId) {
    }
}
