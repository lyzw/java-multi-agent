package cn.studykid.multiagent.infrastructure.persistence.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;
import cn.studykid.multiagent.ports.orchestration.RoutingPolicyProvider;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;

/**
 * 路由策略表的 <b>DB 层</b>集成测试
 * （多级 Agent v1.1 · 第十四批 · MA-T05 / MA-T15）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：与 {@code MultiAgentPersistenceIT} 同源 ——
 * V24 迁移脚本与两个 JDBC 实现（{@code JdbcCapRoutingPolicyRepository} /
 * {@code JdbcRoutingPolicyProvider}）在写单元测试时<b>从未在真实数据库上执行过</b>。
 * mock 连接只能证明「调用了正确的 SQL 文本」，
 * 证不了「这段 SQL 在 PG 上真的能跑」（RLS 策略缺失、CHECK 约束不符、
 * {@code ?::jsonb} 转换失败、列名不匹配……）。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>R-1：策略可写入可读回，<b>targets 顺序保真</b>（降级语义依赖顺序）；</li>
 *   <li>R-2：重复写入是<b>幂等 upsert</b>（不覆盖既有版本行）；</li>
 *   <li>R-3：<b>数据库约束真的在</b>：非法策略名 / 空目标数组 / 非正版本号，
 *       全都要被 PG 拒绝；</li>
 *   <li>R-4：租户隔离：RLS 让跨租户读不到；</li>
 *   <li>R-5：「已发布 / 草稿」两套口径可辨，运行期端口<b>只认已发布</b>。</li>
 * </ul>
 */
class RoutingPolicyPersistenceIT extends CapITBase {

    private CapRoutingPolicyPort policies;
    private RoutingPolicyProvider provider;

