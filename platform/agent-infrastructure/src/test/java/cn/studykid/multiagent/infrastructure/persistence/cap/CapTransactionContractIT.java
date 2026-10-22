package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.infrastructure.persistence.cap.JdbcAuthzVersionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务契约集成测试（W-PERM-P0-T8 · 06 §1.5 第 4 条）。
 *
 * <p>锁住「<b>版本行插入 + authzVersion 自增 + RLS 绑定</b> 同事务」这一契约：
 * 三者必须在同一个事务里，要么都可见、要么都不可见。
 *
 * <p>⚠️ <b>为什么这是 P0 的硬契约</b>：撤权与版本递增若不在同一事务，
 * 会出现「已撤权但版本未变」的中间态 —— 持有旧版本的上下文仍然通过校验，
 * 撤权<b>静默失效</b>。这类缺陷在功能测试里完全看不出来，
 * 只有把三件事放进同一个事务显式验证才能兜住。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class CapTransactionContractIT extends CapITBase {

    /**
     * 在同一租户事务内完成：插入版本行 → 自增 authzVersion → 回读校验。
     *
     * @return 事务内回读到的授权版本
     */
    private long versionInsertAndBumpInOneTransaction(String tenantId, String agentId)
            throws SQLException {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var st = conn.createStatement()) {
                    // ⚠️ RLS 绑定必须在**任何业务 SQL 之前**完成，
                    //    且 is_local=true 让它在事务结束时自动失效。
                    st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, "
                                + "enabled_skills, overrides, config_fingerprint, created_at, "
                                + "updated_at, published_at) VALUES (?::uuid, ?::uuid, 1, "
                                + "'tx-agent', '[]'::jsonb, '{}'::jsonb, 'fp-tx', now(), now(), now())")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, agentId);
                    ps.executeUpdate();
                }
                // 同事务内自增授权版本
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version) "
                                + "VALUES (?::uuid, 'p-tx', 1) "
                                + "ON CONFLICT (tenant_id, principal_id) DO UPDATE "
                                + "SET authz_version = cap_authz_version.authz_version + 1")) {
                    ps.setString(1, tenantId);
                    ps.executeUpdate();
                }
                long version;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT authz_version FROM cap_authz_version "
                                + "WHERE tenant_id = ?::uuid AND principal_id = 'p-tx'")) {
                    ps.setString(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        version = rs.getLong(1);
                    }
                }
                conn.commit();
                return version;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败不掩盖原始异常
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Test
    @DisplayName("✅ 同事务：版本行插入 + authzVersion 自增 + RLS 回读全部成功")
    void versionInsertAndBumpAreAtomic() throws Exception {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-tx-agent"), "{}", true);

        long version = versionInsertAndBumpInOneTransaction(tenantA, agentId);
        assertThat(version).isEqualTo(1L);

        // 提交后：版本行与授权版本都可见
        long defRows = adminQueryLong("SELECT count(*) FROM cap_agent_def WHERE tenant_id = '"
                + tenantA + "'::uuid AND agent_id = '" + agentId + "'::uuid");
        assertThat(defRows).isEqualTo(1L);

        long authzRows = adminQueryLong("SELECT count(*) FROM cap_authz_version "
                + "WHERE tenant_id = '" + tenantA + "'::uuid AND principal_id = 'p-tx'");
        assertThat(authzRows).isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ 回滚：版本行与授权版本一并消失（不产生半成品）")
    void rollbackLeavesNothing() throws Exception {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-tx-rb-agent"), "{}", true);

        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var st = conn.createStatement()) {
                    st.execute("SELECT set_config('app.tenant_id', '" + tenantA + "', true)");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, "
                                + "enabled_skills, overrides, config_fingerprint, created_at, "
                                + "updated_at, published_at) VALUES (?::uuid, ?::uuid, 1, "
                                + "'rb-agent', '[]'::jsonb, '{}'::jsonb, 'fp-rb', now(), now(), now())")) {
                    ps.setString(1, tenantA);
                    ps.setString(2, agentId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version) "
                                + "VALUES (?::uuid, 'p-rb', 1)")) {
                    ps.setString(1, tenantA);
                    ps.executeUpdate();
                }
                conn.rollback();
            } finally {
                conn.setAutoCommit(true);
            }
        }

        long defRows = adminQueryLong("SELECT count(*) FROM cap_agent_def WHERE tenant_id = '"
                + tenantA + "'::uuid AND agent_id = '" + agentId + "'::uuid");
        assertThat(defRows).as("⛔ 回滚后仍留版本行：出现半成品状态").isZero();

        long authzRows = adminQueryLong("SELECT count(*) FROM cap_authz_version "
                + "WHERE tenant_id = '" + tenantA + "'::uuid AND principal_id = 'p-rb'");
        assertThat(authzRows).as("⛔ 回滚后仍留授权版本：撤权与版本递增必须同生共死").isZero();
    }

    @Test
    @DisplayName("✅ 同事务不泄漏：事务内写入对其他租户不可见")
    void sameTransactionDoesNotLeak() throws Exception {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-tx-iso-agent"), "{}", true);

        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var st = conn.createStatement()) {
                    st.execute("SELECT set_config('app.tenant_id', '" + tenantA + "', true)");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, "
                                + "enabled_skills, overrides, config_fingerprint, created_at, "
                                + "updated_at, published_at) VALUES (?::uuid, ?::uuid, 1, "
                                + "'iso-agent', '[]'::jsonb, '{}'::jsonb, 'fp-iso', now(), now(), now())")) {
                    ps.setString(1, tenantA);
                    ps.setString(2, agentId);
                    ps.executeUpdate();
                }
                // ⚠️ 事务**未提交**时，另一租户不得看到这行
                long visibleToB = appCountAsTenant(tenantB,
                        "SELECT count(*) FROM cap_agent_def WHERE agent_id = '" + agentId + "'");
                assertThat(visibleToB)
                        .as("⛔ 未提交的数据被其他租户看到：事务隔离或 RLS 失效")
                        .isZero();
                conn.commit();
            } finally {
                conn.setAutoCommit(true);
            }
        }

        // 提交后：仍然只有 A 可见
        long visibleToB = appCountAsTenant(tenantB,
                "SELECT count(*) FROM cap_agent_def WHERE agent_id = '" + agentId + "'");
        assertThat(visibleToB)
                .as("⛔ 提交后 B 仍能看到 A 的行 = RLS 失效")
                .isZero();

        long visibleToA = appCountAsTenant(tenantA,
                "SELECT count(*) FROM cap_agent_def WHERE agent_id = '" + agentId + "'");
        assertThat(visibleToA).isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ 仓储层的事务边界正确：bump 失败不留下半写状态")
    void repositoryBumpIsSelfContained() {
        JdbcAuthzVersionRepository repo = new JdbcAuthzVersionRepository(appPool);
        assertThat(repo.bump(tenantA, "p-self")).isEqualTo(1L);
        assertThat(repo.bump(tenantA, "p-self")).isEqualTo(2L);

        long rows = adminQueryLong("SELECT count(*) FROM cap_authz_version "
                + "WHERE tenant_id = '" + tenantA + "'::uuid AND principal_id = 'p-self'");
        assertThat(rows)
                .as("⛔ 原子自增产生了多行：版本记录应始终唯一")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ 已发布版本行不可变：DB 层拒绝改写")
    void publishedVersionRowIsImmutable() {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-imm-agent"), "{}", true);
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, enabled_skills, "
                + "overrides, config_fingerprint, created_at, updated_at, published_at) VALUES ('"
                + tenantA + "'::uuid, '" + agentId + "'::uuid, 1, 'imm', '[]'::jsonb, "
                + "'{}'::jsonb, 'fp-imm', now(), now(), now())");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adminExec(
                "UPDATE cap_agent_def SET name = 'tampered' WHERE tenant_id = '" + tenantA
                        + "'::uuid AND agent_id = '" + agentId + "'::uuid"))
                .as("⛔ 已发布版本行可被改写：改配置会退化为原地篡改，发布指针与内容将不一致")
                // ⚠️ 断言查**整条栈**而不是顶层消息：
                //    adminExec 把底层异常包成 IllegalStateException("管理员写入失败: <SQL>")，
                //    PostgreSQL 的原文在 cause 里 —— 查顶层消息会得到
                //    「功能正常但测试红」的假失败。
                .hasStackTraceContaining("不可变");
    }

    @Test
    @DisplayName("✅ 草稿版本行可编辑（草稿编辑不修改已发布版本行）")
    void draftVersionRowIsEditable() {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-draft-agent"), "{}", false);
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, enabled_skills, "
                + "overrides, config_fingerprint, created_at, updated_at) VALUES ('"
                + tenantA + "'::uuid, '" + agentId + "'::uuid, 1, 'draft', '[]'::jsonb, "
                + "'{}'::jsonb, 'fp-draft', now(), now())");

        adminExec("UPDATE cap_agent_def SET name = 'draft-v2' WHERE tenant_id = '" + tenantA
                + "'::uuid AND agent_id = '" + agentId + "'::uuid");

        String name = adminQuery("SELECT name FROM cap_agent_def WHERE tenant_id = '"
                + tenantA + "'::uuid AND agent_id = '" + agentId + "'::uuid");
        assertThat(name).isEqualTo("draft-v2");
    }

    @Test
    @DisplayName("✅ 主键冲突不覆盖：重复插入同版本返回 false")
    void duplicateVersionRowIsRejected() {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-dup-agent"), "{}", true);
        JdbcCapAgentDefRepository repo = new JdbcCapAgentDefRepository(appPool);
        java.time.Instant now = java.time.Instant.now();
        CapAgentDef def = new CapAgentDef(tenantA, agentId, 1, "dup", "d",
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), "{}", "fp-dup", 1, now, now);

        assertThat(repo.insert(def)).isTrue();
        assertThat(repo.insert(def))
                .as("⛔ 重复插入被当作更新：不可变版本行可被静默覆盖")
                .isFalse();
    }

    @Test
    @DisplayName("✅ 跨租户引用被复合外键挡住")
    void crossTenantReferenceRejected() {
        String agentIdB = UUID.randomUUID().toString();
        adminExec("INSERT INTO agent (agent_id, tenant_id, name) VALUES ('" + agentIdB
                + "'::uuid, '" + tenantB + "'::uuid, '" + uniqueName("cap-xt-agent") + "')");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adminExec(
                "INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, enabled_skills, "
                        + "overrides, config_fingerprint, created_at, updated_at) VALUES ('"
                        + tenantA + "'::uuid, '" + agentIdB + "'::uuid, 1, 'xt', '[]'::jsonb, "
                        + "'{}'::jsonb, 'fp-xt', now(), now())"))
                .as("⛔ 跨租户 agent 引用未被外键挡住")
                // ⚠️ 断言查**约束名**而不是英文词 "foreign key"：
                //    数据库的 lc_messages 可能被设为中文，错误文案里没有那个英文词，
                //    按文案断言会得到「功能正常但测试红」的假失败。
                .hasStackTraceContaining("cap_agent_def_agent_fk");
    }
}
