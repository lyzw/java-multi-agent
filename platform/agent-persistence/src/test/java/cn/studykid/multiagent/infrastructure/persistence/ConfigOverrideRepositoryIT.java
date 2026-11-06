package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.application.config.ConfigTighteningPolicy;
import cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ConfigRelaxationException;
import cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ValueType;
import cn.studykid.multiagent.application.config.EffectiveConfigResolver;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.OverrideEntry;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.UnknownConfigKeyException;

/**
 * 配置覆盖仓储集成测试（P4-D15 / F6 · C2+C3+C4+C5）。
 *
 * <p><b>⚠️ 必须跑在真实 PG17 上</b>：RLS 隔离、ON CONFLICT 覆盖写、
 * 审计落库、CHECK 约束都依赖数据库特性，替身验证等于没验证。
 *
 * <p><b>⚠️ 用非特权角色 {@code app_agent}</b>：超级用户绕过 RLS，
 * 会得出虚假的隔离结论（D01 教训）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ConfigOverrideRepositoryIT {

    private static HikariDataSource appPool;
    private static ConfigOverridePort overrides;
    private static EffectiveConfigResolver resolver;
    private static String tenantA;
    private static String tenantB;

    /** 合法的长理由（C7 要求 ≥10 字）。 */
    private static final String REASON = "试点期收紧步数上限以控制成本";

    /**
     * 关闭应用连接池（W-PERM-P0 阶段实测修正）。
     *
     * <p>⚠️ <b>必须关闭</b>：本类原先建池不关。全量构建时各 IT 类累积的
     * 连接会打满 PostgreSQL 的 {@code max_connections}（默认 100），
     * 导致<b>后续模块</b>（platform-bootstrap）的 Spring 上下文加载以
     * {@code FATAL: sorry, too many clients already} 失败 ——
     * 失败现场在别的模块，报错点离真因（本类泄漏了连接）极远，
     * 极易被误判成「装配被改坏了」。
     */
    @AfterAll
    static void closeAppPool() {
        if (appPool != null) {
            appPool.close();
            appPool = null;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv("IT_PG_PASSWORD");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(4);
        appPool = new HikariDataSource(cfg);

        AuditPort audit = new JdbcAuditRepository(appPool);
        overrides = new JdbcConfigOverrideRepository(appPool, audit);
        resolver = new EffectiveConfigResolver(overrides);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-b'");
    }

    @BeforeEach
    void cleanOverrides() {
        // ⚠️ 每例前清空：否则上一例的覆盖会作为「当前值」影响棘轮判定，
        //    让本应通过的收紧写入被拒（用例间相互污染）。
        adminExec("DELETE FROM tenant_config_override WHERE tenant_id = '"
                + tenantA + "'::uuid OR tenant_id = '" + tenantB + "'::uuid");
    }

    // ── D15-1：写入后生效值与来源 ──────────────────────────────────

    @Test
    @DisplayName("✅ D15-1 写入覆盖后，生效值 = 覆盖值且来源标记 db_override")
    void applyThenEffective() {
        overrides.apply(tenantA, "agent.run.max-steps", "12", REASON, "admin-t-a");

        EffectiveConfigResolver.Effective eff = resolver.resolve(tenantA,
                Map.of("agent.run.max-steps", "20"));
        assertThat(eff.get("agent.run.max-steps")).isEqualTo("12");
        assertThat(eff.values().get("agent.run.max-steps").source()).isEqualTo("db_override");
    }

    @Test
    @DisplayName("✅ D15-1b 无覆盖时回落 yml 默认，来源标记 yml")
    void noOverrideFallsBackToYml() {
        EffectiveConfigResolver.Effective eff = resolver.resolve(tenantA,
                Map.of("agent.run.max-steps", "20"));
        assertThat(eff.get("agent.run.max-steps")).isEqualTo("20");
        assertThat(eff.values().get("agent.run.max-steps").source()).isEqualTo("yml");
    }

    // ── D15-3/D15-4：只能收紧（C5） ─────────────────────────────────

    @Test
    @DisplayName("✅ D15-3 上限类只能变小：写更大值 → 409 语义的放松异常")
    void upperBoundRejectsLoosening() {
        overrides.apply(tenantA, "agent.run.max-steps", "10", REASON, "admin-t-a");

        assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                "agent.run.max-steps", "30", "10", REASON, ValueType.INTEGER))
                .as("⛔ 棘轮失效：当前 10 时写 30 竟被放行")
                .isInstanceOf(ConfigRelaxationException.class);
    }

    @Test
    @DisplayName("✅ D15-3b 收紧方向放行")
    void upperBoundAllowsTightening() {
        overrides.apply(tenantA, "agent.run.max-steps", "10", REASON, "admin-t-a");
        ConfigTighteningPolicy.validate(
                "agent.run.max-steps", "5", "10", REASON, ValueType.INTEGER);
    }

    @Test
    @DisplayName("✅ D15-4 审批矩阵只能按等级收紧：放松某等级 → 拒绝")
    void approvalMatrixRejectsRelaxation() {
        String current = "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED";
        assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                "agent.approval.required-by-risk",
                "READ=NOT_REQUIRED,WRITE_EXTERNAL=REQUIRED",
                current, REASON, ValueType.ENUM))
                .as("⛔ 棘轮失效：READ 从 REQUIRED 放松到 NOT_REQUIRED 竟被放行")
                .isInstanceOf(ConfigRelaxationException.class);
    }

    @Test
    @DisplayName("✅ D15-4b 审批矩阵收紧方向放行（含部分等级）")
    void approvalMatrixAllowsTightening() {
        String current = "READ=NOT_REQUIRED,WRITE_EXTERNAL=REQUIRED";
        ConfigTighteningPolicy.validate("agent.approval.required-by-risk",
                "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED", current, REASON, ValueType.ENUM);
    }

    @Test
    @DisplayName("✅ D15-4d 当前值带 yml map 花括号形态时仍可解析（回归）")
    void approvalMatrixParsesCurlyBraceForm() {
        // ⚠️ **本用例锁住一个实测踩到的真实缺陷**：当前生效值来自
        //    ConfigReadService.declaredValueOf()，它对嵌套 yml map 返回
        //    Java Map.toString()，形如 "{READ=REQUIRED, WRITE_EXTERNAL=REQUIRED}"。
        //    不剥花括号时第一段被解析成 "{READ" → 抛「风险等级非法」→ 端点 400，
        //    导致**根本无法对审批矩阵做任何收紧写入**。
        String ymlForm = "{READ=NOT_REQUIRED, WRITE_EXTERNAL=REQUIRED}";
        // ⚠️ 本例只断言**能解析并放行**（NOT_REQUIRED → REQUIRED 是收紧方向）。
        //    若不剥花括号，这里会抛「风险等级非法」→ 用例红。
        //    放松方向的反证由 approvalMatrixRejectsRelaxation 单独覆盖。
        ConfigTighteningPolicy.validate("agent.approval.required-by-risk",
                "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED",
                ymlForm, REASON, ValueType.ENUM);

        // ⛔ 反向：当前值已是 REQUIRED 时，试图写回 NOT_REQUIRED 必须被拒
        //    （证明解析结果真实参与比较，而非「解析失败被当成合法」）。
        //    ⚠️ 当前值必须**比新值更严**，否则两者相同不构成「放松」——
        //    这是第一次写这用例时用错的前提（拿相同的值去验证放松）。
        String strictForm = "{READ=REQUIRED, WRITE_EXTERNAL=REQUIRED}";
        assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                "agent.approval.required-by-risk",
                "READ=NOT_REQUIRED,WRITE_EXTERNAL=REQUIRED",
                strictForm, REASON, ValueType.ENUM))
                .as("⛔ 花括号形态下棘轮未生效 = 可绕过收紧限制")
                .isInstanceOf(ConfigRelaxationException.class);
    }

    @Test
    @DisplayName("✅ D15-4c 棘轮基准是**当前生效值**而非 yml 默认")
    void ratchetUsesEffectiveNotYmlDefault() {
        // ⚠️ 本用例锁住 P4-D15 §3.2 的关键设计：棘轮比较基准。
        //    若拿 yml 默认（20）当基准，收紧一次到 10 后，
        //    再次写入 15 会被放行（15 < 20）——等于允许反弹。
        overrides.apply(tenantA, "agent.run.max-steps", "10", REASON, "admin-t-a");
        String current = resolver.resolve(tenantA,
                Map.of("agent.run.max-steps", "20")).get("agent.run.max-steps");
        assertThat(current).as("当前生效值应是覆盖 10 而非 yml 20").isEqualTo("10");

        assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                "agent.run.max-steps", "15", current, REASON, ValueType.INTEGER))
                .as("⛔ 允许从 10 反弹到 15 = 棘轮可被绕过")
                .isInstanceOf(ConfigRelaxationException.class);
    }

    // ── D15-5：CHG 理由强制（C7） ───────────────────────────────────

    @Test
    @DisplayName("✅ D15-5 理由不足 10 字 → 400 语义的参数异常")
    void reasonTooShortRejected() {
        assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                "agent.run.max-steps", "12", "20", "太贵", ValueType.INTEGER))
                .as("⛔ 理由门槛失效")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ D15-5b 数据库层也拒空理由（应用层校验不是唯一防线）")
    void databaseRejectsEmptyReason() {
        // ⚠️ 绕过应用层直接写库：V11 的 reason CHECK 必须挡住。
        //    应用层被误改/被绕过时，这道防线是最后一道。
        assertThatThrownBy(() -> adminExec(
                "INSERT INTO tenant_config_override "
                        + "(tenant_id, config_key, value_text, value_type, reason, changed_by) "
                        + "VALUES ('" + tenantA + "'::uuid, 'agent.run.max-steps', "
                        + "'12', 'INTEGER', '', 'x' )"))
                .as("⛔ DB 层未拒空理由 = 审计缺失时配置仍能被改")
                .hasMessageContaining("reason");
    }

    // ── D15-6：审计连通性（C4） ─────────────────────────────────────

    @Test
    @DisplayName("✅ D15-6 审计真落 audit_record（CONFIG_UPDATE）")
    void auditActuallyWrites() {
        overrides.apply(tenantA, "agent.run.max-steps", "12", REASON, "admin-t-a");
        long n = adminQueryLong("SELECT count(*) FROM audit_record "
                + "WHERE tenant_id = '" + tenantA + "'::uuid "
                + "AND action = '" + AuditPort.Actions.CONFIG_UPDATE + "' "
                + "AND resource_id = 'agent.run.max-steps'");
        assertThat(n).as("⛔ 配置变更未落审计 = 无声变更").isGreaterThanOrEqualTo(1L);
    }

    // ── D15-7：跨租户隔离 ──────────────────────────────────────────

    @Test
    @DisplayName("✅ D15-7 跨租户不可见：B 的覆盖列表里不得出现 A 的任何键值")
    void crossTenantIsolation() {
        overrides.apply(tenantA, "agent.run.max-steps", "12", REASON, "admin-t-a");

        Map<String, OverrideEntry> seenByB = overrides.overridesFor(tenantB);
        assertThat(seenByB).as("⛔ B 看到了 A 的配置覆盖 = RLS 失效").isEmpty();

        // ⚠️ 不只断言「B 看不到 A 的**值**」，还要断言「B 看不到 A 的**任何行**」
        //    ——只查值相同的话，两边都写 12 时会误判为通过（台账 §3.3 教训）。
        long rowsVisibleToB = appQueryAsTenant(tenantB,
                "SELECT count(*) FROM tenant_config_override");
        assertThat(rowsVisibleToB).isZero();
    }

    // ── D15-8：清除覆盖回落默认 ─────────────────────────────────────

    @Test
    @DisplayName("✅ D15-8 清除覆盖 → 回落 yml 默认")
    void clearFallsBackToYml() {
        overrides.apply(tenantA, "agent.run.max-steps", "12", REASON, "admin-t-a");
        overrides.clear(tenantA, "agent.run.max-steps", "admin-t-a");

        EffectiveConfigResolver.Effective eff = resolver.resolve(tenantA,
                Map.of("agent.run.max-steps", "20"));
        assertThat(eff.get("agent.run.max-steps")).isEqualTo("20");
        assertThat(eff.values().get("agent.run.max-steps").source()).isEqualTo("yml");
    }

    // ── 不可写键 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ L1 锁定键与未知键均不可写（白名单语义）")
    void nonWritableKeysRejected() {
        for (String key : new String[]{
                "agent.tool.risk-level-map",     // L1 锁定：代码事实
                "agent.approval.approver-scope", // L1 锁定：DEC-6
                "auth.jwt.private-key",          // 敏感：绝不暴露写入口
                "totally.made.up"}) {
            assertThatThrownBy(() -> overrides.apply(tenantA, key, "x", REASON, "admin"))
                    .as("⛔ 不可写键 " + key + " 竟可写入")
                    .isInstanceOf(UnknownConfigKeyException.class);
        }
    }

    // ── 类型映射一致性 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ 落库类型与校验类型一致（避免写入成功但解析出不同值）")
    void storedTypeMatchesValidationType() {
        OverrideEntry e = overrides.apply(
                tenantA, "agent.approval.ttl", "10m", REASON, "admin-t-a");
        assertThat(e.valueType()).isEqualTo("DURATION");

        OverrideEntry c = overrides.apply(
                tenantA, "agent.run.max-cost-per-run", "1.50", REASON, "admin-t-a");
        assertThat(c.valueType()).isEqualTo("DECIMAL");

        OverrideEntry i = overrides.apply(
                tenantA, "agent.run.max-steps", "15", REASON, "admin-t-a");
        assertThat(i.valueType()).isEqualTo("INTEGER");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static String adminQuery(String sql) {
        try (HikariDataSource adminPool = new HikariDataSource(adminConfig());
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).as("查询应返回一行: " + sql).isTrue();
            return rs.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("管理员查询失败: " + sql, e);
        }
    }

    private static long adminQueryLong(String sql) {
        try (HikariDataSource adminPool = new HikariDataSource(adminConfig());
             Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).as("查询应返回一行: " + sql).isTrue();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("管理员查询失败: " + sql, e);
        }
    }

    /**
     * 以指定租户上下文查行数，<b>验证 RLS 对非特权连接的真实效果</b>。
     *
     * <p>⚠️ <b>刻意复用 appPool（app_agent）而非管理连接</b>：
     * 首次实现用的是管理连接，实测得到「B 能看到 1 行」而误判为 RLS 失效。
     * 真相是管理角色 {@code agent} 带 {@code Superuser, Bypass RLS}——
     * <b>绕过了 RLS</b>，所以看到全部行恰恰证明查询<b>没有</b>经过 RLS 路径。
     * 用错连接会让隔离测试永远为红（或永远为绿），两种都无意义。
     */
    private static long appQueryAsTenant(String tenantId, String sql) {
        try (Connection conn = appPool.getConnection()) {
            try (var st = conn.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("按租户查询失败: " + sql, e);
        }
    }

    private static void adminExec(String sql) {
        try (HikariDataSource p = new HikariDataSource(adminConfig());
             Connection conn = p.getConnection();
             var st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("管理员写入失败: " + sql, e);
        }
    }

    private static HikariConfig adminConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        // ⚠️ 管理员池（造跨租户夹具）：本地 trust 认证无需口令；
        // 密口认证的库（RDS / CI）必须显式给 IT_PG_ADMIN_PASSWORD，
        // 否则连接失败（错误作用域级隔离可能被误读为「失效」）。
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            cfg.setPassword(adminPwd);
        }
        cfg.setMaximumPoolSize(1);
        return cfg;
    }
}