    @BeforeEach
    void wireRepositories() {
        policies = new JdbcCapRoutingPolicyRepository(appPool);
        provider = new JdbcRoutingPolicyProvider(policies);
        // ⚠️ 新表不在基座的清理清单里 → 必须每例清理：
        //    否则上一例的策略行会让下一例的「唯一约束」「RLS 隔离」断言失真。
        adminExec("DELETE FROM cap_routing_policy WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private CapRoutingPolicyDef policy(String tenantId, String policyId, int version,
                                       RoutingStrategy strategy, List<String> targets,
                                       Instant publishedAt) {
        return new CapRoutingPolicyDef(tenantId, policyId, version, strategy, targets,
                "fp-" + policyId + "-" + version, 1, Instant.now(), publishedAt);
    }

    // ── 基本存取 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("cap_routing_policy（路由策略定义行 · MA-T15）")
    class PolicyRow {

        @Test
        @DisplayName("策略可写入可读回：目标顺序必须保真（ORDERED_FALLBACK 依赖顺序）")
        void writesAndReadsBack() {
            CapRoutingPolicyDef def = policy(tenantA, "it-rp-orders", 1,
                    RoutingStrategy.ORDERED_FALLBACK, List.of("agent-b", "agent-a", "agent-c"),
                    Instant.now());

            assertThat(policies.insert(def)).as("首次插入应成功").isTrue();

            Optional<CapRoutingPolicyDef> loaded = policies.find(tenantA, "it-rp-orders", 1);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().policyId()).isEqualTo("it-rp-orders");
            assertThat(loaded.get().version()).isEqualTo(1);
            assertThat(loaded.get().strategy()).isEqualTo(RoutingStrategy.ORDERED_FALLBACK);
            // ⚠️ 顺序是路由语义的一部分：PG 的 jsonb 会规范化空白，
            //    但**不会重排数组元素** —— 若读回顺序变了，说明实现用错了容器。
            assertThat(loaded.get().targets())
                    .as("targets 必须按声明顺序读回")
                    .containsExactly("agent-b", "agent-a", "agent-c");
            assertThat(loaded.get().isPublished()).isTrue();
            assertThat(loaded.get().ref()).isEqualTo("it-rp-orders@1");
        }

        @Test
        @DisplayName("重复写入同版本是幂等 upsert：返回 false 且不覆盖既有内容")
        void duplicateInsertIsIdempotent() {
            CapRoutingPolicyDef first = policy(tenantA, "it-rp-dup", 1,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-a"), Instant.now());
            assertThat(policies.insert(first)).isTrue();

            CapRoutingPolicyDef conflicting = policy(tenantA, "it-rp-dup", 1,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-zzz"), Instant.now());
            assertThat(policies.insert(conflicting))
                    .as("同版本号重复写入应返回 false")
                    .isFalse();

            // ⚠️ 既有行不得被覆盖：版本行不可变是「在途 Run 按快照复核」的前提。
            assertThat(policies.find(tenantA, "it-rp-dup", 1).orElseThrow().targets())
                    .containsExactly("agent-a");
        }

        @Test
        @DisplayName("findLatest 含草稿、findLatestPublished 不含草稿（版本号不得撞号）")
        void latestRespectsDraftBoundary() {
            policies.insert(policy(tenantA, "it-rp-draft", 1,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-a"), null));
            policies.insert(policy(tenantA, "it-rp-draft", 2,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-a"), Instant.now()));

            assertThat(policies.findLatest(tenantA, "it-rp-draft").orElseThrow().version())
                    .as("最新版本必须含草稿（草稿也占用版本号）")
                    .isEqualTo(2);
            assertThat(policies.findLatestPublished(tenantA, "it-rp-draft")
                    .orElseThrow().version())
                    .as("已发布版本只看 published_at 非空的行")
                    .isEqualTo(2);

            // ⚠️ 只有草稿时：已发布口径为空，但「最新版本」口径仍要能给出草稿 ——
            //    否则分配下一个版本号时会退回 1，与草稿撞号。
            policies.insert(policy(tenantA, "it-rp-only-draft", 1,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-a"), null));
            assertThat(policies.findLatestPublished(tenantA, "it-rp-only-draft")).isEmpty();
            assertThat(policies.findLatest(tenantA, "it-rp-only-draft")).isPresent();
        }
    }

    // ── 数据库约束 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("cap_routing_policy 的 DB 约束（不是靠应用自觉）")
    class DatabaseConstraints {

        @Test
        @DisplayName("非法策略名被 PG 拒绝（CHECK 约束真的在）")
        void rejectsUnknownStrategy() {
            assertThatThrownBy(() -> adminExec("INSERT INTO cap_routing_policy (tenant_id,"
                    + " policy_id, version, strategy, targets, config_fingerprint,"
                    + " schema_version, created_at)"
                    + " VALUES ('" + tenantA + "'::uuid, 'it-rp-bad', 1, 'MATCH_BY_VIBES',"
                    + " '[\"a\"]'::jsonb, 'fp', 1, now())"))
                    // ⚠️ 断言**约束名**而不是「有没有报错」：
                    //    adminExec 把 SQLException 包成了 IllegalStateException，
                    //    约束名在 cause 里 → 用栈追踪断言才钉得到「是哪条约束拦的」。
                    .as("未知策略名必须被 CHECK 约束拒绝")
                    .hasStackTraceContaining("cap_routing_policy_strategy_ck");

            assertThat(adminQueryLong("SELECT count(*) FROM cap_routing_policy"
                    + " WHERE policy_id='it-rp-bad'"))
                    .as("被拒绝的行不得落库")
                    .isZero();
        }

        @Test
        @DisplayName("空目标数组被 PG 拒绝（策略必须显式声明允许的目标）")
        void rejectsEmptyTargets() {
            assertThatThrownBy(() -> adminExec("INSERT INTO cap_routing_policy (tenant_id,"
                    + " policy_id, version, strategy, targets, config_fingerprint,"
                    + " schema_version, created_at)"
                    + " VALUES ('" + tenantA + "'::uuid, 'it-rp-empty', 1, 'EXPLICIT_TARGETS',"
                    + " '[]'::jsonb, 'fp', 1, now())"))
                    .as("空 targets 必须被 CHECK 约束拒绝")
                    .hasStackTraceContaining("cap_routing_policy_targets_ck");
        }

        @Test
        @DisplayName("非正版本号被 PG 拒绝")
        void rejectsNonPositiveVersion() {
            assertThatThrownBy(() -> adminExec("INSERT INTO cap_routing_policy (tenant_id,"
                    + " policy_id, version, strategy, targets, config_fingerprint,"
                    + " schema_version, created_at)"
                    + " VALUES ('" + tenantA + "'::uuid, 'it-rp-v0', 0, 'EXPLICIT_TARGETS',"
                    + " '[\"a\"]'::jsonb, 'fp', 1, now())"))
                    .as("version <= 0 必须被 CHECK 约束拒绝")
                    .hasStackTraceContaining("cap_routing_policy_version_ck");
        }
    }

    // ── 租户隔离 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("cap_routing_policy 的租户隔离（RLS 真的在）")
    class TenantIsolation {

        @Test
        @DisplayName("跨租户读不到策略：连存在性都不泄漏")
        void crossTenantCannotRead() {
            policies.insert(policy(tenantA, "it-rp-secret", 1,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-a"), Instant.now()));

            // ⚠️ 必须用**非特权角色**（appPool）查：管理角色带 BYPASSRLS，
            //    用它查会得到「隔离失效」的虚假结论。
            assertThat(appCountAsTenant(tenantB,
                    "SELECT count(*) FROM cap_routing_policy WHERE policy_id='it-rp-secret'"))
                    .as("B 租户不应看到 A 租户的策略")
                    .isZero();
            assertThat(appCountAsTenant(tenantA,
                    "SELECT count(*) FROM cap_routing_policy WHERE policy_id='it-rp-secret'"))
                    .as("A 租户自己看得到")
                    .isEqualTo(1);
        }
    }

    // ── 运行期端口口径 ──────────────────────────────────────────────

    @Nested
    @DisplayName("RoutingPolicyProvider（运行期取策略的口径）")
    class ProviderSemantics {

        @Test
        @DisplayName("只返回已发布的固定版本：草稿取不到")
        void draftIsNotUsable() {
            policies.insert(policy(tenantA, "it-rp-pub", 1,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-a"), Instant.now()));
            policies.insert(policy(tenantA, "it-rp-pub", 2,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-b"), null));

            assertThat(provider.policyFor(tenantA, "it-rp-pub@1"))
                    .as("已发布版本可用")
                    .isNotNull();
            assertThat(provider.policyFor(tenantA, "it-rp-pub@2"))
                    .as("草稿版本不可用（先引用再发布是绕过发布流程的后门）")
                    .isNull();
        }

        @Test
        @DisplayName("引用不存在 / 格式非法 / 漂移别名一律取不到（调用方 fail-closed）")
        void unusableRefsReturnNull() {
            policies.insert(policy(tenantA, "it-rp-ref", 1,
                    RoutingStrategy.EXPLICIT_TARGETS, List.of("agent-a"), Instant.now()));

            assertThat(provider.policyFor(tenantA, "it-rp-ref@99"))
                    .as("版本不存在")
                    .isNull();
            assertThat(provider.policyFor(tenantA, "it-rp-ref"))
                    .as("裸 id 不是固定版本引用")
                    .isNull();
            assertThat(provider.policyFor(tenantA, "it-rp-ref@latest"))
                    .as("漂移别名必须被拒")
                    .isNull();
            assertThat(provider.policyFor(tenantA, "it-rp-missing@1"))
                    .as("策略不存在")
                    .isNull();
        }
    }
}
