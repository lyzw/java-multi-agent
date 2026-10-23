package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.ConfigObjectVersionCodec;
import cn.studykid.multiagent.application.cap.ConfigPublishConflictException;
import cn.studykid.multiagent.application.cap.ConfigPublishRejectedException;
import cn.studykid.multiagent.application.cap.ConfigPublishService;
import cn.studykid.multiagent.application.cap.ConfigValidationGate;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ConfigObjectVersion;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigPublishResult;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ConfigRollbackCommand;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W-PERM-P5 原子发布 / 回滚 / 生命周期的<b>真实 PG</b> 集成测试（§6.3 / §6.6 / §9.2 · P5-T8）。
 *
 * <p>⚠️ <b>本类锁的是「不变量」，不是「返回值」</b>：
 * <ul>
 *   <li><b>PUB-1</b>：CAS 失败事务<b>无半成品当前发布</b>；</li>
 *   <li><b>PUB-2</b>：seq 分配序 ≠ 提交序时，指针仍以 CAS 为准；</li>
 *   <li><b>回滚</b>：新建回滚发布 + 切指针，<b>不恢复撤权</b>；</li>
 *   <li><b>生命周期</b>：未发布不可启用；停用后不可新引用。</li>
 * </ul>
 *
 * <p>⚠️ <b>PLATFORM 作用域的发布必须用平台运维角色</b>（{@code adminPool}）：
 * {@code cap_config_release} 与 {@code cap_config_pointer} 的写策略
 * <b>只放行本租户的 TENANT 行</b>（V15 注释）——
 * 平台级发布若放开给任一租户，等于让任一租户单方面替换全平台生效配置。
 * 本类用两个角色分别验证「平台运维能发」与「租户不能发平台」。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ConfigPublishIT extends CapITBase {

    private static final String TOOL_ID = "it-p5-tool";
    private static final String SKILL_ID = "it-p5-skill";
    private static final Clock CLOCK = Clock.systemUTC();

    // ── 装配 ──────────────────────────────────────────────────────

    /** 平台运维角色的发布服务（BYPASSRLS，可写 PLATFORM 作用域）。 */
    private ConfigPublishService platformService() {
        return platformService(adminPool, new JdbcConfigPointerRepository(adminPool));
    }

    private ConfigPublishService platformService(DataSource ds, ConfigPointerPort pointers) {
        return new ConfigPublishService(
                new JdbcConfigPublishTransaction(ds),
                pointers,
                new JdbcConfigReleaseRepository(ds),
                new JdbcCapToolDefRepository(ds),
                new JdbcCapSkillDefRepository(ds),
                new JdbcCapMcpServerDefRepository(ds),
                new JdbcCapAgentDefRepository(ds),
                new JdbcDecisionAuditRepository(ds),
                new ConfigValidationGate(),
                CLOCK);
    }

    /** 租户角色的发布服务（非特权，只能写本租户 TENANT 作用域）。 */
    private ConfigPublishService tenantService() {
        return platformService(appPool, new JdbcConfigPointerRepository(appPool));
    }

    private ConfigPublishCommand platformCommand(CapabilityRiskLevel risk, int version) {
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, null,
                "it-requester", "it-approver", null,
                List.of(tool(version, risk)),
                List.of(skill(version)),
                List.of(), List.of(), null);
    }

    private static CapToolDef tool(int version, CapabilityRiskLevel risk) {
        return new CapToolDef(TOOL_ID, version, TOOL_ID, CapToolKind.NATIVE, risk,
                null, null, "{}", "{}", "fp-tool-v" + version, 1, Instant.now(), null);
    }

    private static CapSkillDef skill(int version) {
        return new CapSkillDef(SKILL_ID, version, SKILL_ID, null, List.of(TOOL_ID), List.of(),
                CapabilityRiskLevel.READ, "{}", List.of(), "fp-skill-v" + version, 1,
                Instant.now(), null);
    }

    // ── 原子发布 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 原子发布：版本行 + 发布头 + 指针 CAS 三者同时生效")
    void atomicPublishPersistsAllThree() {
        ConfigPublishService service = platformService();
        ConfigPublishResult result = service.publish(platformCommand(CapabilityRiskLevel.READ, 1));

        assertThat(result.attempts()).isEqualTo(1);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_tool_def WHERE id = '" + TOOL_ID + "'"))
                .as("⛔ 版本行未插入：发布没有落下不可变版本行")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"))
                .isEqualTo(1L);

        ConfigPointer pointer = new JdbcConfigPointerRepository(adminPool)
                .current(ConfigRelease.Scope.PLATFORM, null).orElseThrow();
        assertThat(pointer.currentReleaseId()).isEqualTo(result.release().releaseId());
        assertThat(pointer.currentPublishSeq()).isEqualTo(result.publishSeq());

        // 读路径：指针指向的发布解析出精确版本
        PublishedVersionPort versions = new JdbcPublishedVersionPort(adminPool);
        assertThat(versions.currentVersion(
                cn.studykid.multiagent.core.cap.CapObjectType.TOOL, TOOL_ID)).contains(1);
    }

    @Test
    @DisplayName("⛔ PUB-1：CAS 失败 → 整事务回滚，无半成品当前发布")
    void casFailureLeavesNoHalfPublishedState() {
        // ⚠️ 指针 CAS 必定失败的替身（模拟并发抢占）
        ConfigPointerPort alwaysFail = new ConfigPointerPort() {
            @Override
            public Optional<ConfigPointer> current(ConfigRelease.Scope scope, String tenantId) {
                return Optional.empty();
            }

            @Override
            public boolean compareAndSet(ConfigRelease.Scope scope, String tenantId,
                                         Long expectedPublishSeq, long newReleaseId,
                                         long newPublishSeq) {
                return false;
            }
        };
        ConfigPublishService service = platformService(adminPool, alwaysFail);

        assertThatThrownBy(() -> service.publish(platformCommand(CapabilityRiskLevel.READ, 1)))
                .isInstanceOf(ConfigPublishConflictException.class);

        assertThat(adminQueryLong("SELECT count(*) FROM cap_tool_def WHERE id = '" + TOOL_ID + "'"))
                .as("⛔ PUB-1 未满足：CAS 失败后版本行仍在（半成品当前发布）")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_skill_def WHERE id = '" + SKILL_ID + "'"))
                .as("⛔ PUB-1 未满足：CAS 失败后版本行仍在（半成品当前发布）")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"))
                .as("⛔ PUB-1 未满足：CAS 失败后发布头仍在（半成品当前发布）")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_pointer"))
                .as("⛔ PUB-1 未满足：CAS 失败却创建了当前指针")
                .isZero();
    }

    @Test
    @DisplayName("✅ 配置非法 → 门禁在事务之外拒绝：零写入、零序号占用")
    void invalidRiskLevelIsRejectedWithoutAnyWrite() {
        ConfigPublishService service = platformService();

        // ⚠️ 序号是全局单调的：非法发布**连序号都不该消耗**。
        //    门禁跑在事务之外，若它晚于序号分配，非法请求会白白吃掉一个序号 ——
        //    序号是「发布事件」的标识，白吃会让版本追溯出现空洞。
        long seqBefore = adminQueryLong("SELECT last_value FROM cap_config_publish_seq");

        assertThatThrownBy(() -> service.publish(platformCommand(CapabilityRiskLevel.UNKNOWN, 1)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RISK_LEVEL_UNKNOWN));

        assertThat(adminQueryLong("SELECT count(*) FROM cap_tool_def"))
                .as("⛔ 配置非法仍写入了版本行")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"))
                .as("⛔ 配置非法仍写入了发布头")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_pointer"))
                .as("⛔ 配置非法仍移动了当前指针")
                .isZero();
        assertThat(adminQueryLong("SELECT last_value FROM cap_config_publish_seq"))
                .as("⛔ 配置非法仍消耗了发布序号：门禁必须跑在事务之外、零序号占用")
                .isEqualTo(seqBefore);
    }

    // ── PUB-2 ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ PUB-2：seq 分配序 ≠ 提交序时，指针仍以 CAS 为准")
    void pointerFollowsCasNotSeqOrder() throws Exception {
        ConfigPublishService main = platformService();
        ConfigPublishResult first = main.publish(platformCommand(CapabilityRiskLevel.READ, 1));

        // ⚠️ A 的指针 CAS 前阻塞：让 A 先拿到 seq、后提交
        CountDownLatch aReachedCas = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        BlockingPointer blocking = new BlockingPointer(
                new JdbcConfigPointerRepository(adminPool), aReachedCas, releaseA);
        ConfigPublishService serviceA = platformService(adminPool, blocking);

        AtomicReference<ConfigPublishResult> resultA = new AtomicReference<>();
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        Thread threadA = new Thread(() -> {
            try {
                resultA.set(serviceA.publish(platformCommand(CapabilityRiskLevel.READ, 2)));
            } catch (Throwable t) {
                errorA.set(t);
            }
        });
        threadA.start();

        // A 已经拿到自己的 seq（发布头已插入）并停在 CAS 前
        assertThat(aReachedCas.await(10, TimeUnit.SECONDS))
                .as("⛔ A 未到达 CAS 点：并发编排失效，测的不是 PUB-2")
                .isTrue();
        // ⚠️ A 的发布头此刻**未提交**，别的连接读不到它 ——
        //    故 A 的序号只能从 CAS 参数里取（CAS 收到的是本次发布的 seq）。
        long seqA = blocking.capturedSeq.get();
        assertThat(seqA).isPositive();

        // B 此刻提交：它拿到更大的 seq 并先完成 CAS
        ConfigPublishResult b = main.publish(platformCommand(CapabilityRiskLevel.READ, 3));
        assertThat(b.publishSeq())
                .as("⛔ B 的序号未大于 A：seq 分配序前提不成立，测的不是 PUB-2")
                .isGreaterThan(seqA);

        ConfigPointer afterB = new JdbcConfigPointerRepository(adminPool)
                .current(ConfigRelease.Scope.PLATFORM, null).orElseThrow();
        assertThat(afterB.currentReleaseId())
                .as("⛔ 指针没有跟随 CAS 的赢家：它按 seq 顺序选了 A（A 的 seq 更小）")
                .isEqualTo(b.release().releaseId());

        // 放行 A：A 的基线已过时 → CAS 失败 → 变基重试 → 成功
        releaseA.countDown();
        threadA.join(20_000);
        assertThat(errorA.get()).as("⛔ A 的发布异常终止").isNull();
        assertThat(resultA.get()).as("⛔ A 未完成发布").isNotNull();

        ConfigPointer afterA = new JdbcConfigPointerRepository(adminPool)
                .current(ConfigRelease.Scope.PLATFORM, null).orElseThrow();
        assertThat(afterA.currentReleaseId())
                .as("⛔ 指针没有跟随最后一次成功的 CAS")
                .isEqualTo(resultA.get().release().releaseId());
        assertThat(resultA.get().attempts())
                .as("⛔ A 未发生变基重试：同基线并发失败方应变基重试（§6.3 第 4 条）")
                .isGreaterThanOrEqualTo(2);

        // ── 区分点：指针的目标**不是**「seq 最大的已发布发布」──
        // 租户作用域的发布拿到更大的全局序号，但平台当前发布不该被它顶掉。
        // 若实现按 max(seq) 选当前版本（§6.3 第 2 条明令禁止），
        // 这里会解析到租户发布 —— 平台对象将无版本可解析。
        ConfigPublishService tenant = tenantService();
        ConfigPublishResult tenantRelease = tenant.publish(new ConfigPublishCommand(
                ConfigRelease.Scope.TENANT, tenantA, null, "it-requester", "it-approver", null,
                List.of(), List.of(), List.of(), List.of(), null));
        assertThat(tenantRelease.publishSeq())
                .as("⛔ 前提不成立：租户发布的序号未大于平台发布")
                .isGreaterThan(afterA.currentPublishSeq());

        long maxPublishedSeq = adminQueryLong("SELECT max(config_publish_seq)"
                + " FROM cap_config_release WHERE status = 'PUBLISHED'");
        assertThat(maxPublishedSeq).isEqualTo(tenantRelease.publishSeq());

        ConfigPointer afterTenant = new JdbcConfigPointerRepository(adminPool)
                .current(ConfigRelease.Scope.PLATFORM, null).orElseThrow();
        assertThat(afterTenant.currentPublishSeq())
                .as("⛔ 平台当前发布被顶到了 seq 更大的租户发布：当前版本是按 max(seq) 选的")
                .isEqualTo(afterA.currentPublishSeq());
    }

    @Test
    @DisplayName("✅ 当前版本不以 max(seq) 选择：租户发布不会顶掉平台当前发布")
    void tenantPublishDoesNotStealPlatformCurrent() {
        ConfigPublishService platform = platformService();
        ConfigPublishResult platformRelease = platform.publish(
                platformCommand(CapabilityRiskLevel.READ, 1));

        // 租户作用域发布（更大的全局序号）
        ConfigPublishService tenant = tenantService();
        ConfigPublishResult tenantRelease = tenant.publish(new ConfigPublishCommand(
                ConfigRelease.Scope.TENANT, tenantA, null, "it-requester", "it-approver", null,
                List.of(), List.of(), List.of(), List.of(), null));

        assertThat(tenantRelease.publishSeq())
                .as("⛔ 前提不成立：租户发布的序号未大于平台发布")
                .isGreaterThan(platformRelease.publishSeq());

        ConfigPointer pointer = new JdbcConfigPointerRepository(adminPool)
                .current(ConfigRelease.Scope.PLATFORM, null).orElseThrow();
        assertThat(pointer.currentReleaseId())
                .as("⛔ 平台当前发布被租户发布顶掉了：当前版本被按 max(seq) 选择"
                        + "（租户发布的对象集合里没有平台对象）")
                .isEqualTo(platformRelease.release().releaseId());
    }

    // ── 回滚 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 回滚：新建回滚发布 + 指针切换 + 原发布标 ROLLED_BACK")
    void rollbackCreatesNewReleaseAndSwitchesPointer() {
        ConfigPublishService service = platformService();
        ConfigPublishResult r1 = service.publish(platformCommand(CapabilityRiskLevel.READ, 1));
        ConfigPublishResult r2 = service.publish(platformCommand(CapabilityRiskLevel.READ, 2));

        ConfigPublishResult rollback = service.rollback(new ConfigRollbackCommand(
                ConfigRelease.Scope.PLATFORM, null, r1.release().releaseId(),
                null, "it-requester", "it-approver", null, null));

        // ① 新建了一条 PUBLISHED 的回滚发布记录，内容 = 目标发布的对象集合
        String rollbackRow = adminQuery(
                "SELECT status || '|' || object_versions::text FROM cap_config_release"
                        + " WHERE release_id = " + rollback.release().releaseId());
        assertThat(rollbackRow).startsWith("PUBLISHED|");
        // ⚠️ 顺序按 (type, id) 规范化（skill < tool），与发布头的规范形态一致
        assertThat(ConfigObjectVersionCodec.parse(rollbackRow.substring("PUBLISHED|".length())))
                .containsExactly(ConfigObjectVersion.skill(SKILL_ID, 1),
                        ConfigObjectVersion.tool(TOOL_ID, 1));

        // ② 被回滚掉的发布被标记 ROLLED_BACK（仅审计旁路）
        assertThat(adminQuery("SELECT status FROM cap_config_release WHERE release_id = "
                + r2.release().releaseId()))
                .as("⛔ 被回滚的发布未被标记 ROLLED_BACK：审计旁路缺失（§6.3 第 7 条）")
                .isEqualTo("ROLLED_BACK");
        // 目标发布本身**未被改写**（历史内容保留，06 §3.2）
        assertThat(adminQuery("SELECT status FROM cap_config_release WHERE release_id = "
                + r1.release().releaseId())).isEqualTo("PUBLISHED");

        // ③ 指针切到回滚发布
        ConfigPointer pointer = new JdbcConfigPointerRepository(adminPool)
                .current(ConfigRelease.Scope.PLATFORM, null).orElseThrow();
        assertThat(pointer.currentReleaseId()).isEqualTo(rollback.release().releaseId());

        // ④ 读路径随之回到旧版本
        assertThat(new JdbcPublishedVersionPort(adminPool).currentVersion(
                cn.studykid.multiagent.core.cap.CapObjectType.TOOL, TOOL_ID)).contains(1);
    }

    @Test
    @DisplayName("✅ 回滚不恢复撤权：authzVersion 与既有审批完全不受影响")
    void rollbackDoesNotRestoreRevokedAuthorization() {
        ConfigPublishService service = platformService();
        ConfigPublishResult r1 = service.publish(platformCommand(CapabilityRiskLevel.READ, 1));
        ConfigPublishResult r2 = service.publish(platformCommand(CapabilityRiskLevel.READ, 2));

        // 撤权：授权版本推进到 5（模拟 revoke）
        adminExec("INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version)"
                + " VALUES ('" + tenantA + "'::uuid, 'it-p5-principal', 5)");
        // 一条既有审批
        adminExec("INSERT INTO cap_approval (tenant_id, approval_id, principal_id, tool_id,"
                + " target_resource, approval_scope_hash, scope_definition, effective_args_hash,"
                + " closure_fingerprint, authz_version, approver_id, approver_scope, expires_at)"
                + " VALUES ('" + tenantA + "'::uuid, '11111111-1111-1111-1111-111111111111',"
                + " 'it-p5-principal', '" + TOOL_ID + "', 'res-1', 'h1', '{}', 'h2', 'fp', 5,"
                + " 'it-p5-approver', 'ALL', now() + interval '1 hour')");

        service.rollback(new ConfigRollbackCommand(ConfigRelease.Scope.PLATFORM, null,
                r1.release().releaseId(), null, "it-requester", "it-approver", null, null));

        assertThat(adminQueryLong("SELECT authz_version FROM cap_authz_version"
                + " WHERE tenant_id = '" + tenantA + "'::uuid AND principal_id = 'it-p5-principal'"))
                .as("⛔ 回滚恢复了授权版本：回滚把「配置回退」变成了「权限恢复」（§6.3 第 6 条）")
                .isEqualTo(5L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_approval WHERE approval_id ="
                + " '11111111-1111-1111-1111-111111111111' AND approval_state = 'PENDING'"))
                .as("⛔ 回滚改变了既有审批的状态：配置回滚不得触碰审批链")
                .isEqualTo(1L);
    }

    // ── RLS 隔离 ──────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 租户角色不能发布 PLATFORM 作用域（越权写全局配置）")
    void tenantCannotPublishPlatformScope() {
        ConfigPublishService tenant = tenantService();

        assertThatThrownBy(() -> tenant.publish(platformCommand(CapabilityRiskLevel.READ, 1)))
                .as("⛔ 租户成功发布了平台级配置：任一租户都能单方面替换全平台生效配置")
                .isInstanceOf(RuntimeException.class);

        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release"))
                .as("⛔ 被拒绝的平台发布仍留下了发布头")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_pointer"))
                .as("⛔ 被拒绝的平台发布仍创建了当前指针")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_tool_def WHERE id = '" + TOOL_ID + "'"))
                .as("⛔ 被拒绝的平台发布仍留下了版本行")
                .isZero();
    }

    @Test
    @DisplayName("✅ 租户角色能发布自己的 TENANT 作用域，且看不到别家的指针")
    void tenantPublishesOwnScopeOnly() {
        ConfigPublishService tenant = tenantService();
        ConfigPublishResult result = tenant.publish(new ConfigPublishCommand(
                ConfigRelease.Scope.TENANT, tenantA, null, "it-requester", "it-approver", null,
                List.of(), List.of(), List.of(), List.of(), null));

        assertThat(result.publishSeq()).isPositive();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_config_release WHERE tenant_id = '"
                + tenantA + "'::uuid")).isEqualTo(1L);

        // 非特权角色在 B 租户上下文下读不到 A 租户的指针
        long visible = appCountAsTenant(tenantB,
                "SELECT count(*) FROM cap_config_pointer WHERE pointer_key = 'TENANT:"
                        + tenantA + "'");
        assertThat(visible)
                .as("⛔ 跨租户读到了别人的当前指针：RLS 隔离失效")
                .isZero();
    }

    // ── 审计 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 发布留下四字段完整的审计记录（08 §7）")
    void publishIsAudited() {
        ConfigPublishService service = platformService();
        ConfigPublishResult result = service.publish(platformCommand(CapabilityRiskLevel.READ, 1));

        assertThat(adminQueryLong("SELECT count(*) FROM cap_decision_log WHERE decision_kind ="
                + " 'PUBLISH' AND release_id = " + result.release().releaseId()))
                .as("⛔ 发布没有留下审计记录：配置生效边界不可追责")
                .isEqualTo(1L);
        assertThat(adminQueryLong("SELECT count(*) FROM cap_decision_log WHERE decision_kind ="
                + " 'PUBLISH' AND (authz_decision IS NULL OR exec_decision IS NULL"
                + " OR exec_status IS NULL OR ledger_status IS NULL OR reason_code IS NULL)"))
                .as("⛔ 审计记录的四字段不完整")
                .isZero();
    }

    // ── 夹具工具 ──────────────────────────────────────────────────

    /**
     * 在 CAS 前阻塞的指针替身（用于确定性地制造「seq 分配序 ≠ 提交序」）。
     */
    private static final class BlockingPointer implements ConfigPointerPort {

        private final ConfigPointerPort delegate;
        private final CountDownLatch reached;
        private final CountDownLatch release;
        /** 本次 CAS 携带的发布序号（A 的发布头此刻未提交，只能从这里取）。 */
        final AtomicReference<Long> capturedSeq = new AtomicReference<>(0L);

        BlockingPointer(ConfigPointerPort delegate, CountDownLatch reached, CountDownLatch release) {
            this.delegate = delegate;
            this.reached = reached;
            this.release = release;
        }

        @Override
        public Optional<ConfigPointer> current(ConfigRelease.Scope scope, String tenantId) {
            return delegate.current(scope, tenantId);
        }

        @Override
        public boolean compareAndSet(ConfigRelease.Scope scope, String tenantId,
                                     Long expectedPublishSeq, long newReleaseId,
                                     long newPublishSeq) {
            capturedSeq.set(newPublishSeq);
            reached.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待放行被中断", e);
            }
            return delegate.compareAndSet(scope, tenantId, expectedPublishSeq, newReleaseId,
                    newPublishSeq);
        }
    }
}
