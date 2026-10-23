package cn.studykid.multiagent.infrastructure.persistence.cap;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;

/**
 * {@link ConfigPublishTransaction} 的 PostgreSQL 实现（§6.3 第 1 条 · W-PERM-P5）。
 *
 * <p>⚠️ <b>本类的全部职责就是「让三件事落在同一事务里」</b>：
 * 版本行插入、release 记录、指针 CAS 各自调用不同的仓储，
 * 若它们各自开各自的事务，就会出现「版本行已插入、指针未移动」的半成品当前发布
 * —— 那正是 PUB-1 要防的失败形态。
 *
 * <p>⚠️ <b>异常即回滚</b>：
 * 动作抛出任何运行时异常，{@link TransactionalConnectionHolder} 会在归还连接前显式回滚，
 * 本次发布的一切写入都不留痕迹。
 *
 * <p>⚠️ <b>RLS 前置条件</b>：
 * 租户上下文必须在事务内、且在任何业务 SQL 之前绑定。
 * PLATFORM 作用域绑定零 UUID 哨兵（合法 UUID，且不对应任何真实租户）。
 */
public class JdbcConfigPublishTransaction implements ConfigPublishTransaction {

    /** PLATFORM 作用域的租户哨兵：零 UUID。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final DataSource dataSource;

    public JdbcConfigPublishTransaction(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T execute(@Nullable String tenantId, @NonNull Action<T> action) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId),
                conn -> action.run());
    }

    /**
     * 构造租户上下文。
     *
     * <p>⚠️ PLATFORM 作用域必须绑定一个<b>合法 UUID</b>：{@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID}，
     * 绑非 UUID 文本会让类型转换直接报错（而不是「读不到行」），
     * 报错点落在类型转换上，离「这个作用域是平台级的」这一成因很远。
     */
    private static TenantContext context(@Nullable String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }
}
