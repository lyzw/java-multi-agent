package cn.studykid.multiagent.infrastructure.persistence.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;
import cn.studykid.multiagent.infrastructure.persistence.cap.JdbcAgentInvocationRepository;
import cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapAgentChildRefRepository;
import cn.studykid.multiagent.infrastructure.persistence.cap.JdbcCapResultContractRepository;
import cn.studykid.multiagent.infrastructure.persistence.cap.JdbcResultContractProvider;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;
import cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;
import cn.studykid.multiagent.ports.orchestration.ResultContractProvider;

/**
 * 多级 Agent 三张新表的 <b>DB 层</b>集成测试
 * （多级 Agent v1.1 · FR-022 / FR-027 · MA-T02 / MA-T10 / MA-T12 / MA-T16）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：V19 / V20 迁移脚本与四个 JDBC 实现
 * （{@code JdbcAgentInvocationRepository} / {@code JdbcCapAgentChildRefRepository} /
 * {@code JdbcCapResultContractRepository} / {@code JdbcResultContractProvider}）
 * <b>此前从未在真实数据库上执行过</b> ——
 * 单元级证据全部基于 mock 连接，只能证明「调用了正确的 SQL 文本」，
 * 证不了「这段 SQL 在 PG 上真的能跑」（RLS 策略缺失、CHECK 约束不符、
 * 列名不匹配、复合外键指向错误的列……）。
 * <b>生产上第一次用就会炸</b>的典型风险，必须在 IT 里钉死。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>M-1：父子委派关系可写入可重建；</li>
 *   <li>M-2：<b>数据库约束真的在</b>（不是靠应用自觉）：
 *       悬空根、非法状态、非正世代令牌、悬空子引用、重复边，全都要被 PG 拒绝；</li>
 *   <li>M-3：重复投递不产生第二条逻辑调用（MA-T10）；</li>
 *   <li>M-4：旧世代提交终态被拒、新世代可提交（MA-T12）；</li>
 *   <li>M-5：接管只提升在途记录的世代令牌，<b>不改写终态</b>；</li>
 *   <li>M-6：租户隔离：三张表都受 RLS 约束，跨租户读不到；</li>
 *   <li>M-7：契约的「已发布 / 草稿」语义在 DB 上一致可辨。</li>
 * </ul>
 */
class MultiAgentPersistenceIT extends CapITBase {

    private static final String CURRENCY = "CNY";

    private AgentInvocationPort invocations;
    private CapAgentChildRefPort childRefs;
    private CapResultContractPort contracts;
    private ResultContractProvider contractProvider;

