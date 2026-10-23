package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.ConfigPublishRejectedException;
import cn.studykid.multiagent.application.cap.ConfigPublishService;
import cn.studykid.multiagent.application.cap.ConfigValidationGate;
import cn.studykid.multiagent.application.cap.PlatformOpsPublishService;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigPublishResult;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ConfigRollbackCommand;
import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台运维角色（BYPASSRLS）接入点的<b>真实 PG</b> 集成测试（P6R1 · 上线阻塞项）。
 *
 * <p>⚠️ <b>本类回答的是「平台级发布凭什么能写进去」</b>：
 * {@code cap_config_release} / {@code cap_config_pointer} / {@code cap_decision_log}
 * 的写策略<b>只放行本租户的 TENANT 行</b>（V15/V17 注释）。
 * 在本类之前，生产环境<b>没有任何</b>能写 PLATFORM 行的凭据接入点 ——
 * 平台级发布在生产<b>不可用</b>。
 *
 * <p>⚠️ <b>为什么用「专用 BYPASSRLS 角色」而不是直接用管理角色</b>：
 * 管理角色在本项目是 <b>SUPERUSER</b>。超级用户绕过的是<b>一切</b>，
 * 用它验证「BYPASSRLS 足以写平台级行」会得到一个过强的结论 ——
 * 若实现里另有依赖超级用户特性的地方（如 {@code NO FORCE RLS} 的 owner 语义），
 * 用超级用户测出来的绿是<b>假的</b>。
 * 生产用的运维角色是 <b>NOSUPERUSER + BYPASSRLS</b>，
 * 本类用同形态的角色验证，并<b>断言角色属性</b>，
 * 使「测试用的角色形状」与「生产用的角色形状」不会悄悄漂移。
 *
 * <p>⚠️ <b>本类同时守住两条不可越过的边界</b>：
 * <ul>
 *   <li><b>租户角色仍然不能写平台级</b> —— 接入运维通道<b>不</b>放宽租户隔离；</li>
 *   <li><b>运维凭据只服务平台级</b> —— 用它发租户作用域的发布一律被拒。</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class PlatformOpsPublishIT extends CapITBase {

    /** 运维角色名（测试专用，形状与生产一致：NOSUPERUSER + BYPASSRLS）。 */
    private static final String OPS_ROLE = "platform_ops_it";

    /** 运维角色口令（测试专用，非真实凭据）。 */
    private static final String OPS_PASSWORD = "platform_ops_it_pw";

    private static final String TOOL_ID = "it-ops-tool";
    private static final String SKILL_ID = "it-ops-skill";
    private static final Clock CLOCK = Clock.systemUTC();

    /** 运维角色的连接池（真实 BYPASSRLS 连接）。 */
    private static HikariDataSource opsPool;

    // ── 夹具 ──────────────────────────────────────────────────────

    @BeforeAll
    static void initOpsPool() {
        // ⚠️ 幂等创建运维角色：由管理连接（超级用户）执行。
        //    CREATE ROLE 是集群级对象，幂等靠 DO 块判断 pg_roles。
        adminExec("DO $$ BEGIN "
                + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + OPS_ROLE + "') THEN "
                + "CREATE ROLE " + OPS_ROLE + " LOGIN NOSUPERUSER BYPASSRLS "
                + "NOCREATEDB NOCREATEROLE INHERIT PASSWORD '" + OPS_PASSWORD + "'; "
                + "END IF; "
                + "ALTER ROLE " + OPS_ROLE + " LOGIN NOSUPERUSER BYPASSRLS "
                + "NOCREATEROLE NOCREATEDB INHERIT PASSWORD '" + OPS_PASSWORD + "'; "
                + "END $$;");

        // ⚠️ 最小必要授权：与 app_role 脚本同构，但该角色带 BYPASSRLS。
        adminExec("GRANT USAGE ON SCHEMA public TO " + OPS_ROLE);
        adminExec("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                + OPS_ROLE);
        adminExec("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + OPS_ROLE);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(OPS_ROLE);
        cfg.setPassword(OPS_PASSWORD);
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("it-platform-ops-pool");
        opsPool = new HikariDataSource(cfg);
    }

    /**
     * 关闭运维连接池。
     *
     * <p>⚠️ <b>必须关闭</b>：与基座同理 —— 每个测试类多占 2 条连接，
     * 累积到 {@code max_connections} 之后，
     * <b>后续模块</b>会以 {@code FATAL: sorry, too many clients already} 失败，
     * 报错点离真因（本类泄漏了连接）极远。
     */
    @org.junit.jupiter.api.AfterAll
    static void closeOpsPool() {
        if (opsPool != null) {
            opsPool.close();
            opsPool = null;
        }
    }

    // ── 角色形状自证 ──────────────────────────────────────────────

    @Test
    @DisplayName("✅ 夹具自证：运维角色是 NOSUPERUSER + BYPASSRLS，应用角色两者皆无")
    void roleShapesAreVerified() {
        assertThat(adminQueryLong("SELECT count(*) FROM pg_roles WHERE rolname = '"
                + OPS_ROLE + "' AND rolbypassrls AND NOT rolsuper"))
                .as("⛔ 运维角色不是「非超级用户 + BYPASSRLS」："
                        + "超级用户绕过的是一切，用它验证会得到过强的结论")
                .isEqualTo(1L);

        assertThat(adminQueryLong("SELECT count(*) FROM pg_roles WHERE rolname = '"
                + System.getenv().getOrDefault("IT_PG_USER", "app_agent")
                + "' AND NOT rolbypassrls AND NOT rolsuper"))
                .as("⛔ 应用角色带 BYPASSRLS 或超级用户权限：RLS 验证全部失真")
                .isEqualTo(1L);
    }

    // ── 平台级发布 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 平台运维通道：PLATFORM 作用域发布成功（发布头 + 指针 + 版本行 + 审计）")
    void platformScopePublishSucceedsThroughOpsChannel() {
        PlatformOpsPublishService ops = opsService();

        ConfigPublishResult result = ops.publish(platformCommand(1));

        assertThat(result.publishSeq()).isPositive();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"
                + " WHERE scope = 'PLATFORM' AND tenant_id IS NULL"))
                .as("⛔ 平台级发布头未落库：运维通道没有真正写进去")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_pointer"
                + " WHERE pointer_key = 'PLATFORM' AND scope = 'PLATFORM' AND tenant_id IS NULL"))
                .as("⛔ 平台级当前指针未落库")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_tool_def WHERE id = '" + TOOL_ID
                + "' AND published_at IS NOT NULL"))
                .as("⛔ 平台对象版本行未落库")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_decision_log"
                + " WHERE decision_kind = 'PUBLISH' AND tenant_id IS NULL"))
                .as("⛔ 平台级发布未写入决策日志（审计是发布生效条件之一）")
                .isEqualTo(1L);

        // 指针必须指向本次发布的序号（§6.3 第 3/5 条：指针说了算，绝不按 max(seq)）
        assertThat(adminQueryLong("SELECT current_publish_seq FROM cap_config_pointer"
                + " WHERE pointer_key = 'PLATFORM'"))
                .isEqualTo(result.publishSeq());
    }

    @Test
    @DisplayName("✅ 平台级回滚走运维通道：指针切换 + 被替换的发布标记 ROLLED_BACK")
    void platformScopeRollbackSucceedsThroughOpsChannel() {
        PlatformOpsPublishService ops = opsService();
        ConfigPublishResult first = ops.publish(platformCommand(1));
        ConfigPublishResult second = ops.publish(platformCommand(2));

        // ⚠️ 回滚到 v1：被替换掉的是 v2（当前发布），它才是被标记 ROLLED_BACK 的那一条
        //    （§6.3 第 7 条：标记的是「原发布」，即回滚前生效的那一条）。
        ConfigPublishResult rolled = ops.rollback(new ConfigRollbackCommand(
                ConfigRelease.Scope.PLATFORM, null, first.release().releaseId(),
                null, "it-requester", "it-approver", null, null));

        assertThat(rolled.publishSeq()).isPositive();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"
                + " WHERE status = 'ROLLED_BACK' AND release_id = "
                + second.release().releaseId()))
                .as("⛔ 被替换的发布未被标记 ROLLED_BACK（审计旁路缺失，§6.3 第 7 条）")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"
                + " WHERE status = 'ROLLED_BACK' AND release_id = "
                + first.release().releaseId()))
                .as("⛔ 回滚目标被标记 ROLLED_BACK：回滚来源必须仍可用于再次回滚")
                .isZero();
        assertThat(adminQueryLong("SELECT current_publish_seq FROM cap_config_pointer"
                + " WHERE pointer_key = 'PLATFORM'"))
                .isEqualTo(rolled.publishSeq());
    }

    // ── 隔离边界 ──────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 接入运维通道后，租户角色仍然不能发布 PLATFORM 作用域")
    void appRoleStillCannotPublishPlatformScope() {
        ConfigPublishService tenant = tenantService();

        assertThatThrownBy(() -> tenant.publish(platformCommand(1)))
                .as("⛔ 租户角色成功发布了平台级配置："
                        + "任一租户都能单方面替换全平台生效配置")
                .isInstanceOf(RuntimeException.class);

        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"))
                .as("⛔ 被拒绝的平台发布仍留下了发布头")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_pointer"))
                .as("⛔ 被拒绝的平台发布仍创建了当前指针")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 运维事务不得用于租户作用域（运维凭据不是跨租户写入的后门）")
    void opsTransactionRefusesTenantScope() {
        JdbcPlatformOpsConfigPublishTransaction tx =
                new JdbcPlatformOpsConfigPublishTransaction(enabledProvider());

        assertThatThrownBy(() -> tx.execute(
                "11111111-1111-1111-1111-111111111111", () -> "never"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不得用于租户作用域");

        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"))
                .as("⛔ 被拒绝的运维事务仍写入了数据")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 平台运维发布入口拒收租户作用域（闸门在任何凭据使用之前）")
    void opsPublishServiceRefusesTenantScope() {
        PlatformOpsPublishService ops = opsService();

        assertThatThrownBy(() -> ops.publish(new ConfigPublishCommand(
                ConfigRelease.Scope.TENANT, tenantA, null,
                "it-requester", "it-approver", null,
                List.of(), List.of(), List.of(), List.of(), null)))
                .isInstanceOf(ConfigPublishRejectedException.class);

        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"))
                .as("⛔ 被拒绝的租户作用域发布仍写入了发布头")
                .isZero();
    }

    // ── fail-closed ───────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 未接入运维角色 → PLATFORM 发布 fail-closed，且不写任何行")
    void unconfiguredPlatformOpsFailsClosedWithoutSideEffects() {
        PlatformOpsPublishService ops = new PlatformOpsPublishService(
                new JdbcPlatformOpsConfigPublishTransaction(
                        PlatformOpsDataSourceProvider.disabled()),
                new JdbcConfigPointerRepository(appPool),
                new JdbcConfigReleaseRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcCapAgentDefRepository(appPool),
                new JdbcDecisionAuditRepository(appPool),
                new ConfigValidationGate(),
                CLOCK);

        assertThatThrownBy(() -> ops.publish(platformCommand(1)))
                .isInstanceOf(PlatformOpsUnavailableException.class)
                .hasMessageContaining("agent.platform-ops.datasource.enabled");

        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"))
                .as("⛔ fail-closed 却仍写入了发布头")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_pointer"))
                .as("⛔ fail-closed 却仍创建了当前指针")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_tool_def WHERE id = '"
                + TOOL_ID + "'"))
                .as("⛔ fail-closed 却仍写入了版本行")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 运维连接在发布事务之外不可取（BYPASSRLS 不得泄漏到业务读路径）")
    void opsConnectionIsUnusableOutsidePublishSession() {
        PlatformOpsDataSourceProvider provider = enabledProvider();
        DataSource[] captured = new DataSource[1];
        provider.inPlatformScopeSession(ds -> {
            captured[0] = ds;
            return null;
        });

        assertThatThrownBy(() -> captured[0].getConnection())
                .as("⛔ 会话结束后仍能取到运维连接："
                        + "该连接不受 RLS 约束，取到它 = 租户隔离整体失效")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户隔离");
    }

    // ── 回归 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 租户作用域发布仍走应用角色通道，不受运维通道影响")
    void tenantScopePublishStillWorksThroughAppChannel() {
        ConfigPublishService tenant = tenantService();

        ConfigPublishResult result = tenant.publish(new ConfigPublishCommand(
                ConfigRelease.Scope.TENANT, tenantA, null,
                "it-requester", "it-approver", null,
                List.of(), List.of(), List.of(), List.of(), null));

        assertThat(result.publishSeq()).isPositive();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release WHERE tenant_id = '"
                + tenantA + "'::uuid"))
                .as("⛔ 租户作用域发布被运维通道的引入影响")
                .isEqualTo(1L);
    }

    // ── 装配 ──────────────────────────────────────────────────────

    /**
     * 平台运维发布服务。
     *
     * <p>⚠️ <b>端口刻意用应用角色的连接池</b>，与生产装配保持一致：
     * 运维连接之所以被使用，是因为<b>发布事务</b>开了它，
     * 而不是因为端口被换成了运维池 ——
     * 这正是「连接由事务决定」的机制在真实 PG 上的验证。
     */
    private PlatformOpsPublishService opsService() {
        return new PlatformOpsPublishService(
                new JdbcPlatformOpsConfigPublishTransaction(enabledProvider()),
                new JdbcConfigPointerRepository(appPool),
                new JdbcConfigReleaseRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcCapAgentDefRepository(appPool),
                new JdbcDecisionAuditRepository(appPool),
                new ConfigValidationGate(),
                CLOCK);
    }

    private PlatformOpsDataSourceProvider enabledProvider() {
        return new PlatformOpsDataSourceProvider(true, opsPool);
    }

    /** 租户角色的发布服务（非特权，只能写本租户 TENANT 作用域）。 */
    private ConfigPublishService tenantService() {
        DataSource ds = appPool;
        return new ConfigPublishService(
                new JdbcConfigPublishTransaction(ds),
                new JdbcConfigPointerRepository(ds),
                new JdbcConfigReleaseRepository(ds),
                new JdbcCapToolDefRepository(ds),
                new JdbcCapSkillDefRepository(ds),
                new JdbcCapMcpServerDefRepository(ds),
                new JdbcCapAgentDefRepository(ds),
                new JdbcDecisionAuditRepository(ds),
                new ConfigValidationGate(),
                CLOCK);
    }

    private ConfigPublishCommand platformCommand(int version) {
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, null,
                "it-requester", "it-approver", null,
                List.of(tool(version)), List.of(skill(version)), List.of(), List.of(), null);
    }

    private static CapToolDef tool(int version) {
        return new CapToolDef(TOOL_ID, version, TOOL_ID, CapToolKind.NATIVE,
                CapabilityRiskLevel.READ, null, null, "{}", "{}", "fp-tool-v" + version, 1,
                Instant.now(), null);
    }

    private static CapSkillDef skill(int version) {
        return new CapSkillDef(SKILL_ID, version, SKILL_ID, null, List.of(TOOL_ID), List.of(),
                CapabilityRiskLevel.READ, "{}", List.of(), "fp-skill-v" + version, 1,
                Instant.now(), null);
    }
}
