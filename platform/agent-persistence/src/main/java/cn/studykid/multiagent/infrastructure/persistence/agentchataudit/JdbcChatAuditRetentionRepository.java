package cn.studykid.multiagent.infrastructure.persistence.agentchataudit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRetentionPort;

/**
 * 对话审计记录的保留期删除（PIPL §7）。
 *
 * <p><b>⚠️ 必须用 {@code agent_retention} 数据源构造本类</b>：
 * {@code 03_platform_roles.sql} 对 {@code public.chat_audit_record} 只给
 * {@code app_agent} 授了 {@code SELECT, INSERT} 并显式 {@code REVOKE DELETE}。
 * 拿应用数据源跑本类，结果一定是到期那天 {@code permission denied}——
 * 而那时现场离「连接凭据不对」极远，容易被误判成「表被锁了」。
 *
 * <p><b>⚠️ RLS 铁律</b>：表上是 {@code FORCE ROW LEVEL SECURITY} +
 * {@code tenant_id = public.current_tenant_id()}。因此
 * <ol>
 *   <li>必须在事务内、删除 SQL 之前绑定 {@code app.tenant_id}
 *       （{@link TransactionalConnectionHolder#inTransaction} 经
 *       {@code TenantSessionBinder} 完成）；</li>
 *   <li>SQL 里仍要<b>显式</b>带 {@code tenant_id = ?::uuid}：RLS 退役期（M4）
 *       之后功能不变靠的就是这条显式谓词，而不是策略。</li>
 * </ol>
 * 若忘记绑定，PG 不会报错，只会<b>静默删 0 行</b>——
 * 「清理成功但一行没删」是这类缺陷最难发现的一种形态。
 *
 * <p><b>⚠️ 用 {@code ctid} 子查询分批而不是直接 {@code DELETE ... LIMIT}</b>：
 * PG 的 {@code DELETE} 不支持 {@code LIMIT}；而无上限的
 * {@code DELETE ... WHERE created_at < ?} 会在大表上产生长事务，
 * 把写入侧的 INSERT 一起拖住。与 {@code JdbcErasureRepository} 同一写法。
 *
 * <p><b>装配</b>：刻意不挂 {@code @Repository}，由组合根显式注册。
 */
public class JdbcChatAuditRetentionRepository implements ChatAuditRetentionPort {

    private static final Logger log =
            LoggerFactory.getLogger(JdbcChatAuditRetentionRepository.class);

    /**
     * 删除语句。
     *
     * <p>⚠️ 只删主表，<b>不动</b> {@code chat_audit_unattributed}：
     * 后者只有元数据（无原文、无租户），保留它才能回答
     * 「最近有没有请求因为无法归属租户而进了旁路表」——那是一个需要被看见的信号，
     * 而不是该被顺手清掉的历史。
     */
    private static final String DELETE_SQL = """
            DELETE FROM public.chat_audit_record
             WHERE tenant_id = ?::uuid
               AND created_at < ?
               AND ctid IN (SELECT ctid FROM public.chat_audit_record
                             WHERE tenant_id = ?::uuid
                               AND created_at < ?
                             ORDER BY created_at
                             LIMIT ?)
            """;

    private final DataSource dataSource;

    public JdbcChatAuditRetentionRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public int purgeExpired(@NonNull String tenantId,
                            @NonNull Instant cutoff,
                            int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("单轮上限必须 > 0: " + limit);
        }
        return inTenantTx(tenantId, conn -> {
            // ⚠️ 声明清理主体：与 audit_record 同一条纪律（04_retention_role.sql 的
            //    触发器按此 GUC 与连接角色共同判定删除合法性）。缺失时 PG 只有在
            //    真正 DELETE 的那一刻才会拒绝，报错信息里也不会出现「你忘了声明主体」。
            declareRetentionActor(conn);
            try (PreparedStatement ps = conn.prepareStatement(DELETE_SQL)) {
                ps.setString(1, tenantId);
                ps.setTimestamp(2, Timestamp.from(cutoff));
                ps.setString(3, tenantId);
                ps.setTimestamp(4, Timestamp.from(cutoff));
                ps.setInt(5, limit);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    log.info("对话审计原文已按保留期清理: tenant={}, rows={}, cutoff={}",
                            tenantId, deleted, cutoff);
                }
                return deleted;
            }
        });
    }

    /**
     * 在租户事务内执行。
     *
     * <p>⚠️ 主体名固定为 {@code chat-audit-retention}：它出现在
     * {@code erasure_certificate} 的 actor 字段里，运维据此区分
     * 「这是对话审计的自动清理」还是「这是某个人手工删的」。
     */
    private <T> T inTenantTx(String tenantId, TxAction<T> action) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "chat-audit-retention", Set.of(), 0L),
                action::run);
    }

    /** 声明清理主体 GUC（事务级，{@code set_config(..., true)}）。 */
    private static void declareRetentionActor(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('agent.retention.actor', ?, true)")) {
            ps.setString(1, "chat-audit-retention");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    @FunctionalInterface
    private interface TxAction<T> {
        @Nullable T run(Connection conn) throws SQLException;
    }
}
