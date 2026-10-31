package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditQuery;

/**
 * 平台审计「票据档位」列的真库往返（U-8 / 方案 §5 A4）。
 *
 * <p><b>⚠️ 必须跑在真实 PG 上，单元替身在这件事上零价值</b>：本类盯的是两个
 * 「编译期与替身都看不见」的失效形态——
 * <ol>
 *   <li><b>按位串列</b>：{@code JdbcPlatformAuditRepository} 的 INSERT 列清单与
 *       {@code ?} 占位符、以及 SELECT 列表与 {@code mapEvent} 的取值顺序，都是
 *       <b>按位置</b>对齐的。加一列漏改一处不会编译失败，表现是
 *       <b>整行往后挪一格</b>：{@code varchar} 列接到邻列的文本，写成功、读得回、
 *       值却是别人的。只有「写进去再读回来逐字段对账」能抓住它。</li>
 *   <li><b>CHECK 是不是真的在</b>：档位值域由库侧约束兜底（Java 侧规范集只是第一道）。
 *       约束没建上 = 快照与补丁两条路径都没证过；约束建上了但值域写错 =
 *       合法档位被拒、审计丢失。两条都要在真库里问一句。</li>
 * </ol>
 *
 * <p>存量库经 {@code deploy/sql/platform/patch_audit_ticket_scope.sql} 升上来的形状
 * 是否与快照一致，不由本类证明（那是
 * {@code scripts/platform-audit-ticket-scope-retrofit-check.sh} 用 pg_dump 差分做的事）；
 * 本类只证明<b>这个形状上的读写是对的</b>。
 *
 * <p>跑法（与其余 IT 同源）：{@code IT_PG_URL=jdbc:postgresql://127.0.0.1:5433/agent_it
 * IT_PG_USER=app_agent ./scripts/dev-it-db.sh && mvn -pl agent-infrastructure
 * -Dtest=PlatformAuditTicketScopeIT test}。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class PlatformAuditTicketScopeIT {

    /** 本类专用动作名：查询按它收窄，不与其他 IT 的残留行互相干扰。 */
    private static final String ACTION = "it.u8.ticket_scope";

    private static final UUID ACTOR = UUID.randomUUID();

    private static HikariDataSource pool;
    private static JdbcPlatformAuditRepository audit;

    @BeforeAll
    static void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        // ⚠️⚠️ **必须用平台运维凭据**（IT_PG_ADMIN_*），不能用应用凭据（IT_PG_USER）：
        //    本类操作的是 `platform.platform_audit_event` —— **平台域**对象，
        //    按设计「平台级对象走带外运维通道」，应用角色（app_agent）对
        //    platform schema **刻意没有** USAGE（见 09_platform_schema_grants.sql 的说明）。
        //    用应用账号连接会得到与业务无关的
        //    `permission denied for schema platform`（平台审计通道其实是断的）。
        String adminUser = System.getenv("IT_PG_ADMIN_USER");
        String appUser = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        cfg.setUsername(adminUser == null || adminUser.isBlank() ? appUser : adminUser);
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        String pwd = adminPwd == null || adminPwd.isBlank()
                ? System.getenv("IT_PG_PASSWORD") : adminPwd;
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(4);
        pool = new HikariDataSource(cfg);
        audit = new JdbcPlatformAuditRepository(pool);
    }

    /** ⚠️ 必须关池：否则全量构建时各 IT 累积的连接会打满 max_connections，
     *  把失败现场推到<b>别的模块</b>的 Spring 上下文加载上（本项目实测踩过）。 */
    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private static PlatformAuditEvent event(String decision, String reasonCode, String scope) {
        return new PlatformAuditEvent(UUID.randomUUID(), ACTOR.toString(), "PLATFORM_USER",
                ACTION, "rag_governance_ticket", null, decision, reasonCode,
                null, "10.0.0.7", Instant.now(), scope);
    }

    private static List<PlatformAuditEvent> readBack() {
        return audit.query(new PlatformAuditQuery(null, ACTION, null, null,
                null, null, 200, null));
    }

    @Test
    @DisplayName("档位写在列上，不在 reason_code 里：ALLOW 行读回 scope=PLATFORM 且 reason 仍是 jti")
    void allowRowCarriesItsOwnTier() {
        String jti = UUID.randomUUID().toString();
        audit.record(event("ALLOW", jti, "PLATFORM"));

        PlatformAuditEvent row = readBack().stream()
                .filter(e -> e.decision().equals("ALLOW")).findFirst().orElseThrow();
        assertThat(row.ticketScope()).isEqualTo("PLATFORM");
        // reason_code 没被档位挤掉：它仍然承载「这张票的 jti」，泄露的票才能对回签发记录。
        assertThat(row.reasonCode()).isEqualTo(jti);
    }

    @Test
    @DisplayName("⚠️ 全字段逐一对账：按位绑定的列清单漏改一处会整行串列，只有往返能发现")
    void wholeRowSurvivesTheRoundTrip() {
        String targetId = UUID.randomUUID().toString();
        UUID correlation = UUID.randomUUID();
        PlatformAuditEvent written = new PlatformAuditEvent(
                UUID.randomUUID(), ACTOR.toString(), "PLATFORM_USER", ACTION,
                "rag_governance_ticket", targetId, "DENY", "SCOPE_NOT_MINTABLE",
                correlation, "10.0.0.8", Instant.now(), "ALL");
        audit.record(written);

        PlatformAuditEvent row = readBack().stream()
                .filter(e -> e.id().equals(written.id())).findFirst().orElseThrow();
        assertThat(row)
                .extracting(PlatformAuditEvent::actorId, PlatformAuditEvent::actorType,
                        PlatformAuditEvent::action, PlatformAuditEvent::targetType,
                        PlatformAuditEvent::targetId, PlatformAuditEvent::decision,
                        PlatformAuditEvent::reasonCode, PlatformAuditEvent::ip,
                        PlatformAuditEvent::ticketScope)
                .containsExactly(ACTOR.toString(), "PLATFORM_USER", ACTION,
                        "rag_governance_ticket", targetId, "DENY",
                        "SCOPE_NOT_MINTABLE", "10.0.0.8", "ALL");
        assertThat(row.correlationId()).isEqualTo(correlation);
        assertThat(row.occurredAt()).isCloseTo(written.occurredAt(), within(1, ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("非票据行为 NULL：NULL 是多数行的常态，不是数据缺失")
    void nonTicketRowReadsBackNull() {
        audit.record(event("ALLOW", null, null));

        assertThat(readBack()).anyMatch(e -> e.decision().equals("ALLOW")
                && e.reasonCode() == null && e.ticketScope() == null);
    }

    @Test
    @DisplayName("三档都能落：值域是 PLATFORM/TENANT/ALL，与 RAG 侧 RagCorpusScope 同源")
    void allThreeTiersAreStorable() {
        for (String scope : List.of("PLATFORM", "TENANT", "ALL")) {
            audit.record(event("DENY", "RATE_LIMITED", scope));
        }
        assertThat(readBack()).extracting(PlatformAuditEvent::ticketScope)
                .contains("PLATFORM", "TENANT", "ALL");
    }

    @Test
    @DisplayName("⚠️ 库侧 CHECK 拒非规范值：这是「未校验文本不许进列」的最后一道兜底")
    void checkConstraintRefusesUnvalidatedText() {
        // 绕过 Java 侧规范集，直接用库的口径验证约束确实建上了、且值域就是那三档。
        // 两个样本各有指向：小写证明值域是精确匹配（所以闸门要先规范化大小写），
        // 带引号的那个证明值是**以数据身份**进约束的——约束拦的是取值，不是拦拼接。
        for (String bad : List.of("platform", "PLATFORM', 'x")) {
            assertThatThrownBy(() -> insertRaw(bad))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ticket_scope");
        }
    }

    @Test
    @DisplayName("列与约束都在目录里：形状缺失时上面的断言会假绿，这里直接问 pg_catalog")
    void columnAndConstraintExist() throws Exception {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT (SELECT count(*) FROM information_schema.columns
                              WHERE table_schema='platform'
                                AND table_name='platform_audit_event'
                                AND column_name='ticket_scope') AS col,
                            (SELECT count(*) FROM pg_constraint
                              WHERE conname='platform_audit_event_ticket_scope_ck') AS ck
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("col")).as("ticket_scope 列").isEqualTo(1);
                assertThat(rs.getLong("ck")).as("CHECK 约束").isEqualTo(1);
            }
        }
    }

    /** 原生 INSERT：用于验证「跳过 Java 规范集时库会怎么反应」。 */
    private static void insertRaw(String scope) throws SQLException {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO platform.platform_audit_event
                         (id, actor_id, actor_type, action, decision, occurred_at, ticket_scope)
                     VALUES (?::uuid, ?::uuid, 'PLATFORM_USER', ?, 'DENY', now(), ?)
                     """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, ACTOR.toString());
            ps.setString(3, ACTION);
            ps.setString(4, scope);
            ps.executeUpdate();
        }
    }
}
