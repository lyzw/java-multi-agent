package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;

/**
 * 闭包缓存装饰器测试（P2-T9 · §7.3 缓存 · 07 §4 配置读取与缓存失效）。
 *
 * <p>⚠️ <b>本类的核心命题是「缓存命中 ≠ 授权通过」</b>：
 * 命中只是<b>免去一次重解析</b>，不是「闭包没变」的证明。
 * 因此每条用例都必须验证「命中之后仍然向权威源问过」，
 * 否则一个「命中即放行」的缓存会把撤权与配置变更全部吞掉。
 */
class CachingClosureResolverTest {

    private static final String TENANT = "t-cache-001";
    private static final String AGENT = "homework_tutor";
    private static final String SKILL = "profile_skill";
    private static final String TOOL = "get_student_profile";

    private CapFixture cap;
    private PermissionResolver delegate;
    private CachingClosureResolver caching;

    @BeforeEach
    void setUp() {
        cap = new CapFixture()
                .withSkill(SKILL, 1, CapabilityRiskLevel.READ, List.of(TOOL))
                .withTool(TOOL, 1, CapabilityRiskLevel.READ, "[]")
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}");
        delegate = new PermissionResolver(cap.agentDefs(), cap.skillDefs(), cap.toolDefs(),
                cap.serverDefs(), cap.publishedVersions(), cap.manifest(),
                cap.referencedContent());
        caching = new CachingClosureResolver(delegate, cap.publishedVersions(), cap.toolDefs(),
                cap.agentDefs(), cap.manifest(),
                CachingClosureResolver.DEFAULT_MAX_ENTRIES, cap.referencedContent());
    }

    @Nested
    @DisplayName("命中后仍做权威新鲜度校验")
    class FreshnessOnHit {

        @Test
        @DisplayName("✅ 依赖未变 → 命中，且闭包与指纹逐字段相同")
        void unchangedDependenciesHitCache() {
            EffectiveClosure first = caching.resolveCurrent(TENANT, AGENT);
            EffectiveClosure second = caching.resolveCurrent(TENANT, AGENT);

            assertThat(second).as("依赖未变时闭包必须一致").isEqualTo(first);
            assertThat(ClosureFingerprint.of(second))
                    .as("依赖未变时指纹必须一致")
                    .isEqualTo(ClosureFingerprint.of(first));
            assertThat(caching.hitCount()).isEqualTo(1);
            assertThat(caching.missCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️⚠️ 依赖对象发布新版本 → 不得沿用旧闭包（缓存失效）")
        void changedDependencyInvalidatesCache() {
            EffectiveClosure before = caching.resolveCurrent(TENANT, AGENT);

            // ⚠️ 依赖的 Skill 发布新版本（内容变化）→ 闭包指纹必须变化
            cap.withSkill(SKILL, 2, CapabilityRiskLevel.READ, List.of(TOOL));
            cap.withTool(TOOL, 2, CapabilityRiskLevel.READ, "[]");

            EffectiveClosure after = caching.resolveCurrent(TENANT, AGENT);

            assertThat(after.skill(SKILL))
                    .as("⚠️⚠️ 依赖版本变化后必须回源重解析，否则会沿用旧闭包（旧指纹）")
                    .isPresent()
                    .get()
                    .satisfies(skill -> assertThat(skill.version()).isEqualTo(2));
            assertThat(ClosureFingerprint.of(after))
                    .as("⚠️ 闭包内容变化 → 指纹必须变化")
                    .isNotEqualTo(ClosureFingerprint.of(before));
            assertThat(caching.missCount()).as("回源一次").isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("「仅全局序号变、闭包未变」不误判")
    class GlobalSequenceChange {

        @Test
        @DisplayName("✅ 无关对象发布新版本 → 缓存命中、闭包与指纹不变")
        void unrelatedObjectChangeDoesNotInvalidate() {
            EffectiveClosure before = caching.resolveCurrent(TENANT, AGENT);
            String fingerprintBefore = ClosureFingerprint.of(before);

            // ⚠️ 无关对象（另一个 Tool）发布新版本 —— 模拟「全局发布序号变了」
            cap.withTool("unrelated_tool", 1, CapabilityRiskLevel.READ, "[]");
            cap.withTool("unrelated_tool", 2, CapabilityRiskLevel.READ, "[]");

            EffectiveClosure after = caching.resolveCurrent(TENANT, AGENT);

            assertThat(after)
                    .as("§7.3：无关对象变更不触发本 Run 失效")
                    .isEqualTo(before);
            assertThat(ClosureFingerprint.of(after))
                    .as("§7.3：仅全局序号变、闭包未变 → 指纹必须不变"
                            + "（不得因此判 CONFIG_FINGERPRINT_STALE）")
                    .isEqualTo(fingerprintBefore);
            assertThat(caching.hitCount())
                    .as("无关对象变更不应导致回源")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("✅ 指纹不含全局发布序号")
        void fingerprintDoesNotContainGlobalSequence() {
            EffectiveClosure closure = caching.resolveCurrent(TENANT, AGENT);
            String canonical = ClosureFingerprint.canonicalJson(closure);

            assertThat(canonical)
                    .as("§7.3/§6.4：闭包指纹不得混入全局发布序号，"
                            + "否则任意对象发布都会让全部在途 Run 失效")
                    .doesNotContain("configVersion")
                    .doesNotContain("config_version")
                    .doesNotContain("publish_seq");
        }
    }

    @Test
    @DisplayName("⛔ 同一 Agent 版本行的编排声明被改写 → 缓存必须失效（否则陈旧闭包藏住漂移）")
    void changedOrchestrationInvalidatesCache() {
        EffectiveClosure before = caching.resolve(TENANT, AGENT, 1);

        // ⚠️ 版本号不变、Agent 不变，只是编排声明被就地改写
        cap.withContract(TENANT, "contract", 1, "{}", true)
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}",
                "{\"mode\":\"SEQUENTIAL\",\"resultContractRef\":\"contract@1\",\"maxDelegationDepth\":3,\"maxChildInvocations\":8,\"timeoutMs\":30000}");

        EffectiveClosure after = caching.resolve(TENANT, AGENT, 1);

        assertThat(after.orchestration().resultContractRef())
                .as("⛔ 编排声明被改写却仍供出陈旧闭包：漂移在缓存层就被抹平了")
                .isEqualTo("contract@1");
        assertThat(ClosureFingerprint.of(after))
                .as("⛔ 陈旧闭包会让指纹不变：漂移在指纹层面不可察")
                .isNotEqualTo(ClosureFingerprint.of(before));
    }

    @Nested
    @DisplayName("缓存可运维性")
    class Operability {
        @Test
        @DisplayName("✅ 显式失效可清空指定 Agent 的条目")
        void invalidateRemovesEntries() {
            caching.resolveCurrent(TENANT, AGENT);
            assertThat(caching.size()).isEqualTo(1);

            int removed = caching.invalidate(TENANT, AGENT);

            assertThat(removed).isEqualTo(1);
            assertThat(caching.size()).isZero();
        }

        @Test
        @DisplayName("✅ 缓存有界：超出上限时淘汰最久未用的条目")
        void cacheIsBounded() {
            for (String agent : List.of("agent-a", "agent-b", "agent-c")) {
                cap.withAgent(TENANT, agent, 1, List.of(SKILL), "{}");
            }
            CachingClosureResolver bounded = new CachingClosureResolver(delegate,
                    cap.publishedVersions(), cap.toolDefs(), cap.agentDefs(), cap.manifest(), 2,
                    cap.referencedContent());

            bounded.resolveCurrent(TENANT, "agent-a");
            bounded.resolveCurrent(TENANT, "agent-b");
            bounded.resolveCurrent(TENANT, "agent-c");

            assertThat(bounded.size())
                    .as("⚠️ 无界缓存会随租户规模增长成为内存泄漏")
                    .isLessThanOrEqualTo(2);
        }
    }
}
