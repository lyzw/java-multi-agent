package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.jspecify.annotations.NonNull;

/**
 * 租户运行前置的**单一真相源**（P0 · E2E 二次评估 2026-11-14）。
 *
 * <p>⚠️ 背景：租户开通原先只写「三处齐」（platform.tenant / public.tenant /
 * tenant_quota），却漏了执行链必需的两类行：
 * <ul>
 *   <li>{@code budget_account} 缺失 → 模型预留返回 {@code NO_ACCOUNT} →
 *       Run 收敛为 {@code BUDGET_EXHAUSTED}；</li>
 *   <li>{@code cap_authz_version (tenant_id,'worker')} 缺失 →
 *       {@code CapExecutionFlow} / {@code DelegationExecutor} 等六处
 *       {@code require()} fail-closed → {@code AUTHZ_UNAVAILABLE}；</li>
 * </ul>
 * 两者叠加使「新租户创建 Run 返回 201，随后静默 FAILED」。
 * E2E 只能靠人工 SQL 补数绕过，生产没有任何写入方。
 *
 * <p>⚠️ 两条开通入口（平台侧 {@link JdbcPlatformTenantRepository} 与管理侧
 * {@link AdminTenantRepository}）都调用本方法，避免 SQL 形状漂移。
 *
 * <p>⚠️ 连接必须是 {@code platform_ops}（BYPASSRLS + public 全表 DML 授权），
 * 否则写入 FORCE RLS 表会静默变成 0 行（本项目既有教训）。
 *
 * <p>⛔ 本方法只做「开通时显式建行」这一**写侧**动作，
 * 绝不把六处生产读点改成「缺行即补建」的 {@code currentOrInitialize}
 * ——那会把「从未授权」伪装成「版本 1 的已授权主体」。
 */
public final class TenantRuntimeDefaults {

    /**
     * Worker 服务主体。
     *
     * <p>与 {@code CapExecutionFlow.DEFAULT_PRINCIPAL_ID} 及
     * {@code DelegationExecutor.buildContext} 里的字面量同源；
     * 本模块不依赖 agent-application，故此处保留常量并锁进测试。
     */
    public static final String WORKER_PRINCIPAL_ID = "worker";

    /** 新租户默认预算额度（与 02_platform_seed.sql 附录 B 的 t-b 同口径）。 */
    public static final BigDecimal DEFAULT_TENANT_BUDGET_LIMIT = new BigDecimal("1000.000000");

    private TenantRuntimeDefaults() {
    }

    /**
     * 在**调用方事务内**补齐租户运行前置；幂等（ON CONFLICT DO NOTHING）。
     *
     * @param conn     已置为手动提交的连接（提交/回滚由调用方负责）
     * @param tenantId 已插入 public.tenant 的租户 UUID 字符串
     */
    public static void insertRuntimePrerequisites(@NonNull Connection conn,
                                                  @NonNull String tenantId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO budget_account "
                        + "(tenant_id, currency, total_limit, reserved_amount, settled_amount) "
                        + "VALUES (?::uuid, 'CNY', ?, 0, 0) "
                        + "ON CONFLICT (tenant_id, currency) DO NOTHING")) {
            ps.setString(1, tenantId);
            ps.setBigDecimal(2, DEFAULT_TENANT_BUDGET_LIMIT);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cap_authz_version "
                        + "(tenant_id, principal_id, authz_version, version, updated_at) "
                        + "VALUES (?::uuid, ?, 1, 0, now()) "
                        + "ON CONFLICT (tenant_id, principal_id) DO NOTHING")) {
            ps.setString(1, tenantId);
            ps.setString(2, WORKER_PRINCIPAL_ID);
            ps.executeUpdate();
        }
    }
}