    @BeforeEach
    void wireRepositories() {
        invocations = new JdbcAgentInvocationRepository(appPool);
        childRefs = new JdbcCapAgentChildRefRepository(appPool);
        contracts = new JdbcCapResultContractRepository(appPool);
        contractProvider = new JdbcResultContractProvider(contracts);
        // ⚠️ 新表不在基座的清理清单里 → 必须每例清理：
        //    否则上一例的委派记录会让下一例的「唯一约束」「RLS 隔离」断言失真。
        adminExec("DELETE FROM agent_invocation WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM cap_agent_child_ref WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM cap_result_contract WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
    }


    /** 两个 JSON 文本是否语义等价（忽略空白与键序）。 */
    private static boolean sameJson(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        try {
            return new tools.jackson.databind.ObjectMapper()
                    .readTree(actual)
                    .equals(new tools.jackson.databind.ObjectMapper()
                            .readTree(expected));
        } catch (Exception e) {
            return false;
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    /** 建一条 cap_agent_def 行（子引用表的复合外键目标）。 */
    private String seedAgentDef(String tenantId) {
        String agentId = createAgentWithVersion(tenantId, uniqueName("it-ma-agent"), "{}", true);
        seedAgentDefVersion(tenantId, agentId, 1);
        return agentId;
    }

    /**
     * 补建某个 Agent 的第 N 版定义行。
     *
     * <p>⚠️ <b>为什么需要它</b>：{@code cap_agent_child_ref} 的父侧外键是
     * <b>复合</b>外键 {@code (tenant_id, parent_agent_id, parent_release)} →
     * {@code cap_agent_def (tenant_id, agent_id, version)}。
     * 于是「父 Agent 的第 2 版上挂的边」必须先有第 2 版定义行才写得进去 ——
     * 少了它，插入报的是外键违约，而不是「边不存在」。
     */
    private void seedAgentDefVersion(String tenantId, String agentId, int version) {
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, roles,"
                + " enabled_skills, overrides, config_fingerprint, published_at)"
                + " VALUES ('" + tenantId + "'::uuid, '" + agentId + "'::uuid, " + version
                + ", 'it agent', '[\"TEACHER\"]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'fp-agent-"
                + version + "', now())");
    }

    /** 造一条委派记录。 */
    private AgentInvocation invocation(String tenantId, String runId, String invocationId,
                                       String parentInvocationId, int depth,
                                       long fencingToken, AgentInvocationStatus status) {
        return new AgentInvocation(invocationId, tenantId, runId, parentInvocationId,
                uuid(), 1, uuid(), 1, status, depth, "trace-" + invocationId,
                "in-digest", null, Instant.now(), null, null, fencingToken);
    }

    // ── agent_invocation ────────────────────────────────────────────

    @Nested
    @DisplayName("agent_invocation（委派调用运行记录 · FR-022 / MA-T10 / MA-T12）")
    class InvocationRecord {

        @Test
        @DisplayName("M-1 ✅ 根调用与子调用都可写入，父子关系可据此重建")
        void rootAndChildInvocationsArePersisted() {
            String runId = uuid();
            String rootId = uuid();
            String childId = uuid();

            assertThat(invocations.insert(invocation(tenantA, runId, rootId, null, 1, 1L,
                    AgentInvocationStatus.RUNNING)))
                    .as("根调用（depth=1，无父）应写入成功")
                    .isTrue();
            assertThat(invocations.insert(invocation(tenantA, runId, childId, rootId, 2, 1L,
                    AgentInvocationStatus.RUNNING)))
                    .as("子调用（depth=2，有父）应写入成功")
                    .isTrue();

            var root = invocations.find(tenantA, rootId).orElseThrow();
            assertThat(root.parentInvocationId())
                    .as("根调用的父必须为空（否则调用树有悬空根）")
                    .isNull();
            var child = invocations.find(tenantA, childId).orElseThrow();
            assertThat(child.parentInvocationId())
                    .as("子调用必须能指回父调用（父子关系可重建）")
                    .isEqualTo(rootId);
            assertThat(invocations.listByRun(tenantA, runId))
                    .as("同一 Run 的委派记录应可列出")
                    .hasSize(2);
        }

        @Test
        @DisplayName("M-2 ⛔⚠️ 悬空根（depth=1 却带父）必须被数据库拒绝")
        void danglingRootIsRejectedByDatabase() {
            String runId = uuid();
            // ⚠️ 故意造「depth=1 但 parent_invocation_id 非空」的记录：
            //    若 CHECK 约束不在（或写错），这条记录会落库，
            //    于是「谁是根」出现两个答案，调用树无法重建。
            assertThatThrownBy(() -> invocations.insert(
                    invocation(tenantA, runId, uuid(), uuid(), 1, 1L,
                            AgentInvocationStatus.RUNNING)))
                    .as("⚠️⚠️ 悬空根必须被 PG 拒绝（约束真的在，而不是靠应用自觉）")
                    .isInstanceOf(Exception.class);

            assertThat(adminQueryLong("SELECT count(*) FROM agent_invocation WHERE tenant_id = '"
                    + tenantA + "'::uuid"))
                    .as("被拒的记录不得留下任何行")
                    .isZero();
        }

        @Test
        @DisplayName("M-2 ⛔⚠️ 非正世代令牌必须被数据库拒绝（MA-T12 的地基）")
        void nonPositiveFencingTokenIsRejected() {
            String runId = uuid();
            assertThatThrownBy(() -> invocations.insert(
                    invocation(tenantA, runId, uuid(), null, 1, 0L,
                            AgentInvocationStatus.RUNNING)))
                    .as("⚠️⚠️ fencing_token=0 必须被拒：世代令牌为 0 时，"
                            + "「旧世代提交」与「新世代提交」无法区分")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("M-2 ⛔⚠️ 非法状态必须被数据库拒绝（MA-T13：状态集合是封闭的）")
        void illegalStatusIsRejected() {
            String runId = uuid();
            // ⚠️ 绕过 Java 枚举，直接塞一个非法状态串：
            //    状态集合必须由 DB 兜住，而不是只靠应用层的枚举。
            assertThatThrownBy(() -> adminExec(
                    "INSERT INTO agent_invocation (invocation_id, tenant_id, run_id,"
                    + " parent_agent_id, parent_agent_version, target_agent_id,"
                    + " target_agent_version, status, depth, trace_id, fencing_token)"
                    + " VALUES ('" + uuid() + "'::uuid, '" + tenantA + "'::uuid, '"
                    + runId + "'::uuid, '" + uuid() + "'::uuid, 1, '" + uuid() + "'::uuid, 1,"
                    + " 'WEIRD_STATE', 1, 'trace-x', 1)"))
                    .as("⚠️⚠️ 非法状态必须被 CHECK 拒绝（MA-T13）")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("M-3 ✅ 重复投递不产生第二条逻辑调用（MA-T10 幂等）")
        void duplicateDeliveryDoesNotCreateSecondRecord() {
            String runId = uuid();
            String id = uuid();
            AgentInvocation record = invocation(tenantA, runId, id, null, 1, 1L,
                    AgentInvocationStatus.RUNNING);

            assertThat(invocations.insert(record)).isTrue();
            assertThat(invocations.insert(record))
                    .as("⚠️ 重复投递必须被幂等挡下（不得产生第二条逻辑调用）")
                    .isFalse();

            assertThat(adminQueryLong("SELECT count(*) FROM agent_invocation"
                    + " WHERE tenant_id = '" + tenantA + "'::uuid"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("M-4 ⚠️⚠️ 旧世代提交终态被拒、新世代可提交（MA-T12 fencing CAS）")
        void staleGenerationCannotCommitTerminalState() {
            String runId = uuid();
            String id = uuid();
            invocations.insert(invocation(tenantA, runId, id, null, 1, 5L,
                    AgentInvocationStatus.RUNNING));

            var stale = invocations.markTerminal(tenantA, id,
                    AgentInvocationStatus.SUCCEEDED, null, "digest", Instant.now(), 4L);
            assertThat(stale)
                    .as("⚠️⚠️ 旧世代（token 4 < 当前 5）不得提交终态")
                    .isEqualTo(AgentInvocationPort.TerminalWriteOutcome.TOKEN_EXPIRED);

            var current = invocations.markTerminal(tenantA, id,
                    AgentInvocationStatus.SUCCEEDED, null, "digest", Instant.now(), 5L);
            assertThat(current)
                    .as("当前世代提交终态应成功")
                    .isEqualTo(AgentInvocationPort.TerminalWriteOutcome.WRITTEN);

            var replay = invocations.markTerminal(tenantA, id,
                    AgentInvocationStatus.FAILED, "R", null, Instant.now(), 5L);
            assertThat(replay)
                    .as("⚠️ 已是终态后重复提交 = 幂等重放，**不得覆盖既有终态**")
                    .isEqualTo(AgentInvocationPort.TerminalWriteOutcome.ALREADY_TERMINAL);

            var stored = invocations.find(tenantA, id).orElseThrow();
            assertThat(stored.status())
                    .as("终态是权威事实，重放不得改写它")
                    .isEqualTo(AgentInvocationStatus.SUCCEEDED);
        }

        @Test
        @DisplayName("M-5 ✅ 接管只提升在途记录的世代令牌，不改写终态")
        void adoptPromotesInFlightOnly() {
            String runId = uuid();
            String inFlight = uuid();
            String terminal = uuid();
            invocations.insert(invocation(tenantA, runId, inFlight, null, 1, 1L,
                    AgentInvocationStatus.RUNNING));
            invocations.insert(invocation(tenantA, runId, terminal, null, 1, 1L,
                    AgentInvocationStatus.SUCCEEDED));

            int promoted = invocations.adoptInFlight(tenantA, runId, 9L);
            assertThat(promoted)
                    .as("只应提升在途记录")
                    .isEqualTo(1);

            assertThat(invocations.find(tenantA, inFlight).orElseThrow().fencingToken())
                    .as("在途记录的世代令牌应被提升到新世代")
                    .isEqualTo(9L);
            assertThat(invocations.find(tenantA, terminal).orElseThrow().fencingToken())
                    .as("⚠️⚠️ 终态记录的世代令牌不得被改动（终态是权威事实）")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("M-6 ✅ RLS：跨租户读不到别人的委派记录")
        void rlsIsolatesTenants() {
            String runId = uuid();
            String id = uuid();
            invocations.insert(invocation(tenantA, runId, id, null, 1, 1L,
                    AgentInvocationStatus.RUNNING));

            assertThat(invocations.find(tenantB, id))
                    .as("⚠️⚠️ 租户 B 不得读到租户 A 的委派记录（RLS 必须生效）")
                    .isEmpty();
            assertThat(invocations.listByRun(tenantB, runId)).isEmpty();
        }

        @Test
        @DisplayName("M-8 ✅ 第二十三批 A3：「模型原始候选」可写入、可读回，且与「实际生效目标」并存")
        void modelCandidateRoundTripsAlongsideEffectiveTarget() {
            String runId = uuid();
            String id = uuid();
            String candidate = uuid();

            // ⚠️ 用**完整构造器**写入「原始候选 ≠ 生效目标」这一形态：
            //    这正是路由降级后 DB 里应有的样子。
            assertThat(invocations.insert(new AgentInvocation(
                    id, tenantA, runId, null, uuid(), 1, uuid(), 1, candidate,
                    AgentInvocationStatus.RUNNING, 1, "trace-" + id,
                    "in-digest", null, Instant.now(), null, null, 1L)))
                    .as("带原始候选的委派记录应写入成功")
                    .isTrue();

            var row = invocations.find(tenantA, id).orElseThrow();
            assertThat(row.requestedTargetAgentId())
                    .as("模型原始候选必须能读回（否则降级在数据上就消失了）")
                    .isEqualTo(candidate);
            assertThat(row.targetAgentId())
                    .as("实际生效的目标同样不能丢（两者并存，互不替代）")
                    .isNotEqualTo(candidate);
            assertThat(row.routedFallback()).isTrue();
        }

        @Test
        @DisplayName("M-8b ✅ 第二十三批 A3：存量行语义为「当时未记录」（NULL 可落库、可读回）")
        void legacyRowKeepsNullCandidate() {
            String runId = uuid();
            String id = uuid();
            // ⚠️ 走**兼容构造器**（不含原始候选）—— 模拟迁移前写入的行
            assertThat(invocations.insert(invocation(tenantA, runId, id, null, 1, 1L,
                    AgentInvocationStatus.RUNNING)))
                    .isTrue();

            var row = invocations.find(tenantA, id).orElseThrow();
            assertThat(row.requestedTargetAgentId())
                    .as("未记录的原始候选必须是 NULL，而不是被填成「生效目标」冒充")
                    .isNull();
            assertThat(row.routedFallback())
                    .as("NULL 只意味着「无从判断」")
                    .isFalse();
        }

        @Test
        @DisplayName("M-8c ✅ 第二十三批 A3：降级索引的谓词必须排除存量行（NULL）")
        void fallbackIndexExcludesLegacyRows() {
            // ⚠️ **结构断言**：降级索引的谓词若漏掉 `IS NOT NULL`，
            //    存量行（requested 为 NULL）在 `IS DISTINCT FROM` 下会被判为「不同」——
            //    于是**从未发生降级**的历史行被算进降级集合，
            //    恰恰制造出本列要消除的那种噪音。
            String predicate = adminQuery(
                    "SELECT pg_get_expr(indpred, indrelid) FROM pg_index "
                  + "WHERE indexrelid = 'idx_agent_invocation_routed_fallback'::regclass");

            assertThat(predicate)
                    .as("降级索引谓词必须显式排除 NULL（存量行 ≠ 降级）")
                    .contains("IS NOT NULL")
                    .contains("IS DISTINCT FROM");

            assertThat(adminQuery(
                    "SELECT is_nullable FROM information_schema.columns "
                  + "WHERE table_name = 'agent_invocation' "
                  + "AND column_name = 'requested_target_agent_id'"))
                    .as("该列必须可空 —— 存量行没有这个事实，强行 NOT NULL 会让迁移失败")
                    .isEqualTo("YES");
        }
    }

    // ── cap_agent_child_ref ─────────────────────────────────────────

    @Nested
    @DisplayName("cap_agent_child_ref（父子关系投影 · MA-T02）")
    class ChildRefProjection {

        @Test
        @DisplayName("M-1 ✅ 投影可写入并按声明顺序读回")
        void childRefsRoundTrip() {
            String parent = seedAgentDef(tenantA);
            String childA = seedAgentDef(tenantA);
            String childB = seedAgentDef(tenantA);

            assertThat(childRefs.insert(new CapAgentChildRef(tenantA, parent, 1,
                    childA, 1, 0, true, Instant.now()))).isTrue();
            assertThat(childRefs.insert(new CapAgentChildRef(tenantA, parent, 1,
                    childB, 1, 1, true, Instant.now()))).isTrue();

            List<CapAgentChildRef> refs = childRefs.listByParentRelease(tenantA, parent, 1);
            assertThat(refs).hasSize(2);
            assertThat(refs.get(0).childAgentId())
                    .as("⚠️ 必须按 ordinal 顺序返回（父 Agent 的声明次序要保真 —— "
                            + "⚠️ 框架**不**按此顺序代为执行子 Agent，见 OrchestrationMode#SEQUENTIAL）")
                    .isEqualTo(childA);
            assertThat(refs.get(1).ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("M-2 ⛔⚠️ 引用不存在的子 Agent 版本必须被拒绝（不得留悬空边）")
        void danglingChildReferenceIsRejected() {
            String parent = seedAgentDef(tenantA);
            assertThatThrownBy(() -> childRefs.insert(new CapAgentChildRef(tenantA, parent, 1,
                    uuid(), 1, 0, true, Instant.now())))
                    .as("⚠️⚠️ 复合外键必须挡住悬空子引用"
                            + "（否则发布投影里会留下指向不存在版本的边）")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("M-2 ⛔⚠️ 同一父 release 重复声明同一子 release 必须被幂等挡下")
        void duplicateEdgeIsRejected() {
            String parent = seedAgentDef(tenantA);
            String child = seedAgentDef(tenantA);
            assertThat(childRefs.insert(new CapAgentChildRef(tenantA, parent, 1,
                    child, 1, 0, true, Instant.now()))).isTrue();

            // ⚠️ 投影表是**幂等 upsert**（ON CONFLICT DO NOTHING）而不是抛异常：
            //    投影由发布事务重复执行是常态（重放发布），
            //    真正的「重复声明」在**发布期**就被编排解析层拒绝
            //    （AgentOrchestration 构造器拒绝重复子引用）。
            //    DB 层的职责是「不产生第二条边」，而不是制造一个运行期异常。
            assertThat(childRefs.insert(new CapAgentChildRef(tenantA, parent, 1,
                    child, 1, 1, true, Instant.now())))
                    .as("⚠️⚠️ 重复声明同一子 release 必须被幂等挡下"
                            + "（重复会让子调用次数预算被重复计入、闭包的边集合出现重复）")
                    .isFalse();

            assertThat(childRefs.listByParentRelease(tenantA, parent, 1))
                    .as("重复声明不得产生第二条边")
                    .hasSize(1);
        }

        @Test
        @DisplayName("M-6 ✅ RLS：跨租户读不到别人的关系投影")
        void rlsIsolatesChildRefs() {
            String parent = seedAgentDef(tenantA);
            String child = seedAgentDef(tenantA);
            childRefs.insert(new CapAgentChildRef(tenantA, parent, 1, child, 1, 0, true,
                    Instant.now()));

            assertThat(childRefs.listByParentRelease(tenantB, parent, 1))
                    .as("⚠️⚠️ 租户 B 不得读到租户 A 的关系投影")
                    .isEmpty();
        }

        @Test
        @DisplayName("M-7 ✅ 反查：谁声明了我（删除守卫问的正是这个问题）")
        void inboundQueryFindsParents() {
            String parent = seedAgentDef(tenantA);
            String other = seedAgentDef(tenantA);
            String child = seedAgentDef(tenantA);
            childRefs.insert(new CapAgentChildRef(tenantA, parent, 1, child, 1, 0, true,
                    Instant.now()));
            childRefs.insert(new CapAgentChildRef(tenantA, other, 1, child, 1, 0, false,
                    Instant.now()));

            List<CapAgentChildRef> parents = childRefs.listByChildAgent(tenantA, child);
            assertThat(parents)
                    .as("⛔ 反查不到入向边：删除守卫问的正是「谁依赖我」，"
                            + "查不到就等于放行「被引用对象被抽走」")
                    .hasSize(2);
            assertThat(parents.stream().map(CapAgentChildRef::parentAgentId))
                    .containsExactlyInAnyOrder(parent, other);
            assertThat(childRefs.listByChildAgent(tenantA, uuid()))
                    .as("没人声明的 Agent 不得凭空有入向边")
                    .isEmpty();
        }

        @Test
        @DisplayName("M-8 ✅ 反查不按子版本过滤：引用任何一版都算依赖")
        void inboundQuerySpansChildVersions() {
            String parent = seedAgentDef(tenantA);
            String child = seedAgentDef(tenantA);
            // 父引用的是子 Agent 的 **v1**；v2 未被引用
            childRefs.insert(new CapAgentChildRef(tenantA, parent, 1, child, 1, 0, true,
                    Instant.now()));

            assertThat(childRefs.listByChildAgent(tenantA, child))
                    .as("⚠️ 反查必须按 Agent 维度（不限版本）："
                            + "被停用的是那个 Agent，不是某一版")
                    .hasSize(1);
        }

        @Test
        @DisplayName("M-9 ✅ 出向按父 Agent 聚合：跨父 release 的边都在")
        void outboundQuerySpansParentVersions() {
            String parent = seedAgentDef(tenantA);
            String child = seedAgentDef(tenantA);
            // ⚠️ 父侧外键是复合外键 → 父的第 2 版定义行必须先存在
            seedAgentDefVersion(tenantA, parent, 2);
            childRefs.insert(new CapAgentChildRef(tenantA, parent, 1, child, 1, 0, true,
                    Instant.now()));
            childRefs.insert(new CapAgentChildRef(tenantA, parent, 2, child, 1, 0, true,
                    Instant.now()));

            assertThat(childRefs.listByParentAgent(tenantA, parent))
                    .as("⚠️ 出向必须跨父 release 聚合："
                            + "拓扑图要呈现全部边，否则图会随版本选择而「缺边」")
                    .hasSize(2);
            assertThat(childRefs.listByParentAgent(tenantA, uuid())).isEmpty();
        }

        @Test
        @DisplayName("M-10 ✅ 全量拓扑按租户返回；跨租户不可见")
        void tenantWideTopologyIsTenantScoped() {
            String parent = seedAgentDef(tenantA);
            String child = seedAgentDef(tenantA);
            childRefs.insert(new CapAgentChildRef(tenantA, parent, 1, child, 1, 0, true,
                    Instant.now()));

            assertThat(childRefs.listByTenant(tenantA)).isNotEmpty();
            assertThat(childRefs.listByTenant(tenantB))
                    .as("⚠️⚠️ 全量拓扑必须按租户收窄：跨租户不可见（MA-T04）")
                    .isEmpty();
        }
    }

    // ── cap_result_contract ─────────────────────────────────────────

    @Nested
    @DisplayName("cap_result_contract（输出契约 · FR-027 / MA-T16）")
    class ResultContractRows {

        private CapResultContractDef contract(String tenantId, String contractId, int version,
                                              String schema, boolean published) {
            return new CapResultContractDef(tenantId, contractId, version, schema,
                    "fp-" + contractId + "-" + version, 1, Instant.now(),
                    published ? Instant.now() : null);
        }

        @Test
        @DisplayName("M-7 ✅ 已发布契约可按固定版本解析；草稿不可")
        void publishedContractIsResolvableAndDraftIsNot() {
            contracts.insert(contract(tenantA, "it-ma-contract", 1,
                    "{\"type\":\"object\"}", true));

            assertThat(contracts.find(tenantA, "it-ma-contract", 1))
                    .as("已发布契约应能按固定版本读到")
                    .isPresent();
            // ⚠️ 必须按**语义**比较，不能按文本比较：
            //    契约 Schema 存的是 JSONB，PG 会规范化（去空白、键序、重复 key 取最后一个），
            //    读回来的文本与写入文本**不会逐字符相同** ——
            //    按文本断言会得到一个「与实现无关」的假红。
            assertThat(sameJson(contractProvider.schemaFor(tenantA, "it-ma-contract@1"),
                    "{\"type\":\"object\"}"))
                    .as("provider 必须给出已发布契约的 Schema")
                    .isTrue();

            contracts.insert(contract(tenantA, "it-ma-contract", 2,
                    "{\"type\":\"array\"}", false));
            assertThat(contractProvider.schemaFor(tenantA, "it-ma-contract@2"))
                    .as("⚠️⚠️ 未发布契约必须解析为 null（调用方据此 fail-closed 拒绝委派）")
                    .isNull();
        }

        @Test
        @DisplayName("M-7 ✅ 「最新已发布」与「最新（含草稿）」是两个不同的口径")
        void latestPublishedAndLatestAreDifferent() {
            contracts.insert(contract(tenantA, "it-ma-contract", 1, "{\"v\":1}", true));
            contracts.insert(contract(tenantA, "it-ma-contract", 2, "{\"v\":2}", false));

            assertThat(contracts.findLatestPublished(tenantA, "it-ma-contract")
                    .orElseThrow().version())
                    .as("「最新已发布」只看已发布行")
                    .isEqualTo(1);
            assertThat(contracts.findLatest(tenantA, "it-ma-contract")
                    .orElseThrow().version())
                    .as("⚠️⚠️ 「最新（含草稿）」必须含草稿：草稿也占用版本号，"
                            + "只看已发布会分配出撞号的版本")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("M-6 ✅ RLS：跨租户读不到别人的契约")
        void rlsIsolatesContracts() {
            contracts.insert(contract(tenantA, "it-ma-contract", 1, "{\"v\":1}", true));

            assertThat(contracts.find(tenantB, "it-ma-contract", 1))
                    .as("⚠️⚠️ 租户 B 不得读到租户 A 的契约")
                    .isEmpty();
            assertThat(contractProvider.schemaFor(tenantB, "it-ma-contract@1"))
                    .isNull();
        }
    }
}
