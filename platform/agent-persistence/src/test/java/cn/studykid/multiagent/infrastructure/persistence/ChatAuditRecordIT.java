package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.alibaba.druid.pool.DruidDataSource;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditRetentionRepository;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditSink;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRejected;

/**
 * 对话审计落库的真实数据库集成测试（PIPL §7 / 方案 §12「租户隔离」验收项）。
 *
 * <p><b>为什么单元测试不够</b>：{@code chat_audit_record} 的全部安全语义都在
 * SQL 与 RLS 里——{@code FORCE ROW LEVEL SECURITY} + 租户谓词 + 角色授权。
 * mock 掉 DataSource 只能证明「拼了 SQL 字符串」，证明不了
 * <b>「租户 A 真的读不到租户 B 的原文」</b>——而这恰恰是方案里最不能失守的一条。
 *
 * <p><b>四个命题</b>：
 * <ol>
 *   <li>写入 + 租户隔离：A 写的原文，B 绑租户后读 0 行；A 自己读得到；</li>
 *   <li>平台域 nil UUID：不归属任何真实租户，因此任何租户都读不到；</li>
 *   <li>保留期删除只删<b>本租户 + 已到期</b>的行（既不能漏删，也不能越界删）；</li>
 *   <li>应用角色 <b>无权删除</b>（{@code REVOKE DELETE} 生效），
 *       所以清理必须走 {@code agent_retention} —— 这条断言把「为什么需要第二条凭据」钉在库里。</li>
 * </ol>
 *
 * <p>⚠️ 用 {@code app_agent}（NOBYPASSRLS）做业务断言、{@code agent_retention} 做删除、
 * {@code agent}（管理）只用于准备与清理夹具。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ChatAuditRecordIT {

    private static final String SLUG_A = "it-chataudit-a";
    private static final String SLUG_B = "it-chataudit-b";

    private static final String ENDPOINT = "/api/agent/chat/stream";

    private static DruidDataSource appPool;
    private static DruidDataSource adminPool;
    private static DruidDataSource retentionPool;
    private static String tenantA;
    private static String tenantB;

    private static JdbcChatAuditSink sink;
    private static JdbcChatAuditRetentionRepository retention;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(4);
        adminPool = FaultInjectionSupport.adminPool();
        retentionPool = FaultInjectionSupport.retentionPool(2);
        FaultInjectionSupport.requireNonPrivileged(appPool);

        tenantA = FaultInjectionSupport.itTenant(adminPool, SLUG_A);
        tenantB = FaultInjectionSupport.itTenant(adminPool, SLUG_B);

        sink = new JdbcChatAuditSink(appPool, ChatAuditRecordIT::dbEnabledConfig);
        retention = new JdbcChatAuditRetentionRepository(retentionPool);
        purge();
    }

    @AfterAll
    static void closePools() {
        closeQuietly(retentionPool);
        closeQuietly(appPool);
        closeQuietly(adminPool);
    }

    @BeforeEach
    void reset() throws Exception {
        purge();
    }

    @AfterEach
    void clearHolder() {
        // 残留 ThreadLocal 会让后续测试复用上一个租户的连接，造成跨租户串数据。
        TransactionalConnectionHolder.clear();
    }

    private static void closeQuietly(DruidDataSource pool) {
        if (pool != null) {
            pool.close();
        }
    }

    /** 只打开 DB sink；其它配置与默认值一致。 */
    private static ChatAuditConfig dbEnabledConfig() {
        ChatAuditConfig d = ChatAuditConfig.defaults();
        return new ChatAuditConfig(d.enabled(), d.scope(),
                new ChatAuditConfig.Sinks(new ChatAuditConfig.Sinks.Database(true),
                        d.sinks().file()),
                d.capture(), d.redaction(), d.sampling(), d.writer(), d.retention(), d.limits());
    }

    private static ChatAuditEnvelope envelope(String auditId,
                                              String domain,
                                              String tenantId,
                                              String answer,
                                              Instant startedAt) {
        return new ChatAuditEnvelope(auditId, "trace-" + auditId, ENDPOINT, domain,
                tenantId, "u-1", startedAt, startedAt.plusMillis(5), 5L, "SUCCEEDED", 1L,
                new ChatAuditEnvelope.Http("POST", ENDPOINT, Map.of(), 200, startedAt),
                new ChatAuditEnvelope.RequestInfo(
                        "{\"message\":\"我的孩子数学很差\"}", "我的孩子数学很差", List.of(),
                        "agent-1", 5, true, null, null, null, List.of()),
                List.of(), List.of(),
                new ChatAuditEnvelope.Result(answer, "STOP", 3, 0, 2, false),
                null, false, false);
    }

    // ══════════════════════════════════════════════════════════════
    // ① 写入 + 租户隔离
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 租户 A 写的原文，租户 B 读不到（FORCE RLS + 谓词）")
    void tenantCannotReadAnotherTenantsAuditRaw() throws Exception {
        String auditId = UUID.randomUUID().toString();
        sink.write(envelope(auditId, "TENANT", tenantA, "A 的专属回答", Instant.now()));

        assertThat(countAs(tenantB, auditId))
                .as("⛔ 租户 B 读到了租户 A 的审计原文：租户隔离失守，方案 §12 直接不成立")
                .isZero();
        assertThat(countAs(tenantA, auditId))
                .as("⚠️ 本租户读不到自己刚写的记录：写入没提交或事务绑定错位")
                .isEqualTo(1);
        assertThat(readAnswerAs(tenantA, auditId))
                .as("审计必须存原文——只存摘要就失去「效果评估」的意义")
                .isEqualTo("A 的专属回答");
    }

    @Test
    @DisplayName("⛔ 平台域记录用 nil UUID 占位：不归属任何真实租户，因此租户读不到")
    void platformDomainRecordIsInvisibleToTenants() throws Exception {
        String auditId = UUID.randomUUID().toString();
        // domain=PLATFORM 且没有目标租户：sink 必须落 nil UUID 才能写入（列 NOT NULL）
        sink.write(envelope(auditId, "PLATFORM", null, "平台直连回答", Instant.now()));

        assertThat(countAs(tenantA, auditId)).isZero();
        assertThat(countAs(tenantB, auditId)).isZero();
        assertThat(countInAdmin(auditId))
                .as("⚠️ 平台域记录根本没写进去：NOT NULL 列 + 无租户的组合会静默丢记录")
                .isEqualTo(1);
        assertThat(tenantIdOf(auditId))
                .as("⛔ 平台域未用 nil UUID 占位：NOT NULL 列下唯一可能的替代是 NULL，"
                        + "会让 RLS 谓词恒为 NULL 从而既写不进也读不出")
                .isEqualTo(ChatAuditEnvelope.PLATFORM_TENANT_ID);
    }

    // ══════════════════════════════════════════════════════════════
    // ② 建连前失败：只存元数据
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("建连前被拒的请求进旁路表，且只含元数据（不含请求体）")
    void unattributedRecordStoresMetadataOnly() throws Exception {
        String auditId = UUID.randomUUID().toString();
        sink.write(new ChatAuditRejected(auditId, "trace-" + auditId, Instant.now(),
                "/api/runs", "POST", 401, "未认证",
                Map.of("uri", "/api/runs", "queryKeys", "none", "durationMs", "3")));

        Map<String, Object> row = unattributedRow(auditId);
        assertThat(row).isNotNull();
        assertThat(String.valueOf(row.get("metadata")))
                .as("⛔ 旁路表出现了请求体内容：建连前无租户可绑，落原文等于跨租户泄露")
                .doesNotContain("我的孩子")
                .doesNotContain("Authorization");
        assertThat(row.get("http_status")).isEqualTo(401);
    }

    // ══════════════════════════════════════════════════════════════
    // ③ 保留期删除
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 保留期只删「本租户 + 已到期」：不漏删、不越界删")
    void retentionDeletesOnlyExpiredRowsOfThatTenant() throws Exception {
        String oldA = UUID.randomUUID().toString();
        String freshA = UUID.randomUUID().toString();
        String oldB = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant longAgo = now.minus(40, ChronoUnit.DAYS);

        sink.write(envelope(oldA, "TENANT", tenantA, "A 的旧记录", longAgo));
        sink.write(envelope(freshA, "TENANT", tenantA, "A 的新记录", now));
        sink.write(envelope(oldB, "TENANT", tenantB, "B 的旧记录", longAgo));
        // created_at 是 DEFAULT now()：把「旧记录」的 created_at 回拨，模拟 40 天前落库
        backdate(oldA, longAgo);
        backdate(oldB, longAgo);

        Instant cutoff = now.minus(30, ChronoUnit.DAYS);
        int deleted = retention.purgeExpired(tenantA, cutoff, 100);

        assertThat(deleted).as("到期行没被删：保留期承诺只在文档里").isEqualTo(1);
        assertThat(countInAdmin(oldA)).as("⛔ 到期的本租户记录仍在").isZero();
        assertThat(countInAdmin(freshA)).as("⛔ 未到期记录被删：保留期变成「随便删」").isEqualTo(1);
        assertThat(countInAdmin(oldB))
                .as("⛔ 删到了别的租户：清理通道越界，比不清理更严重")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⛔ 应用角色无权删除审计原文（REVOKE DELETE 生效，故清理必须走 agent_retention）")
    void appRoleCannotDeleteAuditRaw() throws Exception {
        String auditId = UUID.randomUUID().toString();
        sink.write(envelope(auditId, "TENANT", tenantA, "不该被应用删掉", Instant.now()));

        assertThatThrownBy(() -> deleteAsApp(tenantA, auditId))
                .as("⛔ 应用凭据能删审计原文：受控清理通道形同虚设，"
                        + "持有应用凭据者即可抹掉自己的痕迹")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
        assertThat(countInAdmin(auditId)).isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════
    // 夹具：查询与清理
    // ══════════════════════════════════════════════════════════════

    /** 在指定租户上下文中统计某条记录（RLS 生效，必须事务内先绑定租户）。 */
    private static int countAs(String tenantId, String auditId) throws Exception {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn,
                        new TenantContext(tenantId, "it", Set.of(), 0L));
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT count(*) FROM public.chat_audit_record WHERE audit_id = ?::uuid")) {
                    ps.setString(1, auditId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    private static String readAnswerAs(String tenantId, String auditId) throws Exception {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn,
                        new TenantContext(tenantId, "it", Set.of(), 0L));
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT envelope #>> '{result,finalAnswer}' "
                                + "FROM public.chat_audit_record WHERE audit_id = ?::uuid")) {
                    ps.setString(1, auditId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    /** 管理连接（绕过 RLS）统计：用于验证「确实写进去了 / 确实删掉了」。 */
    private static int countInAdmin(String auditId) throws Exception {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM public.chat_audit_record WHERE audit_id = ?::uuid")) {
            ps.setString(1, auditId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String tenantIdOf(String auditId) throws Exception {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT tenant_id::text FROM public.chat_audit_record WHERE audit_id = ?::uuid")) {
            ps.setString(1, auditId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Map<String, Object> unattributedRow(String auditId) throws Exception {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT http_status, metadata::text FROM public.chat_audit_unattributed "
                             + "WHERE audit_id = ?::uuid")) {
            ps.setString(1, auditId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new java.util.HashMap<>();
                row.put("http_status", rs.getInt(1));
                row.put("metadata", rs.getString(2));
                return row;
            }
        }
    }

    /** 回拨 created_at（保留期判据列）。管理连接执行，绕开 RLS。 */
    private static void backdate(String auditId, Instant createdAt) throws Exception {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE public.chat_audit_record SET created_at = ? WHERE audit_id = ?::uuid")) {
            ps.setTimestamp(1, Timestamp.from(createdAt));
            ps.setString(2, auditId);
            ps.executeUpdate();
        }
    }

    private static void deleteAsApp(String tenantId, String auditId) throws Exception {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn,
                        new TenantContext(tenantId, "it", Set.of(), 0L));
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM public.chat_audit_record WHERE audit_id = ?::uuid")) {
                    ps.setString(1, auditId);
                    ps.executeUpdate();
                }
                conn.commit();
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static void purge() throws Exception {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM public.chat_audit_record "
                             + "WHERE tenant_id IN (SELECT tenant_id FROM tenant WHERE slug LIKE 'it-%')")) {
            ps.executeUpdate();
        }
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM public.chat_audit_unattributed")) {
            ps.executeUpdate();
        }
    }
}
