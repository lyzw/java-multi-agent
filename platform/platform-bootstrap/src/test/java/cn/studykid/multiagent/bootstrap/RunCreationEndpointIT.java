package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;

/**
 * Run 创建端点<b>成功路径</b>的端到端验证（P4-D18 回归测试）。
 *
 * <p><b>⚠️ 为什么这个文件必须存在</b>：
 * 修复前 {@code POST /api/runs} <b>恒返回 500</b>——
 * {@code CurrentPrincipal.snapshotId()} 返回随机 UUID，
 * 而 {@code run.snapshot_id} 有外键 {@code run_snapshot_fk → execution_snapshot}，
 * 于是每次插入都必然失败。
 *
 * <p><b>而整个测试套件当时是全绿的</b>，原因有二，且都与本文件要补的缺口同源：
 * <ol>
 *   <li>{@code PlatformSecurityE2ETest} 的 E2E-2 只断言「状态<b>不得</b>是 401」，
 *       并在注释里明确写着「业务层会失败」——它<b>主动接受</b>了 500；</li>
 *   <li><b>没有任何 IT 断言过建 Run 返回 2xx</b>。</li>
 * </ol>
 * 这正是本项目反复记录的 F-14 / CP-1 / CP-2 形态的另一变种：
 * <b>「绿但功能不存在」</b>。本类存在的意义就是把「成功」变成一个
 * <b>可证伪的断言</b>。
 *
 * <p><b>⛔ 不只断言状态码</b>：每个成功用例都回查 {@code run} 表，
 * 确认行真的落库且 {@code snapshot_id} 等于期望值。
 * 否则「返回 201 但什么都没写」也会被当作通过（D01 连通性教训）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("P4-D18 Run 创建端点成功路径（修复「恒 500」）")
class RunCreationEndpointIT {

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    private HikariDataSource adminPool;
    private String tenantA;
    private final List<String> createdRunIds = new java.util.ArrayList<>();

    // ── 夹具 ────────────────────────────────────────────────────────

    private synchronized DataSource admin() {
        if (adminPool == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
            cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
            String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
            if (adminPwd != null && !adminPwd.isBlank()) {
                cfg.setPassword(adminPwd);
            }
            cfg.setMaximumPoolSize(2);
            adminPool = new HikariDataSource(cfg);
        }
        return adminPool;
    }

    private synchronized String tenant() {
        if (tenantA == null) {
            tenantA = query("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        }
        return tenantA;
    }

    private String auth() {
        return "Bearer " + tokens.issue(
                new TenantContext(tenant(), "it-admin", Set.of("TENANT_ADMIN"), 1L),
                Instant.now());
    }

    private String query(String sql) {
        try (Connection c = admin().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("应有结果: " + sql).isTrue();
            return rs.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    private void exec(String sql) {
        try (Connection c = admin().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入失败: " + sql, e);
        }
    }

    /**
     * 写入并回读生成的主键。
     *
     * <p>⚠️ <b>不得自己造 {@code agent_version.version_id}</b>：该列由数据库生成
     * （{@code DEFAULT gen_random_uuid()}）。自造一个 UUID 插入后，
     * 随后的 {@code execution_snapshot} 外键会报
     * 「version 不存在」——而错误信息指向的是快照表，
     * 与真正的成因（版本行没落上）<b>完全不同</b>（排查方向被带偏，实测踩过）。
     */
    private String execReturning(String sql) {
        try (Connection c = admin().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("RETURNING 应有一行: " + sql).isTrue();
                return rs.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入失败: " + sql, e);
        }
    }

    /**
     * 造一个「已发布 + 有快照」的 Agent。
     *
     * <p>⚠️ <b>三样都要</b>：缺任何一样都会让建 Run 失败，且失败原因各不相同
     * ——这正是把三态分别断言成 404 的原因。
     */
    private Fixture publishedAgent() {
        String agentId = UUID.randomUUID().toString();
        exec("""
                INSERT INTO agent (agent_id, tenant_id, name, description, created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, 'rc-%s', 'seeded', now(), now())
                """.formatted(agentId, tenant(), agentId));

        String versionId = execReturning("""
                INSERT INTO agent_version
                    (tenant_id, agent_id, version_number, definition, published_at, created_at)
                VALUES ('%s'::uuid, '%s'::uuid, 1, '{}'::jsonb, now(), now())
                RETURNING version_id::text
                """.formatted(tenant(), agentId));

        // ⚠️ 回读的是 snapshot_id（execution_snapshot 的主键），
        //    而非 version_id —— 外键 run_snapshot_fk 引的是前者。
        String snapshotId = execReturning("""
                INSERT INTO execution_snapshot
                    (tenant_id, version_id, agent_config, prompt_config,
                     tool_contracts, run_config, created_at)
                VALUES ('%s'::uuid, '%s'::uuid, '{}', '{}', '{}',
                        '{"maxCostPerRun":"2.00","maxLlmCalls":12,"maxSteps":20,
                          "approvalRequired":"OPTIONAL"}'::jsonb, now())
                RETURNING snapshot_id::text
                """.formatted(tenant(), versionId));

        return new Fixture(agentId, snapshotId);
    }

    /** 造一个「已发布但<b>无快照</b>」的 Agent —— 第三个必测状态。 */
    private Fixture publishedButNoSnapshotAgent() {
        String agentId = UUID.randomUUID().toString();
        exec("""
                INSERT INTO agent (agent_id, tenant_id, name, description, created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, 'rc-ns-%s', 'seeded', now(), now())
                """.formatted(agentId, tenant(), agentId));
        exec("""
                INSERT INTO agent_version
                    (tenant_id, agent_id, version_number, definition, published_at, created_at)
                VALUES ('%s'::uuid, '%s'::uuid, 1, '{}'::jsonb, now(), now())
                """.formatted(tenant(), agentId));
        return new Fixture(agentId, null);
    }

    /** 造一个「有版本但<b>未发布</b>」的 Agent —— 第二个必测状态。 */
    private Fixture unpublishedAgent() {
        String agentId = UUID.randomUUID().toString();
        exec("""
                INSERT INTO agent (agent_id, tenant_id, name, description, created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, 'rc-d-%s', 'seeded', now(), now())
                """.formatted(agentId, tenant(), agentId));
        String versionId = execReturning("""
                INSERT INTO agent_version
                    (tenant_id, agent_id, version_number, definition, published_at, created_at)
                VALUES ('%s'::uuid, '%s'::uuid, 1, '{}'::jsonb, NULL, now())
                RETURNING version_id::text
                """.formatted(tenant(), agentId));
        exec("""
                INSERT INTO execution_snapshot
                    (tenant_id, version_id, agent_config, prompt_config,
                     tool_contracts, run_config, created_at)
                VALUES ('%s'::uuid, '%s'::uuid, '{}', '{}', '{}', '{}'::jsonb, now())
                """.formatted(tenant(), versionId));
        return new Fixture(agentId, versionId);
    }

    private String body(String agentId, String idempotencyKey) {
        return """
                {"idempotencyKey":"%s","agentId":"%s","input":{"question":"解方程 x^2-5x+6=0"}}
                """.formatted(idempotencyKey, agentId);
    }

    private String createRun(String agentId, String key, int expectedStatus) {
        var result = rest.post().uri("/api/runs")
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(agentId, key))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(String.class)
                .returnResult();
        return result.getResponseBody() == null ? "" : result.getResponseBody();
    }

    private long runCount(String idempotencyKey) {
        return Long.parseLong(query("SELECT count(*)::text FROM run WHERE idempotency_key = '"
                + idempotencyKey + "'"));
    }

    @AfterEach
    void cleanUpRuns() {
        // ⚠️ 只清本类建的 Run（子表均 ON DELETE CASCADE，见 V1/V2/V13）。
        //    残留会占满 claimTasks 的 LIMIT 1000 窗口，
        //    让后来者报「任务未被领取」——即 F-9 的污染形态。
        for (String runId : createdRunIds) {
            exec("DELETE FROM run WHERE run_id = '" + runId + "'::uuid");
        }
        createdRunIds.clear();
    }

    private void remember(String runId) {
        if (runId != null && !runId.isBlank()) {
            createdRunIds.add(runId);
        }
    }

    // ── 用例 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ P4-D18-1 已发布 Agent 建 Run → 201，且 run 行真落库、snapshot_id 正确")
    void publishedAgentReturns201AndPersists() {
        Fixture f = publishedAgent();
        String key = UUID.randomUUID().toString();

        String response = createRun(f.agentId(), key, 201);
        assertThat(response)
                .as("⛔ 这是修复前恒 500 的那条路径：必须真的成功")
                .contains("\"replayed\":false");

        String runId = jsonString(response, "runId");
        assertThat(runId).as("响应必须带 runId").isNotBlank();
        remember(runId);

        // ⛔ 连通性：不止断言状态码，还要确认行真的写进去了
        assertThat(runCount(key))
                .as("⚠️ 返回 201 却没落库 = 「看起来通了」")
                .isEqualTo(1L);

        String snapshotId = query("SELECT snapshot_id::text FROM run WHERE run_id = '"
                + runId + "'::uuid");
        assertThat(snapshotId)
                .as("⚠️ run.snapshot_id 必须等于该 Agent 已发布版本的**快照主键**"
                        + "（execution_snapshot.snapshot_id，而非 version_id）")
                .as("绝不可能是随机 UUID——那正是原缺陷")
                .isEqualTo(f.versionId());
    }

    @Test
    @DisplayName("✅ P4-D18-2 同 idempotencyKey 重放 → 200 + 同一个 runId，且不新增行")
    void replayReturns200SameRunId() {
        Fixture f = publishedAgent();
        String key = UUID.randomUUID().toString();

        String first = createRun(f.agentId(), key, 201);
        remember(jsonString(first, "runId"));
        String second = createRun(f.agentId(), key, 200);

        assertThat(second).contains("\"replayed\":true");
        assertThat(jsonString(second, "runId"))
                .as("重放必须返回首次的 runId，否则调用方无法追踪同一次执行")
                .isEqualTo(jsonString(first, "runId"));
        assertThat(runCount(key)).as("重放不得新增行").isEqualTo(1L);
    }

    @Test
    @DisplayName("⛔ P4-D18-3 已发布但无快照 → 404 AGENT_SNAPSHOT_UNAVAILABLE（非 500）")
    void publishedWithoutSnapshotReturns404() {
        Fixture f = publishedButNoSnapshotAgent();
        String key = UUID.randomUUID().toString();

        String response = createRun(f.agentId(), key, 404);
        assertThat(response)
                .as("⚠️ 客户端引用了不可用资源 → 4xx。「服务暂时不可用」会诱导无限重试")
                .contains("AGENT_SNAPSHOT_UNAVAILABLE");
        assertThat(runCount(key)).as("失败不得留下半成品行").isZero();
    }

    @Test
    @DisplayName("⛔ P4-D18-4 版本未发布 → 404（草稿不该被 Run 使用）")
    void unpublishedAgentReturns404() {
        Fixture f = unpublishedAgent();
        String key = UUID.randomUUID().toString();

        String response = createRun(f.agentId(), key, 404);
        assertThat(response).contains("AGENT_SNAPSHOT_UNAVAILABLE");
        assertThat(runCount(key)).isZero();
    }

    @Test
    @DisplayName("⛔ P4-D18-5 不存在的 agentId → 404（非 500）")
    void unknownAgentReturns404() {
        String key = UUID.randomUUID().toString();
        String response = createRun(UUID.randomUUID().toString(), key, 404);
        assertThat(response).contains("AGENT_SNAPSHOT_UNAVAILABLE");
        assertThat(runCount(key)).isZero();
    }

    @Test
    @DisplayName("⛔ P4-D18-6 agentId 非 UUID → 404（非 500）")
    void nonUuidAgentIdReturns404() {
        // ⚠️ 这条曾经会以 PSQLException(22P02) 冒泡 → 兜底处理器 → 500：
        //    客户端一个明显的输入错误被报成服务端故障。
        String key = UUID.randomUUID().toString();
        String response = createRun("not-a-uuid", key, 404);
        assertThat(response).contains("AGENT_SNAPSHOT_UNAVAILABLE");
        assertThat(runCount(key)).isZero();
    }

    @Test
    @DisplayName("✅ P4-D18-7 护栏：CurrentPrincipal 不得再暴露 snapshotId()")
    void snapshotResolverNeverReturnsRandomUuid() {
        // ⚠️ 本条锁定「缺陷不会被重新引入」：
        //    只要 CurrentPrincipal 里不再有 snapshotId()，
        //    就不可能再有「随机 UUID 写进外键列」这种写法。
        var methods = java.util.Arrays.stream(
                        cn.studykid.multiagent.api.run.RunController.CurrentPrincipal.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();
        assertThat(methods)
                .as("⛔ CurrentPrincipal.snapshotId() 必须保持删除状态："
                        + "它曾返回随机 UUID，使 POST /api/runs 恒 500")
                .doesNotContain("snapshotId");
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 夹具：Agent 与其执行快照（{@code versionId} 实际存的是快照主键）。 */
    private record Fixture(String agentId, String versionId) {
    }

    private static String jsonString(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i);
        int start = json.indexOf('"', colon + 1);
        int end = json.indexOf('"', start + 1);
        return start < 0 || end < 0 ? "" : json.substring(start + 1, end);
    }
}