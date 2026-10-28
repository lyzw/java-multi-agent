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
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.RoutingMode;
import cn.studykid.multiagent.ports.orchestration.RoutingPolicyProvider;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;

/**
 * 路由策略表的 <b>DB 层</b>集成测试（10 号设计 §4.2 · B5 · 取代第十四批的旧行形态）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：DDL 快照与两个 JDBC 实现
 * （{@code JdbcCapRoutingPolicyRepository} / {@code JdbcRoutingPolicyProvider}）
 * 在写单元测试时<b>从未在真实数据库上执行过</b>。
 * mock 连接只能证明「调用了正确的 SQL 文本」，
 * 证不了「这段 SQL 在 PG 上真的能跑」（RLS 策略缺失、CHECK 约束不符、
 * {@code ?::jsonb} 转换失败、列名不匹配……）。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>R-1：策略可写入可读回，<b>规则次序保真</b>（首个命中即止依赖次序）；</li>
 *   <li>R-1b：<b>jsonb 的重排行为必须被观测到</b> —— 这不是要修 bugs，而是
 *       {@code CapObjectFingerprint} 必须对规范化形态稳定的<b>依据</b>。
 *       若本类发现读回文本与提交文本<b>逐字节相同</b>，
 *       说明列被改成了 {@code text}，那条指纹用例的前提就消失了；</li>
 *   <li>R-2：重复写入是<b>幂等 upsert</b>（不覆盖既有版本行）；</li>
 *   <li>R-3：<b>数据库约束真的在</b>：非法 mode / 空规则数组 / 缺兜底目标 /
 *       非正版本号，全都要被 PG 拒绝；</li>
 *   <li>R-4：租户隔离：RLS 让跨租户读不到；</li>
 *   <li>R-5：「已发布 / 草稿」两套口径可辨，运行期端口<b>只认已发布</b>。</li>
 * </ul>
 */
