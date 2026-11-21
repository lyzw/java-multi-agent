package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.alibaba.druid.pool.DruidDataSource;

import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditRecordQueryRepository;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditSink;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRejected;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Detail;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Query;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Source;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 对话审计记录查询仓储的真库集成测试。
 *
 * <p><b>验证什么</b>：平台审计角色用 BYPASSRLS 数据源<b>跨租户</b>读回
 * {@code chat_audit_record}（FORCE RLS）与 {@code chat_audit_unattributed}（无 RLS），
 * 两域合并列表 + 游标分页 + 详情还原完整信封。单测只能证明「拼了 SQL」，
 * 本类证明「真的能读到别的租户、翻页不重不漏、详情原文一字不差」。
 *
 * <p>⚠️ 查询仓储必须接 <b>adminPool（BYPASSRLS）</b>；若误接 appPool，
 * 跨租户列表会得到 0 行——本类第一条测试就会红（反证 A）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ChatAuditRecordQueryIT {

    private static final String SLUG_A = "it-chataudit-q-a";
    private static final String SLUG_B = "it-chataudit-q-b";
    /**
     * ⚠️ 测试专用端点标记：库里有 E2E IT 留下的真实审计数据（不同 trace_id 前缀），
     * 查询页本就是跨租户查全表，不能假设「整表只有我写的数据」。所有 Query 一律带
     * endpoint 过滤，把断言范围锁在本测试写入的行——不删别人的数据，也不被干扰。
     */
    private static final String ENDPOINT = "/it/chat-audit-query-test";

    private static DruidDataSource appPool;
    private static DruidDataSource adminPool;
    private static String tenantA;
    private static String tenantB;
    private static JdbcChatAuditSink sink;
    private static ChatAuditRecordQueryPort query;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(4);
        adminPool = FaultInjectionSupport.adminPool();
        tenantA = FaultInjectionSupport.itTenant(adminPool, SLUG_A);
        tenantB = FaultInjectionSupport.itTenant(adminPool, SLUG_B);
        sink = new JdbcChatAuditSink(appPool, ChatAuditRecordQueryIT::dbEnabledConfig);
        // BYPASSRLS 数据源 = 平台运维通道的等价物（admin 角色）。
        query = new JdbcChatAuditRecordQueryRepository(adminPool);
        purge();
    }

    @AfterAll
    static void closePools() {
        if (appPool != null) appPool.close();
        if (adminPool != null) adminPool.close();
    }

    @BeforeEach
    void reset() throws Exception {
        purge();
    }

    private static ChatAuditConfig dbEnabledConfig() {
        ChatAuditConfig d = ChatAuditConfig.defaults();
        return new ChatAuditConfig(d.enabled(), d.scope(),
                new ChatAuditConfig.Sinks(new ChatAuditConfig.Sinks.Database(true), d.sinks().file()),
                d.capture(), d.redaction(), d.sampling(), d.writer(), d.retention(), d.limits());
    }

    @Test
    @DisplayName("跨租户：admin(BYPASSRLS) 能读到 A、B 两租户的原文，app_agent 只读得到 0 行（反证）")
    void crossTenantRead() throws Exception {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        sink.write(envelope(a, tenantA, "A 的专属回答"));
        sink.write(envelope(b, tenantB, "B 的专属回答"));

        Page<ChatAuditRecordQueryPort.Summary> all = query.list(
                new Query(null, null, ENDPOINT, null, null, null, null, 20));
        assertThat(all.items()).extracting(ChatAuditRecordQueryPort.Summary::auditId)
                .containsExactlyInAnyOrder(a, b);

        // 反证：应用角色受 FORCE RLS，不绑租户时查不到任何一行。
        ChatAuditRecordQueryPort appScoped = new JdbcChatAuditRecordQueryRepository(appPool);
        Page<ChatAuditRecordQueryPort.Summary> viaApp = appScoped.list(
                new Query(null, null, ENDPOINT, null, null, null, null, 20));
        assertThat(viaApp.items()).isEmpty();
    }

    @Test
    @DisplayName("两域合并：RECORD 与 UNATTRIBUTED 立表都在；source 过滤生效")
    void twoSources() throws Exception {
        String rec = UUID.randomUUID().toString();
        String rej = UUID.randomUUID().toString();
        sink.write(envelope(rec, tenantA, "回答"));
        sink.write(new ChatAuditRejected(rej, "trace-" + rej, Instant.now(),
                ENDPOINT, "POST", 401, "UNAUTHENTICATED", Map.of("uri", ENDPOINT)));

        Page<ChatAuditRecordQueryPort.Summary> all = query.list(
                new Query(null, null, ENDPOINT, null, null, null, null, 20));
        assertThat(all.items()).extracting(ChatAuditRecordQueryPort.Summary::source)
                .containsExactlyInAnyOrder("RECORD", "UNATTRIBUTED");

        Page<ChatAuditRecordQueryPort.Summary> onlyRej = query.list(
                new Query(Source.UNATTRIBUTED, null, ENDPOINT, null, null, null, null, 20));
        assertThat(onlyRej.items()).hasSize(1);
        assertThat(onlyRej.items().get(0).source()).isEqualTo("UNATTRIBUTED");
        assertThat(onlyRej.items().get(0).terminalState()).isEqualTo("REJECTED");
        assertThat(onlyRej.items().get(0).rejectionReason()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("游标分页：3 条按 started_at DESC 翻页，不重不漏，全序稳定")
    void cursorPagination() throws Exception {
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        String id3 = UUID.randomUUID().toString();
        // 刻意让两条同毫秒，验证 audit_id 参与全序。
        sink.write(envelopeAt(id1, tenantA, base));
        sink.write(envelopeAt(id2, tenantB, base));
        sink.write(envelopeAt(id3, tenantA, base.plusSeconds(1)));

        Page<ChatAuditRecordQueryPort.Summary> p1 = query.list(
                new Query(Source.RECORD, null, ENDPOINT, null, null, null, null, 2));
        assertThat(p1.items()).hasSize(2);
        assertThat(p1.nextCursor()).isNotNull();

        Page<ChatAuditRecordQueryPort.Summary> p2 = query.list(
                new Query(Source.RECORD, null, ENDPOINT, null, null, null, p1.nextCursor(), 2));
        assertThat(p2.items()).hasSize(1);
        assertThat(p2.nextCursor()).isNull();

        List<String> seen = new java.util.ArrayList<>();
        seen.addAll(p1.items().stream().map(ChatAuditRecordQueryPort.Summary::auditId).toList());
        seen.addAll(p2.items().stream().map(ChatAuditRecordQueryPort.Summary::auditId).toList());
        assertThat(seen).containsExactlyInAnyOrder(id1, id2, id3);
    }

    @Test
    @DisplayName("跨两域游标分页：同一 started_at 的 RECORD 与 UNATTRIBUTED 混排不重不漏（source 参与全序）")
    void crossSourceCursorPagination() throws Exception {
        Instant base = Instant.now().minus(2, ChronoUnit.DAYS);
        String rec1 = UUID.randomUUID().toString();
        String rec2 = UUID.randomUUID().toString();
        String rej1 = UUID.randomUUID().toString();
        String rej2 = UUID.randomUUID().toString();
        // 四条刻意同一时间戳：排序完全由 audit_id + source 决定。
        sink.write(envelopeAt(rec1, tenantA, base));
        sink.write(envelopeAt(rec2, tenantB, base));
        sink.write(new ChatAuditRejected(rej1, "trace-" + rej1, base,
                ENDPOINT, "POST", 401, "UNAUTHENTICATED", Map.of("uri", ENDPOINT)));
        sink.write(new ChatAuditRejected(rej2, "trace-" + rej2, base,
                ENDPOINT, "POST", 429, "RATE_LIMITED", Map.of("uri", ENDPOINT)));

        List<ChatAuditRecordQueryPort.Summary> collected = new java.util.ArrayList<>();
        String cursor = null;
        Page<ChatAuditRecordQueryPort.Summary> page;
        do {
            page = query.list(new Query(null, null, ENDPOINT, null, null, null, cursor, 2));
            collected.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(collected).hasSize(4);
        assertThat(collected).extracting(ChatAuditRecordQueryPort.Summary::auditId)
                .containsExactlyInAnyOrder(rec1, rec2, rej1, rej2);
        // 同一页内不能出现相同 (started_at, audit_id, source) 两次（全序唯一性）。
        assertThat(collected).extracting(
                s -> s.startedAt() + "|" + s.auditId() + "|" + s.source()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("详情：RECORD 还原完整 envelope JSON；UNATTRIBUTED 只给元数据")
    void detailRestoresEnvelope() throws Exception {
        String rec = UUID.randomUUID().toString();
        String rej = UUID.randomUUID().toString();
        sink.write(envelope(rec, tenantA, "一字不差的回答"));
        sink.write(new ChatAuditRejected(rej, "trace-" + rej, Instant.now(),
                ENDPOINT, "POST", 429, "RATE_LIMITED", Map.of("uri", ENDPOINT)));

        Detail recDetail = query.detail(Source.RECORD, rec);
        assertThat(recDetail).isNotNull();
        assertThat(recDetail.payloadJson()).contains("一字不差的回答");
        assertThat(recDetail.payloadJson()).contains("\"finalAnswer\"");
        assertThat(recDetail.tenantId()).isEqualTo(tenantA);
        assertThat(recDetail.domain()).isEqualTo("TENANT");

        Detail rejDetail = query.detail(Source.UNATTRIBUTED, rej);
        assertThat(rejDetail).isNotNull();
        assertThat(rejDetail.payloadJson()).contains("uri");
        assertThat(rejDetail.payloadJson()).doesNotContain("finalAnswer");
        assertThat(rejDetail.configVersion()).isZero();

        // source 用错表 = 查不到（两张表的 audit_id 命名空间隔离）。
        assertThat(query.detail(Source.UNATTRIBUTED, rec)).isNull();
    }

    @Test
    @DisplayName("非法参数：损坏游标 → 400 语义的 IllegalArgumentException；非 UUID → 同样拒绝")
    void invalidParams() {
        assertThatThrownBy(() -> query.list(new Query(null, null, null, null, null, null, "!!!not-base64", 20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.detail(Source.RECORD, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChatAuditEnvelope envelope(String auditId, String tenantId, String answer) {
        return envelopeAt(auditId, tenantId, Instant.now(), answer);
    }

    private static ChatAuditEnvelope envelopeAt(String auditId, String tenantId, Instant startedAt) {
        return envelopeAt(auditId, tenantId, startedAt, "回答");
    }

    private static ChatAuditEnvelope envelopeAt(String auditId, String tenantId,
                                                Instant startedAt, String answer) {
        return new ChatAuditEnvelope(auditId, "trace-" + auditId, ENDPOINT, "TENANT",
                tenantId, "u-1", startedAt, startedAt.plusMillis(5), 5L, "SUCCEEDED", 1L,
                new ChatAuditEnvelope.Http("POST", ENDPOINT, Map.of(), 200, startedAt),
                new ChatAuditEnvelope.RequestInfo("{\"message\":\"问题\"}", "问题", List.of(),
                        "agent-1", 5, false, null, null, null, List.of()),
                List.of(), List.of(),
                new ChatAuditEnvelope.Result(answer, "STOP", 3, 0, 2, false),
                null, false, false);
    }

    private static void purge() throws Exception {
        try (var c = adminPool.getConnection(); var ps = c.createStatement()) {
            ps.execute("DELETE FROM public.chat_audit_record WHERE trace_id LIKE 'trace-%'");
            ps.execute("DELETE FROM public.chat_audit_unattributed WHERE trace_id LIKE 'trace-%'");
        }
    }
}
