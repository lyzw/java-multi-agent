package cn.studykid.multiagent.infrastructure.persistence.cap;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;

/**
 * 平台运维角色的配置发布事务边界（§6.3 第 1 条 · PLATFORM 作用域专用）。
 *
 * <p>⚠️ <b>本类与 {@link JdbcConfigPublishTransaction} 是两条不同的通道</b>，
 * 刻意不合并：
 * <ul>
 *   <li>{@link JdbcConfigPublishTransaction} 用<b>应用角色</b>连接，
 *       PLATFORM 行会被 RLS 拒写 —— 这是<b>刻意的隔离</b>，
 *       保证「任一租户都不能单方面替换全平台生效配置」；</li>
 *   <li>本类用<b>平台运维角色</b>（BYPASSRLS）连接，
 *       是唯一能写 PLATFORM 行的通道。</li>
 * </ul>
 * 若把两者合并成「按 scope 自动选池」，则<b>调用方只需把 scope 写成 PLATFORM</b>
 * 就能拿到运维凭据 —— 那等于把 DB 层的越权防线降级成应用层的一行判断。
 *
 * <p>⚠️ <b>两条闸门</b>：
 * <ol>
 *   <li><b>作用域闸门</b>：{@code tenantId != null}（租户作用域）一律拒绝 ——
 *       运维凭据<b>不得</b>用于写租户数据（那会让运维角色的 BYPASSRLS
 *       变成跨租户写入的后门，也超出「最小权限」）。</li>
 *   <li><b>会话闸门</b>：运维连接只在 {@link PlatformOpsDataSourceProvider}
 *       的会话窗口内交出，窗口外取用一律拒绝
 *       （见 {@link GuardedPlatformOpsDataSource}）。</li>
 * </ol>
 *
 * <p>⚠️ <b>fail-closed</b>：平台运维角色未接入时抛
 * {@link cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException}，
 * <b>绝不</b>回落到应用角色去写平台级数据 ——
 * 那样的失败现场是「RLS 策略拒写」，离「这个环境没配运维凭据」这一真因极远。
 *
 * <p>⚠️ <b>租户上下文仍然绑定</b>：即便连接带 BYPASSRLS，
 * 本类照样绑定 PLATFORM 作用域的零 UUID 哨兵。
 * 若运维角色哪天被误降级为普通角色，写入会因 RLS 被拒（fail-closed），
 * 而不是无声地写进错误的作用域。
 */
public class JdbcPlatformOpsConfigPublishTransaction implements ConfigPublishTransaction {

    /** PLATFORM 作用域的租户哨兵：零 UUID（合法 UUID，不对应任何真实租户）。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final PlatformOpsDataSourceProvider platformOps;

    public JdbcPlatformOpsConfigPublishTransaction(@NonNull PlatformOpsDataSourceProvider platformOps) {
        this.platformOps = platformOps;
    }

    @Override
    public <T> T execute(@Nullable String tenantId, @NonNull Action<T> action) {
        if (tenantId != null) {
            throw new IllegalStateException(
                    "平台运维发布事务不得用于租户作用域（tenantId=" + tenantId + "）。"
                            + "平台运维角色的 BYPASSRLS 会绕过 RLS，用它写租户数据等于开一个"
                            + "跨租户写入的后门。租户作用域的发布请走应用角色的发布通道。");
        }
        return platformOps.inPlatformScopeSession(ops ->
                TransactionalConnectionHolder.inTransaction(
                        ops, context(), conn -> action.run()));
    }

    /**
     * PLATFORM 作用域的租户上下文。
     *
     * <p>⚠️ 必须绑一个<b>合法 UUID</b>：{@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID}，
     * 绑非 UUID 文本会让类型转换直接报错，报错点离「这是平台级作用域」很远。
     */
    private static TenantContext context() {
        return new TenantContext(PLATFORM_SCOPE_TENANT, "system", java.util.Set.of(), 0L);
    }
}