class RoutingPolicyPersistenceIT extends CapITBase {

    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}";

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

    /** 目标列表 → {@code {"const":false}} 规则数组（永不命中，但目标进了策略文本）。 */
    private static String rulesOf(List<String> targets) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"ordinal\":").append(i + 1).append(",\"ruleId\":\"r-").append(i + 1)
                    .append("\",\"target\":\"").append(targets.get(i))
                    .append("\",\"when\":{\"eq\":[\"in.topic\",\"")
                    .append("case-").append(i + 1).append("\"]}}");
        }
        return sb.append(']').toString();
    }

    private CapRoutingPolicyDef policy(String tenantId, String policyId, int version,
                                      NoMatchAction noMatchAction, List<String> targets,
                                      Instant publishedAt) {
        return new CapRoutingPolicyDef(tenantId, policyId, version, RoutingMode.RULES_ONLY,
                INPUT_SCHEMA, rulesOf(targets),
                noMatchAction == NoMatchAction.DEFAULT_TARGET && !targets.isEmpty()
                        ? targets.get(0) : null,
                noMatchAction, ConflictPolicy.RULE_WINS, "[]",
                "fp-" + policyId + "-" + version, 1, Instant.now(), publishedAt, null);
    }

    /** 从读回的 evaluation 文本里按出现次序取出目标（顺序保真的判据）。 */
    private static List<String> targetsInOrder(String evaluation) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int from = 0;
        while (true) {
            int hit = evaluation.indexOf("\"target\":", from);
            if (hit < 0) {
                return out;
            }
            int start = evaluation.indexOf('"', hit + 9) + 1;
            int end = evaluation.indexOf('"', start);
            out.add(evaluation.substring(start, end));
            from = end;
        }
    }

    // ── 基本存取 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("cap_routing_policy（路由策略定义行 · §4.2）")
    class PolicyRow {

        @Test
        @DisplayName("策略可写入可读回：规则次序必须保真（首个命中即止依赖次序）")
        void writesAndReadsBack() {
            CapRoutingPolicyDef def = policy(tenantA, "it-rp-orders", 1,
                    NoMatchAction.REJECT, List.of("agent-b", "agent-a", "agent-c"),
                    Instant.now());

            assertThat(policies.insert(def)).as("首次插入应成功").isTrue();

            Optional<CapRoutingPolicyDef> loaded = policies.find(tenantA, "it-rp-orders", 1);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().policyId()).isEqualTo("it-rp-orders");
            assertThat(loaded.get().version()).isEqualTo(1);
            assertThat(loaded.get().mode()).isEqualTo(RoutingMode.RULES_ONLY);
            assertThat(loaded.get().noMatchAction()).isEqualTo(NoMatchAction.REJECT);
            assertThat(loaded.get().onConflict()).isEqualTo(ConflictPolicy.RULE_WINS);
            // ⚠️ 次序是路由语义的一部分：PG 的 jsonb **不重排数组元素**。
            //    若读回次序变了，说明实现用错了容器（或列被换成了有序性无保证的类型）。
            assertThat(targetsInOrder(loaded.get().evaluation()))
                    .as("evaluation 必须按声明顺序读回")
                    .containsExactly("agent-b", "agent-a", "agent-c");
            assertThat(loaded.get().isPublished()).isTrue();
            assertThat(loaded.get().ref()).isEqualTo("it-rp-orders@1");
        }

        @Test
        @DisplayName("读回的是 jsonb <b>规范化后</b>的文本：键序会变、内容不变")
        void readBackIsJsonbNormalized() {
            // ⚠️ 这条断言是 CapObjectFingerprint「只排序对象键、保留数组顺序」的<b>依据</b>：
            //    库里存的就是另一个字节序列，指纹若按原文算，每次读回都会被判成「内容变了」。
            String submitted = "[{\"ordinal\":1,\"ruleId\":\"r-1\",\"target\":\"agent-a\","
                    + "\"when\":{\"const\":true}}]";
            policies.insert(new CapRoutingPolicyDef(tenantA, "it-rp-normalize", 1,
                    RoutingMode.RULES_ONLY, INPUT_SCHEMA, submitted, null,
                    NoMatchAction.REJECT, ConflictPolicy.RULE_WINS, "[]",
                    "fp-normalize", 1, Instant.now(), Instant.now(), null));

            String readBack = policies.find(tenantA, "it-rp-normalize", 1)
                    .orElseThrow().evaluation();
            assertThat(readBack)
                    .as("jsonb 会重排对象键：读回文本与提交文本<b>不该</b>逐字节相同")
                    .isNotEqualTo(submitted);
            assertThat(targetsInOrder(readBack)).containsExactly("agent-a");
            assertThat(readBack).as("规则数不变").containsOnlyOnce("agent-a");
        }

        @Test
        @DisplayName("default_target / sample_set 的存取：兜底目标为空时读回 null")
        void optionalColumnsRoundTrip() {
            policies.insert(policy(tenantA, "it-rp-fallback", 1,
                    NoMatchAction.DEFAULT_TARGET, List.of("agent-a", "agent-b"),
                    Instant.now()));

            CapRoutingPolicyDef loaded = policies.find(tenantA, "it-rp-fallback", 1).orElseThrow();
            assertThat(loaded.defaultTarget())
                    .as("兜底必须指名道姓，读回不能是空串")
                    .isEqualTo("agent-a");
            assertThat(loaded.sampleSet()).isEqualTo("[]");

            // 对照组：REJECT 不声明兜底 —— 列必须读回 null，而不是空串或首目标。
            policies.insert(policy(tenantA, "it-rp-reject", 1,
                    NoMatchAction.REJECT, List.of("agent-a"), Instant.now()));
            assertThat(policies.find(tenantA, "it-rp-reject", 1).orElseThrow().defaultTarget())
                    .isNull();
        }

        @Test
        @DisplayName("重复写入同版本是幂等 upsert：返回 false 且不覆盖既有内容")
        void duplicateInsertIsIdempotent() {
            CapRoutingPolicyDef first = policy(tenantA, "it-rp-dup", 1,
                    NoMatchAction.REJECT, List.of("agent-a"), Instant.now());
            assertThat(policies.insert(first)).isTrue();

            CapRoutingPolicyDef conflicting = policy(tenantA, "it-rp-dup", 1,
                    NoMatchAction.REJECT, List.of("agent-zzz"), Instant.now());
            assertThat(policies.insert(conflicting))
                    .as("同版本号重复写入应返回 false")
                    .isFalse();

            // ⚠️ 既有行不得被覆盖：版本行不可变是「在途 Run 按快照复核」的前提。
            assertThat(targetsInOrder(policies.find(tenantA, "it-rp-dup", 1)
                    .orElseThrow().evaluation())).containsExactly("agent-a");
        }

        @Test
        @DisplayName("findLatest 含草稿、findLatestPublished 不含草稿（版本号不得撞号）")
        void latestRespectsDraftBoundary() {
            policies.insert(policy(tenantA, "it-rp-draft", 1,
                    NoMatchAction.REJECT, List.of("agent-a"), null));
            policies.insert(policy(tenantA, "it-rp-draft", 2,
                    NoMatchAction.REJECT, List.of("agent-a"), Instant.now()));

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
                    NoMatchAction.REJECT, List.of("agent-a"), null));
            assertThat(policies.findLatestPublished(tenantA, "it-rp-only-draft")).isEmpty();
            assertThat(policies.findLatest(tenantA, "it-rp-only-draft")).isPresent();
        }
    }

    // ── 数据库约束 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("cap_routing_policy 的 DB 约束（不是靠应用自觉）")
    class DatabaseConstraints {

        @Test
        @DisplayName("非法 mode 被 PG 拒绝（CHECK 约束真的在）")
        void rejectsUnknownMode() {
            assertThatThrownBy(() -> adminExec("INSERT INTO cap_routing_policy (tenant_id,"
                    + " policy_id, version, mode, input_schema, evaluation, no_match_action,"
                    + " on_conflict, sample_set, config_fingerprint, schema_version, created_at)"
                    + " VALUES ('" + tenantA + "'::uuid, 'it-rp-bad', 1, 'MATCH_BY_VIBES',"
                    + " '{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}'::jsonb,"
                    + " '[{\"ordinal\":1,\"ruleId\":\"r-1\",\"target\":\"a\","
                    + "\"when\":{\"const\":true}}]'::jsonb,"
                    + " 'REJECT', 'RULE_WINS', '[]'::jsonb, 'fp', 1, now())"))
                    // ⚠️ 断言**约束名**而不是「有没有报错」：
                    //    adminExec 把 SQLException 包成了 IllegalStateException，
                    //    约束名在 cause 里 → 用栈追踪断言才钉得到「是哪条约束拦的」。
                    .as("未知 mode 必须被 CHECK 约束拒绝")
                    .hasStackTraceContaining("cap_routing_policy_mode_ck");

            assertThat(adminQueryLong("SELECT count(*) FROM cap_routing_policy"
                    + " WHERE policy_id='it-rp-bad'"))
                    .as("被拒绝的行不得落库")
                    .isZero();
        }

        @Test
        @DisplayName("RULES_ONLY 带空规则数组被 PG 拒绝（空规则不是「策略」，是配置缺陷）")
        void rejectsEmptyRulesForRuleModes() {
            assertThatThrownBy(() -> adminExec("INSERT INTO cap_routing_policy (tenant_id,"
                    + " policy_id, version, mode, input_schema, evaluation, no_match_action,"
                    + " on_conflict, sample_set, config_fingerprint, schema_version, created_at)"
                    + " VALUES ('" + tenantA + "'::uuid, 'it-rp-empty', 1, 'RULES_ONLY',"
                    + " '{}'::jsonb, '[]'::jsonb, 'REJECT', 'RULE_WINS', '[]'::jsonb,"
                    + " 'fp', 1, now())"))
                    .as("空 evaluation 必须被 CHECK 约束拒绝")
                    .hasStackTraceContaining("cap_routing_policy_eval_ck");

            // ⚠️ 反向一侧也要钉住：SEMANTIC 允许空规则。
            //    只测「拒」不测「放」，约束写严了（比如把 mode 条件漏掉）就发现不了。
            adminExec("INSERT INTO cap_routing_policy (tenant_id,"
                    + " policy_id, version, mode, input_schema, evaluation, no_match_action,"
                    + " on_conflict, sample_set, config_fingerprint, schema_version, created_at)"
                    + " VALUES ('" + tenantA + "'::uuid, 'it-rp-semantic', 1, 'SEMANTIC',"
                    + " '{}'::jsonb, '[]'::jsonb, 'REJECT', 'RULE_WINS', '[]'::jsonb,"
                    + " 'fp', 1, now())");
            assertThat(adminQueryLong("SELECT count(*) FROM cap_routing_policy"
                    + " WHERE policy_id='it-rp-semantic'")).isEqualTo(1);
        }

        @Test
        @DisplayName("no_match_action=DEFAULT_TARGET 缺 default_target 被 PG 拒绝")
        void requiresDefaultTargetForFallback() {
            assertThatThrownBy(() -> adminExec("INSERT INTO cap_routing_policy (tenant_id,"
                    + " policy_id, version, mode, input_schema, evaluation, no_match_action,"
                    + " on_conflict, sample_set, config_fingerprint, schema_version, created_at)"
                    + " VALUES ('" + tenantA + "'::uuid, 'it-rp-nofallback', 1, 'RULES_ONLY',"
                    + " '{}'::jsonb, '[{\"ordinal\":1,\"ruleId\":\"r-1\",\"target\":\"a\","
                    + "\"when\":{\"const\":true}}]'::jsonb,"
                    + " 'DEFAULT_TARGET', 'RULE_WINS', '[]'::jsonb, 'fp', 1, now())"))
                    .as("兜底动作必须指名兜底目标（与代码侧同口径，两处都得拒）")
                    .hasStackTraceContaining("cap_routing_policy_default_target_ck");
        }

        @Test
        @DisplayName("非正版本号被 PG 拒绝")
        void rejectsNonPositiveVersion() {
            assertThatThrownBy(() -> adminExec("INSERT INTO cap_routing_policy (tenant_id,"
                    + " policy_id, version, mode, input_schema, evaluation, no_match_action,"
                    + " on_conflict, sample_set, config_fingerprint, schema_version, created_at)"
                    + " VALUES ('" + tenantA + "'::uuid, 'it-rp-v0', 0, 'RULES_ONLY',"
                    + " '{}'::jsonb, '[{\"ordinal\":1,\"ruleId\":\"r-1\",\"target\":\"a\","
                    + "\"when\":{\"const\":true}}]'::jsonb,"
                    + " 'REJECT', 'RULE_WINS', '[]'::jsonb, 'fp', 1, now())"))
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
                    NoMatchAction.REJECT, List.of("agent-a"), Instant.now()));

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
                    NoMatchAction.REJECT, List.of("agent-a"), Instant.now()));
            policies.insert(policy(tenantA, "it-rp-pub", 2,
                    NoMatchAction.REJECT, List.of("agent-b"), null));

            assertThat(provider.policyFor(tenantA, "it-rp-pub@1"))
                    .as("已发布版本可用")
                    .isNotNull();
            assertThat(provider.policyFor(tenantA, "it-rp-pub@2"))
                    .as("草稿版本不可用（先引用再发布是绕过发布流程的后门）")
                    .isNull();
        }

        @Test
        @DisplayName("取回的行走 definitionJson 时必须是<b>可解析</b>的策略文本")
        void definitionJsonIsParseable() {
            policies.insert(policy(tenantA, "it-rp-shape", 1,
                    NoMatchAction.DEFAULT_TARGET, List.of("agent-a", "agent-b"),
                    Instant.now()));

            CapRoutingPolicyDef loaded = policies.find(tenantA, "it-rp-shape", 1).orElseThrow();
            assertThat(loaded.definitionJson())
                    .as("行字段拼回的必须还是合法 JSON：引号/转义错了就在引擎里炸")
                    .startsWith("{")
                    .endsWith("}")
                    .contains("\"mode\":\"RULES_ONLY\"")
                    .contains("\"defaultTarget\":\"agent-a\"");
        }

        @Test
        @DisplayName("引用不存在 / 格式非法 / 漂移别名一律取不到（调用方 fail-closed）")
        void unusableRefsReturnNull() {
            policies.insert(policy(tenantA, "it-rp-ref", 1,
                    NoMatchAction.REJECT, List.of("agent-a"), Instant.now()));

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
