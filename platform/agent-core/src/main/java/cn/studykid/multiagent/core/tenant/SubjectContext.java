package cn.studykid.multiagent.core.tenant;

import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.security.Scope;

/**
 * 主体上下文 —— 一切授权判定的起点（12 文 §2.2）。
 *
 * <p>在 {@link TenantContext} 四要素（tenantId / principalId / roles / authzVersion）之上
 * <b>只增不改</b>地扩展一个作用域维度 {@link Scope}，用来表达「平台运维身份」——
 * 这是替代 {@code BYPASSRLS} 连接的关键。
 *
 * <p><b>不可妥协的规则</b>（沿用 TenantContext，不新增例外）：
 * <ol>
 *   <li>tenant_id <b>只能</b>从可信认证上下文推导，客户端传入的不作为授权依据。</li>
 *   <li>不可变值对象，创建后不可修改（防请求处理中途被篡改）。</li>
 *   <li>authzVersion 撤销授权后递增，用于拒绝持有陈旧上下文的在途请求。</li>
 *   <li>无上下文即拒绝（对应 C7：TenantSessionBinder.clear() 的 fail-closed 在应用层重建）。</li>
 * </ol>
 *
 * <p><b>scope 的可信推导路径（替代「BYPASSRLS 连接」的关键）</b>：
 * {@code scope} 必须由认证入口依据「该请求是否走平台运维通道」唯一写入；
 * 租户态请求绝不可自设 {@code Scope.PLATFORM}。此推导点是「平台全域放行」的唯一开关，
 * 须用反证钉死（租户请求携带伪造 scope 必须被拒）。
 */
public record SubjectContext(
        @NonNull String tenantId,
        @NonNull String principalId,
        @NonNull Set<String> roles,
        @NonNull Scope scope,
        long authzVersion) {

    public SubjectContext {
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

    /**
     * 平台运维身份 —— 替代「BYPASSRLS 连接」这一表达（12 文 §2.2）。
     *
     * <p>不变式（R3 核心防线）：{@code UNRESTRICTED} 范围只能由本方法为 {@code true} 的主体产出。
     */
    public boolean isPlatformOps() {
        return scope == Scope.PLATFORM && roles.contains("PLATFORM_ADMIN");
    }

    public boolean hasRole(@NonNull String role) {
        return roles.contains(role);
    }

    /**
     * 从既有 {@link TenantContext} 派生，默认 {@link Scope#TENANT}（租户态）。
     *
     * <p>认证入口在确认请求走平台运维通道且主体含 {@code PLATFORM_ADMIN} 时，
     * 应改用 {@link #from(TenantContext, Scope)} 传入 {@link Scope#PLATFORM}。
     */
    public static @NonNull SubjectContext from(@NonNull TenantContext ctx, @NonNull Scope scope) {
        return new SubjectContext(ctx.tenantId(), ctx.principalId(), ctx.roles(), scope, ctx.authzVersion());
    }

    /** 便捷工厂：租户态主体（绝大多数请求）。 */
    public static @NonNull SubjectContext tenant(@NonNull TenantContext ctx) {
        return from(ctx, Scope.TENANT);
    }

    /**
     * 仓储已知租户标识、但无完整认证上下文时构造的主体（租户态）。
     *
     * <p>仅用于影子比对 / 染色校验阶段，让仓储能把「本次查询实际以租户 X 的身份执行」
     * 显式告知 {@link cn.studykid.multiagent.infrastructure.security.AccessGate}，
     * 而<b>不依赖</b>线程局部主体解析器。principalId 取字面量 {@code "repo"}，
     * 不参与任何授权判定（授权只认 {@code tenantId} + {@code scope}）。
     */
    public static @NonNull SubjectContext forTenant(@NonNull String tenantId) {
        return new SubjectContext(tenantId, "repo", Set.of(), Scope.TENANT, 0L);
    }

    /** 生成审计友好的表示，<b>不含</b>敏感信息。 */
    public @NonNull String auditString() {
        return "tenant=" + tenantId + ", principal=" + principalId
                + ", roles=" + roles + ", scope=" + scope + ", authzVersion=" + authzVersion;
    }
}
