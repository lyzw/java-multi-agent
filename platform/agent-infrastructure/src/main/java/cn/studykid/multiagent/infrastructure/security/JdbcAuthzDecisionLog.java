package cn.studykid.multiagent.infrastructure.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC 授权决策记录器（12 文 §4.5 / D6 · P2 落地）。
 *
 * <p>把每次判定写入 {@code platform.authz_decision_log}（平台域审计表）。
 * <b>本实现已编译可用，但目标表需在 P2 通过 DDL 建立后方可运行</b>：
 *
 * <pre>{@code
 * CREATE TABLE platform.authz_decision_log (
 *     id            BIGSERIAL PRIMARY KEY,
 *     occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
 *     tenant_id     TEXT        NOT NULL,   -- ⚠️ TEXT 而非 UUID，见下
 *     principal_id  TEXT        NOT NULL,
 *     scope         TEXT        NOT NULL,
 *     action        TEXT        NOT NULL,
 *     resource_type TEXT        NOT NULL,
 *     permitted     BOOLEAN     NOT NULL,
 *     reason_code   TEXT
 * );
 * CREATE INDEX authz_decision_log_occurred_idx ON platform.authz_decision_log (occurred_at DESC);
 * CREATE INDEX authz_decision_log_tenant_idx   ON platform.authz_decision_log (tenant_id, occurred_at DESC);
 * }</pre>
 *
 * <p>⚠️ <b>tenant_id 为什么是 TEXT 而不是 UUID</b>：本表是<b>审计流水</b>，
 * 目标是「无论主体长什么样都能记下来」。写成 UUID 后，任何非 UUID 形态的租户标识
 * （测试夹具、历史迁移遗留、将来的非 PG 后端）都会让写入抛类型错误 ——
 * 而审计表写入失败意味着<b>这次判定没有留下痕迹</b>，恰恰是最需要痕迹的时刻。
 *
 * <p><b>失败语义：只记录，绝不外抛</b>。决策日志是 observability，不能成为关键路径的故障源：
 * <ul>
 *   <li>Permit 路径上抛异常 = 把「放行」变成 5xx；</li>
 *   <li>Deny 路径上抛异常 = 原始拒绝原因码被内部异常覆盖，调用方分不清 403 与 500。</li>
 * </ul>
 * 另外 {@link DefaultAccessGate#record} 也做了同样的兜底 —— <b>两层都要有</b>：
 * 门禁层保证「换任何 recorder 实现都不会破坏判定」，本类保证「直接使用本类时也不会」。
 *
 * <p>日志按 {@code 60s} 窗口限频（否则审计表不可用时会以每次判定的频率刷 WARN，
 * 把真正有用的告警挤掉）；被抑制的条数以 {@code suppressed} 计数补报。
 */
public final class JdbcAuthzDecisionLog implements AuthzDecisionRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAuthzDecisionLog.class);

    /** 失败日志限频窗口（毫秒）。 */
    private static final long WARN_WINDOW_MILLIS = 60_000L;

    private static final String SQL = """
            INSERT INTO platform.authz_decision_log
                (occurred_at, tenant_id, principal_id, scope, action, resource_type, permitted, reason_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 零行写入探测：与 {@link #SQL} <b>同一张表、同一组列</b>，但 {@code WHERE FALSE} 保证不产生行。
     *
     * <p>⚠️ 不是「查权限」而是「真的走一遍 INSERT 的解析与类型检查」：
     * <ul>
     *   <li>{@code has_table_privilege} 只能回答「有没有权限」，回答不了
     *       「列名/列类型是否还在」—— 表被改过一列，权限检查照样通过，
     *       而每次判定都在写失败后被限频告警吞掉（<b>静默无审计</b>）；</li>
     *   <li>{@code WHERE FALSE} 让 INSERT 在<b>执行期一行不写</b>，
     *       但语法解析、权限检查、目标列类型推导全部真实发生过。</li>
     * </ul>
     * 这是 P2「决策日志上线」的启动门禁：探不通就不许起，而不是起来后静默丢审计。
     */
    private static final String PROBE_SQL = """
            INSERT INTO platform.authz_decision_log
                (occurred_at, tenant_id, principal_id, scope, action, resource_type, permitted, reason_code)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?
            WHERE FALSE
            """;

    private final DataSource dataSource;

    private final AtomicLong lastWarnAt = new AtomicLong(0L);
    private final AtomicLong suppressed = new AtomicLong(0L);

    public JdbcAuthzDecisionLog(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void record(@NonNull SubjectContext subject,
                       @NonNull Action action,
                       @NonNull ResourceRef resource,
                       boolean permitted,
                       @Nullable String reasonCode) {
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, subject.tenantId());
            ps.setString(3, subject.principalId());
            ps.setString(4, subject.scope().name());
            ps.setString(5, action.name());
            ps.setString(6, resource.type());
            ps.setBoolean(7, permitted);
            ps.setString(8, reasonCode);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            warnThrottled(e, action, resource, permitted);
        }
    }

    /**
     * 限频告警：窗口内只打一条，其余计数；下一条带出被抑制的条数。
     *
     * <p>⚠️ 失败绝不外抛（见类注释）。{@code SQLException} 之外不捕获其他异常：
     * 参数为 {@code null} 之类的编程错误应当暴露，而不是被审计层吞掉。
     */
    /**
     * 启动期就绪探测（{@code WHERE FALSE} 零行写入，见 {@link #PROBE_SQL}）。
     *
     * <p>⚠️ 失败<b>只返回 false 并打一条 ERROR</b>，不抛异常 ——
     * 是否拒绝启动由装配层决定（那里的提示能说清「该去跑哪个 DDL」）。
     *
     * @return true = 表存在、当前角色可 INSERT、目标列与写入语句一致
     */
    public boolean probe() {
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(PROBE_SQL)) {
            ps.setTimestamp(1, Timestamp.from(Instant.EPOCH));
            ps.setString(2, "SELF_CHECK");
            ps.setString(3, "SELF_CHECK");
            ps.setString(4, "TENANT");
            ps.setString(5, "RETRIEVE");
            ps.setString(6, "SELF_CHECK");
            ps.setBoolean(7, false);
            ps.setString(8, "SELF_CHECK");
            ps.executeUpdate();
            conn.commit();
            return true;
        } catch (SQLException e) {
            LOG.error("⛔ 授权决策日志不可写（零行探测失败）：表缺失 / 无 INSERT 权限 / 列已漂移。"
                    + "请执行 deploy/sql/platform/01_platform_ddl.sql 中的 platform.authz_decision_log "
                    + "定义并重跑 03_platform_roles.sql 的授权段落。", e);
            return false;
        }
    }

    private void warnThrottled(SQLException e, Action action, ResourceRef resource, boolean permitted) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last < WARN_WINDOW_MILLIS) {
            suppressed.incrementAndGet();
            return;
        }
        // 抢占本窗口的告警权：抢不到说明并发线程刚打过，本线程只计数
        if (!lastWarnAt.compareAndSet(last, now)) {
            suppressed.incrementAndGet();
            return;
        }
        long dropped = suppressed.getAndSet(0L);
        LOG.warn("授权决策日志写入失败（已忽略，不影响判定）: action={} resource={} permitted={} 本窗口另有 {} 条未记录",
                action, resource.type(), permitted, dropped, e);
    }

    private Connection openSystem() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        // 平台域审计表无 RLS，但仍显式清空连接级 GUC —— 连接池复用时不残留上一请求的租户
        TenantSessionBinder.clear(conn);
        return conn;
    }
}
