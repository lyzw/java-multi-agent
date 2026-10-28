package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.CapDualWriteService;
import cn.studykid.multiagent.application.cap.CapDualWriteSwitch;
import cn.studykid.multiagent.application.cap.DualWriteAgentManagePort;
import cn.studykid.multiagent.application.cap.PermissionResolver;
import cn.studykid.multiagent.application.run.RunCreationService;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.mcp.UnconfiguredMcpManifestPort;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentManageRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAuditRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcExecutionSnapshotResolver;
import cn.studykid.multiagent.infrastructure.persistence.JdbcRunCreationRepository;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力快照<b>写入入口</b>的真实 PG 集成测试（G-3 · 10 §8 S0）。
 *
 * <p><b>本类锁的是「链路真的通到底」</b>。G-3 的真缺陷不是「{@code config_version}
 * 写成 0」，而是 {@code execution_capability_snapshot} <b>压根没有生产写入点</b> ——
 * 单元测试只能证明「调用了 save」，只有真库能证明「行确实落下了、
 * 且值来自权威来源」。所以本类一律<b>不桩件化任何一层</b>：
 * Run 创建、快照解析、闭包解析、授权版本、发布指针全部走 JDBC 实现。
 *
 * <p>三条判据（缺一即 G-3 未修复）：
 * <ol>
 *   <li>创建 Run 后<b>有</b>一行能力快照，其 {@code agent_version} 与 Run 绑定的
 *       快照同源（同一次读解析出来）；</li>
 *   <li>{@code config_version} <b>跟随当前发布指针</b>：发布新配置后递增，
 *       且此时 {@code config_fingerprint} 随之变化；</li>
 *   <li>算不出来时<b>不写占位</b>：授权版本缺失 → 快照整条不写（宁缺毋滥），
 *       但 Run 照常提交（双写的 fail-soft 契约不得破坏旧路径）。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么 {@code config_version} 只认 TENANT 指针</b>：
 * 见 {@link CapDualWriteService#currentConfigVersion} —— 序列的
 * {@code last_value} 是全局的，别的租户发布会让它凭空跳格，
 * 于是「configVersion 递增」不再等价于「本租户的配置变过」。
 * 本类的夹具因此<b>只</b>移动本租户指针（平台对象发布用于改变闭包内容，
 * 不参与 {@code config_version} 的判定）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class CapabilitySnapshotWriterIT extends CapITBase {

    /** 平台对象 id 用 {@code it-cap-} 前缀：基座每例清理已覆盖该前缀。 */
    private static final String TOOL_ID = "it-cap-g3-tool";
    private static final String SKILL_ID = "it-cap-g3-skill";
    private static final String PRINCIPAL = "it-g3-principal";

    /** v1：叶子 Agent（无 Skill）—— 闭包可解析，不依赖任何平台发布。 */
    private static final String LEAF_DEFINITION = """
            {"name":"g3-agent","description":"G-3 写入入口用例",
             "roles":["TEACHER"],"enabledSkills":[]}
            """;

    /** v2：启用 Skill —— 闭包内容变了，指纹必须跟着变。 */
    private static final String SKILLED_DEFINITION = """
            {"name":"g3-agent","description":"G-3 写入入口用例",
             "roles":["TEACHER"],"enabledSkills":["%s"]}
            """.formatted(SKILL_ID);

    private CapDualWriteService dualWrite;
    private AgentManagePort agentManage;
    private AuthzVersionPort authzVersions;
    private CapabilitySnapshotPort snapshots;

    /** 本例创建的 Run（用完删掉，避免污染 claimTasks 的任务窗口）。 */
    private final List<String> createdRuns = new ArrayList<>();

    /** 装配：双写端口接<b>真实</b>发布指针仓储（G-3 修复的那一条依赖）。 */
    private void build() {
        dualWrite = new CapDualWriteService(new CapDualWriteSwitch(true),
                new JdbcCapAgentDefRepository(appPool),
                new PermissionResolver(new JdbcCapAgentDefRepository(appPool),
                        new JdbcCapSkillDefRepository(appPool),
                        new JdbcCapToolDefRepository(appPool),
                        new JdbcCapMcpServerDefRepository(appPool),
                        new JdbcPublishedVersionPort(appPool),
                        new UnconfiguredMcpManifestPort()),
                new JdbcAuthzVersionRepository(appPool),
                new JdbcCapabilitySnapshotRepository(appPool),
                Clock.systemUTC(),
                new JdbcConfigPointerRepository(appPool));
        agentManage = new DualWriteAgentManagePort(
                new JdbcAgentManageRepository(appPool, new JdbcAuditRepository(appPool)),
                dualWrite);
        authzVersions = new JdbcAuthzVersionRepository(appPool);
        snapshots = new JdbcCapabilitySnapshotRepository(appPool);
    }

    @AfterEach
    void removeCreatedRuns() {
        // ⚠️ 快照对 run 有外键（ON DELETE CASCADE）：删 Run 即连带删快照，
        //    本例残留不会长进下一例的「零写入」断言里。
        for (String runId : createdRuns) {
            adminExec("DELETE FROM run WHERE run_id = '" + runId + "'::uuid");
        }
        createdRuns.clear();
    }

    // ── 判据 1：写入入口 ──────────────────────────────────────────

    @Test
    @DisplayName("✅ 创建 Run 即产出能力快照：agent_version 与 Run 快照同源")
    void creatingRunWritesCapabilitySnapshot() {
        build();
        String agentId = publishAgent(LEAF_DEFINITION);
        authzVersions.initialize(tenantA, PRINCIPAL);
        long pointerSeq = publishTenantConfig("agent:" + agentId + "@1");

        String runId = createRun(agentId);

        CapabilitySnapshot snapshot = snapshots.find(tenantA, runId)
                .orElseThrow();
        assertThat(snapshot.agentVersion())
                .as("⛔ agent_version 不是本次 Run 绑定的那个版本：不可变快照将永久失真")
                .isEqualTo(1);
        assertThat(snapshot.configVersion())
                .as("⛔ config_version 没取当前发布指针（G-3 原缺陷：恒为 0）")
                .isEqualTo(pointerSeq);
        assertThat(snapshot.configFingerprint())
                .as("指纹必须是闭包指纹（cap_fp_v1 前缀），不是对象内容指纹的别名")
                .startsWith("cap_fp_v1:");
        assertThat(snapshot.authzVersion())
                .as("authz_version 必须取自主体授权版本表")
                .isEqualTo(authzVersions.require(tenantA, PRINCIPAL).authzVersion());
        assertThat(snapshot.agentId()).isEqualTo(agentId);

        // 隔离：B 读不到 A 的快照（快照写入没有绕开 RLS）
        assertThat(snapshots.find(tenantB, runId))
                .as("⛔⛔ 跨租户读到能力快照：闭包内容将泄漏给别的租户")
                .isEmpty();
    }

    // ── 判据 2：config_version 跟随指针 ───────────────────────────

    @Test
    @DisplayName("✅ 本租户尚未发布过配置：config_version 如实记 0 并计数")
    void missingPointerIsRecordedNotGuessed() {
        build();
        String agentId = publishAgent(LEAF_DEFINITION);
        authzVersions.initialize(tenantA, PRINCIPAL);

        String runId = createRun(agentId);

        assertThat(snapshots.find(tenantA, runId).orElseThrow().configVersion())
                .as("0 的语义是「无发布序号可追溯」，不是「配置没变过」")
                .isZero();
        assertThat(dualWrite.unknownConfigVersionCount())
                .as("⛔ 指针缺失未计数：追溯字段形同虚设将无人察觉")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ 发布新配置后：config_version 递增且 config_fingerprint 变化（G-3 验收）")
    void newPublishBumpsConfigVersionAndChangesFingerprint() {
        build();
        String agentId = publishAgent(LEAF_DEFINITION);
        authzVersions.initialize(tenantA, PRINCIPAL);
        long firstSeq = publishTenantConfig("agent:" + agentId + "@1");

        CapabilitySnapshot before = snapshots.find(tenantA, createRun(agentId)).orElseThrow();

        // ── 变配置：平台侧发布 Skill/Tool，Agent 发布 v2 启用它 ──
        insertPlatformObjects();
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");
        agentManage.publishVersion(tenantA, agentId, SKILLED_DEFINITION, "publisher-1");
        long secondSeq = publishTenantConfig("agent:" + agentId + "@2");

        CapabilitySnapshot after = snapshots.find(tenantA, createRun(agentId)).orElseThrow();

        assertThat(after.configVersion())
                .as("⛔ config_version 没跟着发布指针递增：「策略变了没有」的判据不成立")
                .isGreaterThan(before.configVersion());
        assertThat(secondSeq).isGreaterThan(firstSeq);
        assertThat(after.configFingerprint())
                .as("⛔ 闭包内容变了但指纹没变：「看起来生效、实际不生效」的经典形态")
                .isNotEqualTo(before.configFingerprint());
        assertThat(after.agentVersion())
                .as("新版本 Run 必须绑到新版本")
                .isEqualTo(2);
        assertThat(before.agentVersion()).isEqualTo(1);
    }

    // ── 判据 3：不写占位 / fail-soft ──────────────────────────────

    @Test
    @DisplayName("⛔ 授权版本缺失 → 整条快照不写（不写占位闭包），但 Run 照常提交")
    void missingAuthzVersionSkipsSnapshotWithoutBreakingRun() {
        build();
        String agentId = publishAgent(LEAF_DEFINITION);
        publishTenantConfig("agent:" + agentId + "@1");
        // 故意不 initialize(principal)：require() 抛 AuthzUnavailableException

        String runId = createRun(agentId);

        assertThat(snapshots.find(tenantA, runId))
                .as("⛔ 算不出 authz_version 仍写了快照：配置缺陷被伪装成权限结论")
                .isEmpty();
        assertThat(dualWrite.deferredSnapshotCount())
                .as("挂起未计数：缺失的快照将成为无人知的空洞")
                .isEqualTo(1L);
        assertThat(dualWrite.failureCount())
                .as("双写降级不得升级为旧路径失败")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM run WHERE run_id = '" + runId + "'::uuid"))
                .as("⛔ Run 因「顺带写快照」失败而没建成：违反双写的 fail-soft 契约")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ 幂等重放不再写第二份快照")
    void replayDoesNotWriteAnotherSnapshot() {
        build();
        String agentId = publishAgent(LEAF_DEFINITION);
        authzVersions.initialize(tenantA, PRINCIPAL);
        publishTenantConfig("agent:" + agentId + "@1");

        RunCreationService service = runService();
        String key = "it-g3-replay-" + UUID.randomUUID();
        TenantContext context = new TenantContext(tenantA, PRINCIPAL, Set.of(), 0L);
        String first = service.createRun(context, agentId,
                new IdempotencyKey("run.create", key),
                Map.of("q", "x")).runId();
        createdRuns.add(first);
        var replay = service.createRun(context, agentId,
                new IdempotencyKey("run.create", key),
                Map.of("q", "x"));
        assertThat(replay.replayed()).isTrue();

        assertThat(snapshots.find(tenantA, first)).isPresent();
        assertThat(adminQueryLong("SELECT count(*) FROM execution_capability_snapshot "
                + "WHERE tenant_id = '" + tenantA + "'::uuid"))
                .as("⛔ 重放产生了重复副作用：快照应当只在首次执行时写一次")
                .isEqualTo(1L);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    /** 用真实服务链路创建 Run（走 RunCreationService，不绕过任何一步）。 */
    private String createRun(String agentId) {
        String runId = runService().createRun(
                new TenantContext(tenantA, PRINCIPAL, Set.of(), 0L), agentId,
                new IdempotencyKey("run.create", "it-g3-" + UUID.randomUUID()),
                Map.of("q", "x")).runId();
        createdRuns.add(runId);
        return runId;
    }

    private RunCreationService runService() {
        return new RunCreationService(new JdbcRunCreationRepository(appPool),
                new JdbcExecutionSnapshotResolver(appPool),
                tenantBoundTransaction(), Clock.systemUTC(),
                new JdbcBudgetRepository(appPool), dualWrite);
    }

    /**
     * 与 {@code ApplicationConfiguration#transactionRunner} 同形的租户绑定事务。
     *
     * <p>⚠️ 必须「关自动提交 + 绑定租户」：{@code set_config(..., is_local=true)}
     * 是事务局部语义，自动提交下绑定立即失效 → 「写入成功但查询恒为 0」。
     */
    private static RunCreationService.TransactionRunner tenantBoundTransaction() {
        return new RunCreationService.TransactionRunner() {
            @Override
            public <T> T execute(String tenantId,
                                 RunCreationService.TransactionRunner.TransactionalAction<T> action) {
                return TransactionalConnectionHolder.inTransaction(appPool,
                        new TenantContext(tenantId, "system", Set.of(), 0L),
                        new TransactionalConnectionHolder.TxAction<T>() {
                            @Override
                            public T run(Connection connection) {
                                return action.run();
                            }
                        });
            }
        };
    }

    /** 建 Agent 并发布 v1（旧模型 + 双写出的 cap_agent_def v1）。 */
    private String publishAgent(String definition) {
        String name = uniqueName("g3-agent");
        String agentId = agentManage.createAgent(tenantA, name, "G-3 用例", "creator-1").agentId();
        agentManage.publishVersion(tenantA, agentId, definition, "publisher-1");
        return agentId;
    }

    /** 往平台对象表插一行 Tool v1 + 一行 Skill v1（Skill 声明该 Tool）。 */
    private static void insertPlatformObjects() {
        new JdbcCapToolDefRepository(appPool).insert(
                new CapToolDef(TOOL_ID, 1, TOOL_ID, CapToolKind.NATIVE,
                        CapabilityRiskLevel.READ, null, null, "{}", "{}", "fp-g3-tool-v1",
                        1, Instant.now(), Instant.now()));
        new JdbcCapSkillDefRepository(appPool).insert(new CapSkillDef(SKILL_ID, 1, SKILL_ID,
                null, List.of(TOOL_ID), List.of(), CapabilityRiskLevel.READ, "{}", List.of(),
                "fp-g3-skill-v1", 1, Instant.now(), Instant.now()));
    }

    /**
     * 移动<b>本租户</b>当前发布指针（造「本租户刚发布过一版配置」的现场）。
     *
     * <p>⚠️ 用管理角色直插发布头 + 指针，而不是走 {@code ConfigPublishService}：
     * 被测对象是<b>读</b>侧「config_version 是否只认当前指针」，
     * 发布链路本身由 {@code ConfigPublishIT} 负责，两层各测各的。
     *
     * <p>⚠️ RLS 的写策略只放行本租户的 TENANT 行，平台运维角色（BYPASSRLS）
     * 才能替租户造这条夹具 —— 与 {@code publishPlatformObjects} 同理。
     *
     * @param objectRefs 发布头里的对象引用，形如 {@code agent:<uuid>@1}
     * @return 本次发布的序号（即指针的 {@code current_publish_seq}）
     */
    private static long publishTenantConfig(String... objectRefs) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < objectRefs.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(objectRefJson(objectRefs[i]));
        }
        json.append(']');

        long seq = adminQueryLong("SELECT nextval('cap_config_publish_seq')");
        adminExec("INSERT INTO cap_config_release (config_publish_seq, scope, tenant_id,"
                + " object_versions, aggregate_fingerprint, status, requester_id, approver_id,"
                + " effective_at, created_at)"
                + " VALUES (" + seq + ", 'TENANT', '" + tenantA + "'::uuid, '" + json + "'::jsonb,"
                + " 'fp-agg-" + seq + "', 'PUBLISHED', 'it-requester', 'it-approver',"
                + " now(), now())");
        long releaseId = adminQueryLong("SELECT release_id FROM cap_config_release"
                + " WHERE config_publish_seq = " + seq);
        adminExec("INSERT INTO cap_config_pointer (pointer_key, scope, tenant_id,"
                + " current_release_id, current_publish_seq, updated_at)"
                + " VALUES ('TENANT:" + tenantA + "', 'TENANT', '" + tenantA + "'::uuid,"
                + " " + releaseId + ", " + seq + ", now())"
                + " ON CONFLICT (pointer_key) DO UPDATE"
                + " SET current_release_id = EXCLUDED.current_release_id,"
                + " current_publish_seq = EXCLUDED.current_publish_seq, updated_at = now()");
        return seq;
    }

    /** 把 {@code type:id@version} 转成发布头 {@code object_versions[]} 的一项。 */
    private static String objectRefJson(String ref) {
        int at = ref.lastIndexOf('@');
        int colon = ref.indexOf(':');
        return "{\"type\":\"" + ref.substring(0, colon) + "\",\"id\":\""
                + ref.substring(colon + 1, at) + "\",\"version\":" + ref.substring(at + 1) + "}";
    }
}
